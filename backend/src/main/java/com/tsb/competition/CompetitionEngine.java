package com.tsb.competition;

import com.tsb.compiler.CompiledStrategy;
import com.tsb.compiler.Expr;
import com.tsb.compiler.StrategyAst;
import com.tsb.execution.Backtester;
import com.tsb.execution.ExchangeRules;
import com.tsb.execution.IndicatorBank;
import com.tsb.execution.Interpreter;
import com.tsb.execution.ProtectiveExits;
import com.tsb.execution.Sizing;
import com.tsb.execution.Trade;
import com.tsb.marketdata.CandleSeries;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Processes ONE entry through ONE new candle — the inner loop of the "Run"
 * section of the competition sequence diagram.
 *
 * <p><b>Same fill model as the backtester, step for step.</b> A rule that
 * fires at a candle's close places an order; the order fills at the NEXT
 * candle's open. Stops and take-profits fill intrabar at their level, stop
 * first — or at the open when the candle gaps past them ({@link ProtectiveExits},
 * the same code the backtester uses). So with every limit switched off, stepping this engine through a
 * series produces exactly the trades {@code Backtester} produces — the tests
 * check that trade for trade. The competition adds only the rules on top.
 *
 * <p>The order of work for each candle, mapped to the diagram:
 * <ol>
 *   <li>eliminated earlier → skip;</li>
 *   <li>new UTC day → trading days +1 if it traded yesterday, reset the
 *       daily loss, trade count and halt;</li>
 *   <li>orders from the last close fill at this open — "enter a trade" /
 *       "exit the trade, sell signal fired" (a halted entry cannot enter);</li>
 *   <li>in a trade: stop-loss / take-profit / trailing stop — "check exit
 *       rules";</li>
 *   <li>value at this candle's close, then "check max drawdown" → eliminated
 *       (any open position is closed at this close);</li>
 *   <li>strategy rules at the close — "check exit / entry rules", which
 *       place orders for the next open (a halted, flat entry cannot enter);</li>
 *   <li>a trade opened or closed this candle → "check daily loss and trade
 *       count" → halted for the rest of today.</li>
 * </ol>
 *
 * <p>Fills count toward the daily trade limit one each: opening a trade is
 * one, closing it another, as in the diagram ("trade count plus one" on both).
 */
public final class CompetitionEngine {

    private static final long DAY_MS = 86_400_000L;

    /** Something the service must save: a trade opened or closed. */
    public sealed interface Event {
    }

    public record Opened(long time, double qty, double price, double fees) implements Event {
    }

    /**
     * @param reason SIGNAL, STOPLOSS, TAKEPROFIT, TRAILING, ELIMINATED or
     *               END_OF_COMPETITION
     */
    public record Closed(long entryTime, long exitTime, double qty, double entryPrice,
                         double exitPrice, double fees, double pnl, String reason)
            implements Event {
    }

    private final CompiledStrategy strategy;
    private final CandleSeries series;
    private final Interpreter interp;
    private final ExchangeRules exchange;
    private final RuleSet rules;
    private final double fee;

    /**
     * @param strategy the entry's locked strategy; its capital and fee are
     *                 replaced by the competition's, so every entry plays
     *                 with the same money under the same costs
     */
    public CompetitionEngine(CompiledStrategy strategy, CandleSeries series,
                             ExchangeRules exchange, RuleSet rules) {
        this.strategy = withCompetitionMoney(strategy, rules);
        this.series = series;
        this.exchange = exchange;
        this.rules = rules;
        this.fee = rules.feeFraction();
        this.interp = new Interpreter(this.strategy, series,
                IndicatorBank.compute(this.strategy, series));
    }

    static CompiledStrategy withCompetitionMoney(CompiledStrategy s, RuleSet r) {
        return new CompiledStrategy(s.name(), s.symbol(), s.timeframe(),
                r.startingCapital(), r.feeFraction(), s.lets(), s.rules(),
                s.indicators(), s.warmupBars());
    }

    public CompiledStrategy strategy() {
        return strategy;
    }

    // ── One candle ──────────────────────────────────────────────────────

