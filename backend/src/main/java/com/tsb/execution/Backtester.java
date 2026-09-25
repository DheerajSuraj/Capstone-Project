package com.tsb.execution;

import com.tsb.compiler.CompiledStrategy;
import com.tsb.compiler.ConstFold;
import com.tsb.compiler.Expr;
import com.tsb.compiler.StrategyAst;
import com.tsb.marketdata.CandleSeries;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * The engine loop. Deterministic, single-position, long-only (per the MVP
 * grammar), pessimistic on ties.
 *
 * <p><b>Per-bar order of operations</b> — the fill-model contract:
 * <ol>
 *   <li><b>Fill pending orders at open[i]</b> — orders born from bar i-1's
 *       signals. Signal on close, fill at NEXT open: decisions use only
 *       closed-bar information, transactions happen at the next price that
 *       actually existed. (No-look-ahead, final layer.)</li>
 *   <li><b>Protective exits intrabar</b> — long stop fires if low[i] <=
 *       stop level, FILLED AT THE LEVEL (not the low: we do not award
 *       ourselves the best price of the bar). Take-profit mirrors on
 *       high[i]. Both in one bar -> the STOP wins: when intrabar ordering
 *       is unknowable, assume the worse outcome.</li>
 *   <li><b>Evaluate rules at close[i]</b> — THEN on true, ELSE on false;
 *       BUY/SELL become pending orders for i+1; SET configures protective
 *       exits immediately.</li>
 * </ol>
 *
 * <p>Consequences, all deliberate: a signal on the last bar never fills
 * (there is no next open); execution starts at the compiler's warmupBars;
 * BUY while long and SELL while flat are ignored (no pyramiding — every
 * trade is a clean entry/exit pair); an open position at end of data is
 * closed at the last close and tagged END_OF_DATA so results never hide an
 * open loser.
 */
public final class Backtester {

    /** What a rule decided at a close, awaiting the next open. The origin
     *  (which rule, which statement, which bar) is carried only so an
     *  observer can be told what became of it; it plays no part in filling. */
    private sealed interface Pending {
        Origin origin();

        record Buy(StrategyAst.Sizing sizing, Origin origin) implements Pending {}
        record Sell(StrategyAst.Sizing sizing, Origin origin) implements Pending {}
    }

    private record Origin(int bar, int rule, int stmt) {}

    public BacktestResult run(CompiledStrategy strategy, CandleSeries series,
                              ExchangeRules rules) {
        return run(strategy, series, rules, BarObserver.NONE);
    }

