package com.tsb.competition;

import com.tsb.execution.ExchangeRules;
import com.tsb.marketdata.CandleSeries;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static com.tsb.competition.CompetitionEngineTest.HOUR;
import static com.tsb.competition.CompetitionEngineTest.compile;
import static com.tsb.competition.CompetitionEngineTest.series;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The prop-firm choices of a rule set — a daily loss that FAILS the entry,
 * a STATIC max loss from the starting capital, and passing the moment the
 * target is reached — on hourly candles you can follow on paper. 1,000 USDT
 * all in at 100 buys 10 coins, so every 1.00 of price is 1% of the account.
 */
@DisplayName("Prop-firm rules")
class PropFirmRulesTest {

    private static final String BUY_ALWAYS = "rule a { IF CLOSE > 0 THEN BUY ALL }";

    static RuleSet rules(double target, double maxDd, double dailyLoss, int minDays,
                         RuleSet.DailyLoss onDailyLoss, RuleSet.Drawdown drawdown,
                         boolean passOnTarget) {
        return new RuleSet(1000, 0, target, maxDd, dailyLoss, 0, minDays, onDailyLoss,
                drawdown, passOnTarget);
    }

    /** Candles from explicit open, low and close (high = the larger of open and close). */
    static CandleSeries candles(double[] open, double[] low, double[] close) {
        int n = close.length;
        long[] t = new long[n];
        double[] h = new double[n];
        double[] v = new double[n];
        for (int i = 0; i < n; i++) {
            t[i] = i * HOUR;
            h[i] = Math.max(open[i], close[i]);
            v[i] = 100;
        }
        return new CandleSeries(t, open, h, low, close, v);
    }

    static List<CompetitionEngine.Event> run(CompetitionEngine e, EntryState s, int from, int to) {
        List<CompetitionEngine.Event> all = new ArrayList<>();
        for (int i = from; i <= to; i++) {
            all.addAll(e.step(s, i));
        }
        return all;
    }

    @Test
    @DisplayName("the old constructor keeps the original rules: halt, trailing, judged at the end")
    void originalRulesByDefault() {
        RuleSet r = new RuleSet(1000, 0.001, 10, 10, 5, 0, 4);
        assertEquals(RuleSet.DailyLoss.HALT, r.dailyLoss());
        assertEquals(RuleSet.Drawdown.TRAILING, r.drawdown());
        assertFalse(r.passOnTarget());
    }

    @Nested
    @DisplayName("max drawdown: trailing or static")
    class Drawdown {

        // +8% to 108, then a candle down to 96.66: 10.5% below the best
        // equity, but only 3.3% below the starting capital.
        final double[] open = {100, 100, 100, 108};
        final double[] low = {100, 100, 100, 96.66};
        final double[] close = {100, 100, 108, 96.66};

        @Test
        @DisplayName("trailing: 10.5% below the best equity is out, though only 3.3% below the start")
        void trailingIsOut() {
            RuleSet r = rules(50, 10, 0, 0, RuleSet.DailyLoss.HALT, RuleSet.Drawdown.TRAILING, false);
            CompetitionEngine e = new CompetitionEngine(compile(BUY_ALWAYS),
                    candles(open, low, close), ExchangeRules.none(), r);
            EntryState s = EntryState.start(r);
            run(e, s, 0, 3);
            assertEquals(EntryState.Status.ELIMINATED, s.status);
            assertTrue(s.statusReason.contains("from its best"), s.statusReason);
            assertEquals(972, s.equity, 1e-9); // out at 97.2: 10% below 1,080
        }

        @Test
        @DisplayName("static: the same fall leaves it in — the floor is the starting capital minus 10%")
        void staticStaysIn() {
            RuleSet r = rules(50, 10, 0, 0, RuleSet.DailyLoss.HALT, RuleSet.Drawdown.STATIC, false);
            CompetitionEngine e = new CompetitionEngine(compile(BUY_ALWAYS),
                    candles(open, low, close), ExchangeRules.none(), r);
            EntryState s = EntryState.start(r);
            run(e, s, 0, 3);
            assertEquals(EntryState.Status.ACTIVE, s.status);
            assertEquals(3.34, s.drawdownPct(r), 1e-9); // 1,000 → 966.6
        }