    public List<Event> step(EntryState s, int i) {
        List<Event> events = new ArrayList<>();
        if (s.finished()) {
            return events; // eliminated earlier: skip this entry
        }
        long t = series.openTimeMillis()[i];
        double open = series.open()[i];
        double high = series.high()[i];
        double low = series.low()[i];
        double close = series.close()[i];

        // ── New day? ────────────────────────────────────────────────
        long day = Math.floorDiv(t, DAY_MS);
        if (day != s.day) {
            if (s.day != Long.MIN_VALUE && s.tradedToday) {
                s.tradingDays++;
            }
            s.day = day;
            s.tradesToday = 0;
            s.tradedToday = false;
            s.haltedToday = false;
            s.haltReason = null;
            s.dayStartEquity = s.equity;
        }

        boolean tradedThisCandle = false;

        // ── Orders from the last close fill at this open ───────────
        for (EntryState.Pending order : s.pending) {
            StrategyAst.IfStmt stmt = statement(order);
            StrategyAst.Action action = order.elseBranch()
                    ? stmt.elseAction().orElseThrow() : stmt.thenAction();
            if (order.buy()) {
                if (s.qty > 0 || s.haltedToday) {
                    continue; // one position at a time / halted for today
                }
                StrategyAst.Sizing sizing = ((StrategyAst.Action.Buy) action).sizing();
                double rounded = exchange.roundQty(
                        Sizing.desiredBuyQty(sizing, s.cash, open, fee));
                if (rounded <= 0 || !exchange.meetsMinNotional(rounded, open)
                        || !Sizing.affordable(rounded, s.cash, open, fee)) {
                    continue; // too small, or more than the cash: same as the backtester
                }
                double notional = rounded * open;
                double feePaid = notional * fee;
                s.cash -= notional + feePaid;
                s.qty = rounded;
                s.entryPrice = open;
                s.entryTime = t;
                s.entryFees = feePaid;
                // Same as the backtester: the peak starts at the entry price,
                // and this candle's high only counts after the stops.
                s.peakSinceEntry = open;
                countFill(s);
                tradedThisCandle = true;
                events.add(new Opened(t, rounded, open, feePaid));
                checkDailyLimits(s, open);
            } else {
                if (s.qty <= 0) {
                    continue;
                }
                StrategyAst.Sizing sizing = ((StrategyAst.Action.Sell) action).sizing();
                double sellQty = Backtester.sellSize(sizing, s.qty, s.cash, open, exchange);
                if (sellQty <= 0) {
                    continue;
                }
                s.cash += Sizing.exitProceeds(sellQty, open, fee);
                if (sellQty >= s.qty - 1e-12) {
                    events.add(closed(s, s.qty, open, s.entryFees, t, "SIGNAL"));
                    flatten(s);
                } else {
                    double share = sellQty / s.qty;
                    events.add(closed(s, sellQty, open, s.entryFees * share, t, "SIGNAL"));
                    s.entryFees *= (1 - share);
                    s.qty -= sellQty;
                }
                countFill(s);
                tradedThisCandle = true;
                checkDailyLimits(s, open);
            }
        }
        s.pending.clear();

        // ── In a trade: stop-loss / trailing / take-profit ─────────
        if (s.qty > 0) {
            // The very same rules as the backtester, from the very same code.
            ProtectiveExits.Exit exit = ProtectiveExits.check(s.entryPrice,
                    s.peakSinceEntry, s.stopPct, s.trailingPct, s.takeProfitPct,
                    open, high, low);

            // Max drawdown INSIDE the candle, not only at its close: a candle
            // that dips 15% and closes 8% down must still knock out a 10%
            // limit. Same pessimism as the stops — prices fall first — so
            // the entry is out where equity crossed the limit (at the open
            // when it gapped through), unless its own stop or trail sat
            // higher and fired first. A take-profit is on the rising side,
            // so it comes after the fall.
            double limitEquity = s.peakEquity * (1 - rules.maxDrawdownPct() / 100.0);
            double breach = (limitEquity - s.cash) / s.qty; // price where equity = limit
            boolean stopFirst = exit != null
                    && exit.reason() != Trade.ExitReason.TAKEPROFIT
                    && exit.price() >= breach;
            if (low < breach && !stopFirst) {
                double price = Math.min(open, breach);
                s.cash += Sizing.exitProceeds(s.qty, price, fee);
                events.add(closed(s, s.qty, price, s.entryFees, t, "ELIMINATED"));
                flatten(s);
                s.equity = s.cash;
                double fell = s.drawdownPct();
                s.maxDrawdownSeen = Math.max(s.maxDrawdownSeen, fell);
                s.status = EntryState.Status.ELIMINATED;
                s.statusReason = String.format(Locale.ROOT,
                        "Max drawdown breached during the candle: equity fell %.2f%% "
                                + "from its best (limit %.2f%%).",
                        fell, rules.maxDrawdownPct());
                s.statusCandle = t;
                s.lastCandle = t;
                s.pending.clear();
                return events; // stop: this entry is finished
            }

            if (exit != null) {
                s.cash += Sizing.exitProceeds(s.qty, exit.price(), fee);
                events.add(closed(s, s.qty, exit.price(), s.entryFees, t,
                        exit.reason().name()));
                flatten(s);
                countFill(s);
                tradedThisCandle = true;
            } else {
                s.peakSinceEntry = Math.max(s.peakSinceEntry, high);
            }
        }

        // ── Value at this close, then the max drawdown rule ────────
        s.equity = s.cash + s.qty * close;
        s.peakEquity = Math.max(s.peakEquity, s.equity);
        double dd = s.drawdownPct();
        s.maxDrawdownSeen = Math.max(s.maxDrawdownSeen, dd);
        if (dd > rules.maxDrawdownPct()) {
            if (s.qty > 0) {
                s.cash += Sizing.exitProceeds(s.qty, close, fee);
                events.add(closed(s, s.qty, close, s.entryFees, t, "ELIMINATED"));
                flatten(s);
                s.equity = s.cash;
            }
            s.status = EntryState.Status.ELIMINATED;
            s.statusReason = String.format(Locale.ROOT,
                    "Max drawdown breached: equity fell %.2f%% from its best (limit %.2f%%).",
                    dd, rules.maxDrawdownPct());
            s.statusCandle = t;
            s.lastCandle = t;
            return events; // stop: this entry is finished
        }

        // ── Strategy rules at the close → orders for the next open ─
        if (i >= strategy.warmupBars()) {
            List<StrategyAst.RuleDecl> ruleList = strategy.rules();
            for (int r = 0; r < ruleList.size(); r++) {
                List<StrategyAst.IfStmt> body = ruleList.get(r).body();
                for (int k = 0; k < body.size(); k++) {
                    StrategyAst.IfStmt stmt = body.get(k);
                    // Same rule as the backtest: ELSE needs a definite
                    // false; no answer (missing data) runs neither branch.
                    byte answer = interp.tri(stmt.condition(), i);
                    boolean then = answer == Interpreter.TRUE;
                    StrategyAst.Action action = then
                            ? stmt.thenAction()
                            : answer == Interpreter.FALSE
                                    ? stmt.elseAction().orElse(null)
                                    : null;
                    if (action == null) {
                        continue;
                    }
                    switch (action) {
                        case StrategyAst.Action.Buy b -> {
                            if (!(s.qty <= 0 && s.haltedToday)) { // halted: cannot enter
                                s.pending.add(new EntryState.Pending(true, r, k, !then));
                            }
                        }
                        case StrategyAst.Action.Sell x ->
                                s.pending.add(new EntryState.Pending(false, r, k, !then));
                        case StrategyAst.Action.Set set -> {
                            double pct = ((Expr.PercentLit) set.value()).value();
                            switch (set.target()) {
                                case STOPLOSS -> s.stopPct = pct;
                                case TAKEPROFIT -> s.takeProfitPct = pct;
                                case TRAILING -> s.trailingPct = pct;
                            }
                        }
                    }
                }
            }
        }

        // ── A trade happened: daily loss and trade count ───────────
        if (tradedThisCandle) {
            checkDailyLimits(s, close);
        }

        s.lastCandle = t;
        return events;
    }

