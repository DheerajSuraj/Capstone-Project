package com.tsb.competition;

import com.tsb.compiler.CompiledStrategy;
import com.tsb.compiler.Expr;
import com.tsb.compiler.StrategyAst;
import com.tsb.execution.ExchangeRules;
import com.tsb.execution.IndicatorBank;
import com.tsb.execution.Interpreter;
import com.tsb.execution.Sizing;
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
 * first. So with every limit switched off, stepping this engine through a
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
        this.interp = new Interpreter(this.strategy.lets(), series,
                IndicatorBank.compute(this.strategy.indicators(), series));
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
                if (rounded <= 0 || !exchange.meetsMinNotional(rounded, open)) {
                    continue;
                }
                double notional = rounded * open;
                double feePaid = notional * fee;
                s.cash -= notional + feePaid;
                s.qty = rounded;
                s.entryPrice = open;
                s.entryTime = t;
                s.entryFees = feePaid;
                s.peakSinceEntry = high;
                countFill(s);
                tradedThisCandle = true;
                events.add(new Opened(t, rounded, open, feePaid));
            } else {
                if (s.qty <= 0) {
                    continue;
                }
                StrategyAst.Sizing sizing = ((StrategyAst.Action.Sell) action).sizing();
                double sellQty = Math.min(s.qty,
                        exchange.roundQty(Sizing.desiredSellQty(sizing, s.qty)));
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
            }
        }
        s.pending.clear();

        // ── In a trade: stop-loss / trailing / take-profit ─────────
        if (s.qty > 0) {
            s.peakSinceEntry = Math.max(s.peakSinceEntry, high);
            Double level = null;
            String reason = null;
            if (s.stopPct > 0) {
                double l = s.entryPrice * (1 - s.stopPct / 100.0);
                if (low <= l) {
                    level = l;
                    reason = "STOPLOSS";
                }
            }
            if (level == null && s.trailingPct > 0) {
                double l = s.peakSinceEntry * (1 - s.trailingPct / 100.0);
                if (low <= l) {
                    level = l;
                    reason = "TRAILING";
                }
            }
            if (level == null && s.takeProfitPct > 0) {
                double l = s.entryPrice * (1 + s.takeProfitPct / 100.0);
                if (high >= l) {
                    level = l;
                    reason = "TAKEPROFIT";
                }
            }
            if (level != null) {
                s.cash += Sizing.exitProceeds(s.qty, level, fee);
                events.add(closed(s, s.qty, level, s.entryFees, t, reason));
                flatten(s);
                countFill(s);
                tradedThisCandle = true;
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
                    boolean then = interp.bool(stmt.condition(), i);
                    StrategyAst.Action action = then
                            ? stmt.thenAction()
                            : stmt.elseAction().orElse(null);
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
        if (tradedThisCandle && !s.haltedToday) {
            if (rules.dailyLossLimitPct() > 0
                    && s.dailyLossPct() >= rules.dailyLossLimitPct()) {
                halt(s, String.format(Locale.ROOT,
                        "Daily loss limit hit: down %.2f%% today (limit %.2f%%).",
                        s.dailyLossPct(), rules.dailyLossLimitPct()));
            } else if (rules.maxTradesPerDay() > 0
                    && s.tradesToday >= rules.maxTradesPerDay()) {
                halt(s, "Max trades per day reached (" + rules.maxTradesPerDay() + ").");
            }
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