        @Test
        @DisplayName("static: below the floor inside a candle is out at the floor")
        void staticFloor() {
            RuleSet r = rules(50, 10, 0, 0, RuleSet.DailyLoss.HALT, RuleSet.Drawdown.STATIC, false);
            CompetitionEngine e = new CompetitionEngine(compile(BUY_ALWAYS),
                    candles(new double[]{100, 100, 100, 95}, new double[]{100, 100, 95, 89},
                            new double[]{100, 100, 95, 92}), ExchangeRules.none(), r);
            EntryState s = EntryState.start(r);
            List<CompetitionEngine.Event> ev = run(e, s, 0, 3);
            assertEquals(EntryState.Status.ELIMINATED, s.status);
            assertTrue(s.statusReason.startsWith("Max loss breached during the candle"), s.statusReason);
            CompetitionEngine.Closed out = (CompetitionEngine.Closed) ev.get(ev.size() - 1);
            assertEquals(90, out.exitPrice(), 1e-9); // 900 USDT = 10 coins at 90
            assertEquals(900, s.equity, 1e-9);
        }
    }

    @Nested
    @DisplayName("daily loss limit: halt or fail")
    class DailyLoss {

        // Bought at 100; the price slides through the first day with no
        // other fill: down 4% at the close of hour 4, then a candle that
        // dips to 94 and closes there.
        final double[] open = {100, 100, 100, 99, 97, 96};
        final double[] low = {100, 100, 99, 97, 96, 94};
        final double[] close = {100, 100, 99, 97, 96, 94};

        @Test
        @DisplayName("fail: a position held 5% down inside the day is out at the limit, no fill needed")
        void failsWhileHolding() {
            RuleSet r = rules(50, 50, 5, 0, RuleSet.DailyLoss.FAIL, RuleSet.Drawdown.TRAILING, false);
            CompetitionEngine e = new CompetitionEngine(compile(BUY_ALWAYS),
                    candles(open, low, close), ExchangeRules.none(), r);
            EntryState s = EntryState.start(r);
            List<CompetitionEngine.Event> ev = run(e, s, 0, 5);
            assertEquals(EntryState.Status.ELIMINATED, s.status);
            assertTrue(s.statusReason.startsWith("Daily loss limit breached during the candle"),
                    s.statusReason);
            CompetitionEngine.Closed out = (CompetitionEngine.Closed) ev.get(ev.size() - 1);
            assertEquals("ELIMINATED", out.reason());
            assertEquals(95, out.exitPrice(), 1e-9); // 950 = 5% below the day's 1,000
            assertEquals(5 * HOUR, s.statusCandle);
            assertTrue(e.step(s, 5).isEmpty(), "out: skipped afterwards");
        }

        @Test
        @DisplayName("halt: the same day halts at the close it is 5% down, even without a fill")
        void haltsWhileHolding() {
            RuleSet r = rules(50, 50, 5, 0, RuleSet.DailyLoss.HALT, RuleSet.Drawdown.TRAILING, false);
            CompetitionEngine e = new CompetitionEngine(compile(BUY_ALWAYS),
                    candles(open, low, close), ExchangeRules.none(), r);
            EntryState s = EntryState.start(r);
            run(e, s, 0, 4);
            assertFalse(s.haltedToday, "down 4% at the close of hour 4: not yet");
            e.step(s, 5);
            assertTrue(s.haltedToday, "down 6% at the close of hour 5: halted");
            assertTrue(s.haltReason.startsWith("Daily loss limit hit"), s.haltReason);
            assertEquals(EntryState.Status.ACTIVE, s.status);
        }