    // ── End of competition ──────────────────────────────────────────────

    /**
     * Closes any open trade at the last candle's close, counts the final
     * day, and decides pass or fail. An entry eliminated during the run
     * keeps its FAILED-for-a-reason status.
     */
    public List<Event> finish(EntryState s, int lastBar) {
        List<Event> events = new ArrayList<>();
        if (s.status == EntryState.Status.ELIMINATED) {
            return events;
        }
        if (s.finished()) {
            return events; // already finished (idempotent)
        }
        if (s.qty > 0 && lastBar >= 0) {
            double close = series.close()[lastBar];
            s.cash += Sizing.exitProceeds(s.qty, close, fee);
            events.add(closed(s, s.qty, close, s.entryFees,
                    series.openTimeMillis()[lastBar], "END_OF_COMPETITION"));
            flatten(s);
        }
        s.equity = s.cash;
        if (s.tradedToday) {
            s.tradingDays++;
            s.tradedToday = false;
        }
        s.pending.clear();

        double ret = s.returnPct(rules);
        boolean target = ret >= rules.profitTargetPct();
        boolean days = s.tradingDays >= rules.minTradingDays();
        if (target && days) {
            s.status = EntryState.Status.PASSED;
            s.statusReason = String.format(Locale.ROOT,
                    "Passed: %+.2f%% (target %.2f%%) over %d trading days.",
                    ret, rules.profitTargetPct(), s.tradingDays);
        } else {
            s.status = EntryState.Status.FAILED;
            s.statusReason = !target
                    ? String.format(Locale.ROOT, "Profit target not met: %+.2f%% of %.2f%%.",
                            ret, rules.profitTargetPct())
                    : "Not enough trading days: " + s.tradingDays + " of "
                            + rules.minTradingDays() + ".";
        }
        s.statusCandle = lastBar >= 0 ? series.openTimeMillis()[lastBar] : -1;
        return events;
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    private StrategyAst.IfStmt statement(EntryState.Pending p) {
        return strategy.rules().get(p.rule()).body().get(p.stmt());
    }

    private Closed closed(EntryState s, double qty, double exitPrice,
                          double entryFees, long exitTime, String reason) {
        double exitFee = qty * exitPrice * fee;
        double pnl = qty * (exitPrice - s.entryPrice) - entryFees - exitFee;
        return new Closed(s.entryTime, exitTime, qty, s.entryPrice, exitPrice,
                entryFees + exitFee, pnl, reason);
    }

    private static void flatten(EntryState s) {
        s.qty = 0;
        s.stopPct = 0;
        s.takeProfitPct = 0;
        s.trailingPct = 0;
    }

    /**
     * The daily rules — loss limit and trade count — valued at {@code mark}.
     * Checked after every fill as well as at the close, so a second order
     * filling at the same open cannot slip past a limit the first one just
     * reached. A halt only stops new entries; exits are always allowed.
     */
    private void checkDailyLimits(EntryState s, double mark) {
        if (s.haltedToday) {
            return;
        }
        double equityNow = s.cash + s.qty * mark;
        double lossPct = s.dayStartEquity > 0
                ? Math.max(0, (s.dayStartEquity - equityNow) / s.dayStartEquity * 100) : 0;
        if (rules.dailyLossLimitPct() > 0 && lossPct >= rules.dailyLossLimitPct()) {
            halt(s, String.format(Locale.ROOT,
                    "Daily loss limit hit: down %.2f%% today (limit %.2f%%).",
                    lossPct, rules.dailyLossLimitPct()));
        } else if (rules.maxTradesPerDay() > 0
                && s.tradesToday >= rules.maxTradesPerDay()) {
            halt(s, "Max trades per day reached (" + rules.maxTradesPerDay() + ").");
        }
    }

    private static void countFill(EntryState s) {
        s.tradedToday = true;
        s.tradesToday++;
        s.tradeCount++;
    }

    private static void halt(EntryState s, String reason) {
        s.haltedToday = true;
        s.haltReason = reason;
    }
}