    /**
     * Same run, with an observer told what the engine did at each close and
     * to each order. The observer cannot influence the run.
     */
    public BacktestResult run(CompiledStrategy strategy, CandleSeries series,
                              ExchangeRules rules, BarObserver observer) {
        int n = series.size();
        double[] equity = new double[n];
        List<Trade> trades = new ArrayList<>();
        if (n == 0) {
            return new BacktestResult(strategy.capital(), strategy.capital(),
                    equity, trades, strategy.warmupBars(), 0, 0);
        }

        Interpreter interp = new Interpreter(strategy.lets(), series,
                IndicatorBank.compute(strategy.indicators(), series));
        double fee = strategy.feePercent();

        // ── Portfolio state ─────────────────────────────────────────────
        double cash = strategy.capital();
        double qty = 0;                    // base asset held
        double entryPrice = 0;
        int entryBar = -1;
        double entryFees = 0;
        double peakSinceEntry = 0;         // for trailing stops
        // Protective config (percent as written, e.g. 5.0 for 5%).
        double stopPct = 0;
        double takeProfitPct = 0;
        double trailingPct = 0;

        List<Pending> pending = new ArrayList<>();
        int firstBar = Math.min(strategy.warmupBars(), n);

        for (int i = firstBar; i < n; i++) {
            double open = series.open()[i];
            double high = series.high()[i];
            double low = series.low()[i];
            double close = series.close()[i];
            Instant barTime = Instant.ofEpochMilli(series.openTimeMillis()[i]);

            // ── 1. Fill pending orders at this bar's open ───────────────
            // Pattern-matching if/instanceof rather than a switch statement:
            // an arrow-form switch RULE may not use `break` to exit early,
            // and these fills need guard-style early exits.
            for (Pending order : pending) {
                if (order instanceof Pending.Buy b) {
                    if (qty > 0) {
                        report(observer, order, i,
                                BarObserver.FillOutcome.IGNORED_ALREADY_LONG);
                        continue; // single position: ignore add-ons
                    }
                    double desired = desiredBuyQty(b.sizing(), cash, open, fee);
                    double rounded = rules.roundQty(desired);
                    if (rounded <= 0 || !rules.meetsMinNotional(rounded, open)) {
                        report(observer, order, i,
                                BarObserver.FillOutcome.REJECTED_TOO_SMALL);
                        continue; // unplaceable on a real exchange -> no fill
                    }
                    double notional = rounded * open;
                    double feePaid = notional * fee;
                    cash -= notional + feePaid;
                    qty = rounded;
                    entryPrice = open;
                    entryBar = i;
                    entryFees = feePaid;
                    peakSinceEntry = high;
                    report(observer, order, i, BarObserver.FillOutcome.FILLED);
                } else if (order instanceof Pending.Sell s) {
                    if (qty <= 0) {
                        report(observer, order, i,
                                BarObserver.FillOutcome.IGNORED_NOTHING_TO_SELL);
                        continue; // nothing to sell
                    }
                    double sellQty = Math.min(qty,
                            rules.roundQty(desiredSellQty(s.sizing(), qty)));
                    if (sellQty <= 0) {
                        report(observer, order, i,
                                BarObserver.FillOutcome.REJECTED_TOO_SMALL);
                        continue;
                    }
                    report(observer, order, i, BarObserver.FillOutcome.FILLED);
                    cash += exitProceeds(sellQty, open, fee);
                    if (sellQty >= qty - 1e-12) {
                        trades.add(makeTrade(series, entryBar, i, qty,
                                entryPrice, open, entryFees, fee,
                                Trade.ExitReason.SIGNAL));
                        qty = 0;
                        stopPct = takeProfitPct = trailingPct = 0;
                    } else {
                        // Partial exit: realize a proportional trade. Note
                        // the ordering — the fee share and the remaining
                        // entryFees are both computed against the PRE-exit
                        // qty, so they must be read before qty shrinks.
                        double share = sellQty / qty;
                        trades.add(makeTrade(series, entryBar, i, sellQty,
                                entryPrice, open, entryFees * share, fee,
                                Trade.ExitReason.SIGNAL));
                        entryFees *= (1 - share);
                        qty -= sellQty;
                    }
                }
            }
            pending.clear();

            // ── 2. Protective exits, checked intrabar ───────────────────
            if (qty > 0) {
                peakSinceEntry = Math.max(peakSinceEntry, high);
                Double exitLevel = null;
                Trade.ExitReason reason = null;

                if (stopPct > 0) {
                    double level = entryPrice * (1 - stopPct / 100.0);
                    if (low <= level) {
                        exitLevel = level;
                        reason = Trade.ExitReason.STOPLOSS;
                    }
                }
                if (exitLevel == null && trailingPct > 0) {
                    double level = peakSinceEntry * (1 - trailingPct / 100.0);
                    if (low <= level) {
                        exitLevel = level;
                        reason = Trade.ExitReason.TRAILING;
                    }
                }
                if (exitLevel == null && takeProfitPct > 0) {
                    double level = entryPrice * (1 + takeProfitPct / 100.0);
                    if (high >= level) {
                        exitLevel = level;
                        reason = Trade.ExitReason.TAKEPROFIT;
                    }
                }

                if (exitLevel != null) {
                    cash += exitProceeds(qty, exitLevel, fee);
                    trades.add(makeTrade(series, entryBar, i, qty, entryPrice,
                            exitLevel, entryFees, fee, reason));
                    qty = 0;
                    stopPct = takeProfitPct = trailingPct = 0;
                }
            }

            // ── 3. Rules at the close -> pending orders / SET config ────
            observer.onClose(i, qty > 0);
            List<StrategyAst.RuleDecl> ruleList = strategy.rules();
            for (int r = 0; r < ruleList.size(); r++) {
                List<StrategyAst.IfStmt> body = ruleList.get(r).body();
                for (int k = 0; k < body.size(); k++) {
                    StrategyAst.IfStmt stmt = body.get(k);
                    StrategyAst.Action action = interp.bool(stmt.condition(), i)
                            ? stmt.thenAction()
                            : stmt.elseAction().orElse(null);
                    if (action == null) {
                        continue;
                    }
                    switch (action) {
                        case StrategyAst.Action.Buy b -> pending.add(
                                new Pending.Buy(b.sizing(), new Origin(i, r, k)));
                        case StrategyAst.Action.Sell s -> pending.add(
                                new Pending.Sell(s.sizing(), new Origin(i, r, k)));
                        case StrategyAst.Action.Set set -> {
                            double pct = ((Expr.PercentLit) set.value()).value();
                            switch (set.target()) {
                                case STOPLOSS -> stopPct = pct;
                                case TAKEPROFIT -> takeProfitPct = pct;
                                case TRAILING -> trailingPct = pct;
                            }
                        }
                    }
                }
            }

            equity[i] = cash + qty * close;
        }

        // Bars before firstBar hold flat capital, for an honest curve.
        for (int i = 0; i < firstBar && i < n; i++) {
            equity[i] = strategy.capital();
        }

        // ── End of data: close any open position at the last close ──────
        double finalEquity = cash + qty * series.close()[n - 1];
        if (qty > 0) {
            double lastClose = series.close()[n - 1];
            cash += exitProceeds(qty, lastClose, fee);
            trades.add(makeTrade(series, entryBar, n - 1, qty, entryPrice,
                    lastClose, entryFees, fee, Trade.ExitReason.END_OF_DATA));
            finalEquity = cash;
            equity[n - 1] = cash;
        }

        return new BacktestResult(strategy.capital(), finalEquity, equity,
                trades, strategy.warmupBars(), Math.max(0, n - firstBar),
                maxDrawdownPct(equity, firstBar));
    }