        @Test
        @DisplayName("fail: a sell that gaps down past the limit at the open ends the entry right there")
        void failsOnAFill() {
            RuleSet r = rules(50, 50, 5, 0, RuleSet.DailyLoss.FAIL, RuleSet.Drawdown.TRAILING, false);
            String cut = """
                    rule a { IF CLOSE > 0 THEN BUY ALL }
                    rule b { IF CLOSE < 97 THEN SELL ALL }
                    """;
            // Close 96.5 at hour 2 (down 3.5%, sell signal); hour 3 opens at 93.
            CompetitionEngine e = new CompetitionEngine(compile(cut),
                    candles(new double[]{100, 100, 100, 93}, new double[]{100, 100, 96.5, 93},
                            new double[]{100, 100, 96.5, 95}), ExchangeRules.none(), r);
            EntryState s = EntryState.start(r);
            List<CompetitionEngine.Event> ev = run(e, s, 0, 3);
            assertEquals(EntryState.Status.ELIMINATED, s.status);
            assertTrue(s.statusReason.startsWith("Daily loss limit breached: down 7.00%"),
                    s.statusReason);
            assertEquals("SIGNAL", ((CompetitionEngine.Closed) ev.get(ev.size() - 1)).reason());
            assertFalse(s.inTrade());
            assertEquals(930, s.equity, 1e-9);
        }
    }

    @Nested
    @DisplayName("passing the moment the target is reached")
    class PassOnTarget {

        @Test
        @DisplayName("+11% at a close passes there: the position is sold and the result is locked in")
        void passesAtTheTarget() {
            RuleSet r = rules(10, 50, 0, 0, RuleSet.DailyLoss.HALT, RuleSet.Drawdown.TRAILING, true);
            CompetitionEngine e = new CompetitionEngine(compile(BUY_ALWAYS),
                    series(100, 100, 105, 111, 120, 90), ExchangeRules.none(), r);
            EntryState s = EntryState.start(r);
            List<CompetitionEngine.Event> ev = run(e, s, 0, 3);
            assertEquals(EntryState.Status.PASSED, s.status);
            assertTrue(s.statusReason.contains("locked in"), s.statusReason);
            assertEquals("TARGET_REACHED", ((CompetitionEngine.Closed) ev.get(ev.size() - 1)).reason());
            assertEquals(1110, s.equity, 1e-9);
            assertEquals(1, s.tradingDays);
            assertTrue(run(e, s, 4, 5).isEmpty(), "passed: nothing more happens");
            e.finish(s, 5);
            assertEquals(EntryState.Status.PASSED, s.status);
            assertEquals(1110, s.equity, 1e-9, "the later fall to 90 does not count");
        }

        @Test
        @DisplayName("judged at the end instead, the same run fails after giving it back")
        void judgedAtTheEnd() {
            RuleSet r = rules(10, 50, 0, 0, RuleSet.DailyLoss.HALT, RuleSet.Drawdown.TRAILING, false);
            CompetitionEngine e = new CompetitionEngine(compile(BUY_ALWAYS),
                    series(100, 100, 105, 111, 120, 90), ExchangeRules.none(), r);
            EntryState s = EntryState.start(r);
            run(e, s, 0, 5);
            e.finish(s, 5);
            assertEquals(EntryState.Status.FAILED, s.status);
        }

        @Test
        @DisplayName("the target alone is not enough: it still needs the minimum trading days")
        void needsTheDays() {
            RuleSet r = rules(10, 50, 0, 2, RuleSet.DailyLoss.HALT, RuleSet.Drawdown.TRAILING, true);
            CompetitionEngine e = new CompetitionEngine(compile(BUY_ALWAYS),
                    series(100, 100, 105, 111, 120), ExchangeRules.none(), r);
            EntryState s = EntryState.start(r);
            run(e, s, 0, 4);
            assertEquals(EntryState.Status.ACTIVE, s.status, "+20%, but on one trading day of two");
            e.finish(s, 4);
            assertEquals(EntryState.Status.FAILED, s.status);
            assertTrue(s.statusReason.contains("trading days"), s.statusReason);
        }

        @Test
        @DisplayName("the exit fee counts: a target met only before the fee does not pass")
        void afterTheFee() {
            RuleSet r = new RuleSet(1000, 0.001, 10, 50, 0, 0, 0, RuleSet.DailyLoss.HALT,
                    RuleSet.Drawdown.TRAILING, true);
            // Bought at 100 with the fee: 9.99 coins. At 110.12 they are worth
            // 1,100.10 — 10.01% — but selling costs 1.10, leaving 9.90%.
            CompetitionEngine e = new CompetitionEngine(compile(BUY_ALWAYS),
                    series(100, 100, 110.12), ExchangeRules.none(), r);
            EntryState s = EntryState.start(r);
            run(e, s, 0, 2);
            assertEquals(EntryState.Status.ACTIVE, s.status);
        }
    }
}