    private static void report(BarObserver observer, Pending order, int fillBar,
                               BarObserver.FillOutcome outcome) {
        Origin o = order.origin();
        observer.onOrder(o.bar(), fillBar, o.rule(), o.stmt(), outcome);
    }

    // ── Sizing ──────────────────────────────────────────────────────────

    private static double desiredBuyQty(StrategyAst.Sizing sizing, double cash,
                                        double price, double fee) {
        double budget = switch (sizing) {
            case StrategyAst.Sizing.All ignored -> cash;
            case StrategyAst.Sizing.PercentOf p ->
                    cash * ((Expr.PercentLit) p.percent()).value() / 100.0;
            case StrategyAst.Sizing.Quantity q -> {
                double amount = ConstFold.fold(q.amount()).orElse(0.0);
                yield amount * price; // fixed base-asset qty -> its notional
            }
        };
        // Budget covers notional + fee: qty*price*(1+fee) <= budget.
        return budget / (price * (1 + fee));
    }

    private static double desiredSellQty(StrategyAst.Sizing sizing, double held) {
        return switch (sizing) {
            case StrategyAst.Sizing.All ignored -> held;
            case StrategyAst.Sizing.PercentOf p ->
                    held * ((Expr.PercentLit) p.percent()).value() / 100.0;
            case StrategyAst.Sizing.Quantity q ->
                    ConstFold.fold(q.amount()).orElse(0.0);
        };
    }

    private static double exitProceeds(double qty, double price, double fee) {
        double notional = qty * price;
        return notional - notional * fee;
    }

    private static Trade makeTrade(CandleSeries s, int entryBar, int exitBar,
                                   double qty, double entryPrice,
                                   double exitPrice, double entryFees,
                                   double fee, Trade.ExitReason reason) {
        double exitFee = qty * exitPrice * fee;
        double pnl = qty * (exitPrice - entryPrice) - entryFees - exitFee;
        return new Trade(entryBar, exitBar,
                Instant.ofEpochMilli(s.openTimeMillis()[entryBar]),
                Instant.ofEpochMilli(s.openTimeMillis()[exitBar]),
                qty, entryPrice, exitPrice, entryFees + exitFee, pnl, reason);
    }

    private static double maxDrawdownPct(double[] equity, int from) {
        double peak = Double.MIN_VALUE;
        double maxDd = 0;
        for (int i = from; i < equity.length; i++) {
            peak = Math.max(peak, equity[i]);
            if (peak > 0) {
                maxDd = Math.max(maxDd, (peak - equity[i]) / peak * 100.0);
            }
        }
        return maxDd;
    }
}