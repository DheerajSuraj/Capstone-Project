package com.tsb.competition;

import com.tsb.compiler.Analyzer;
import com.tsb.compiler.CompiledStrategy;
import com.tsb.compiler.Lexer;
import com.tsb.compiler.Parser;
import com.tsb.execution.BacktestResult;
import com.tsb.execution.Backtester;
import com.tsb.execution.BarObserver;
import com.tsb.execution.ExchangeRules;
import com.tsb.execution.Trade;
import com.tsb.marketdata.CandleSeries;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The competition engine, one rule at a time, on hourly candles small
 * enough to follow on paper (24 candles = one UTC day). Fees are 0 unless a
 * test is about them.
 */
@DisplayName("CompetitionEngine")
class CompetitionEngineTest {

    static final long HOUR = 3_600_000L;

    static CompiledStrategy compile(String rules) {
        String source = """
                strategy "T" {
                    symbol = BTCUSDT
                    timeframe = 1h
                    capital = 1000
                    %s
                }
                """.formatted(rules);
        Lexer.LexResult lexed = new Lexer(source).scan();
        Parser.ParseResult parsed = new Parser(lexed.tokens()).parse();
        Analyzer.AnalysisResult analyzed = new Analyzer(parsed.strategy().orElseThrow()).analyze();
        assertEquals(List.of(), analyzed.diagnostics(), "fixture must compile");
        return analyzed.strategy().orElseThrow();
    }

    /** open[i] = close[i-1]; high/low hug the bar, so no stray stops. */
    static CandleSeries series(double... close) {
        int n = close.length;
        long[] t = new long[n];
        double[] o = new double[n];
        double[] h = new double[n];
        double[] l = new double[n];
        double[] v = new double[n];
        for (int i = 0; i < n; i++) {
            t[i] = i * HOUR;
            o[i] = i == 0 ? close[0] : close[i - 1];
            h[i] = Math.max(o[i], close[i]);
            l[i] = Math.min(o[i], close[i]);
            v[i] = 100;
        }
        return new CandleSeries(t, o, h, l, close, v);
    }

    static double[] repeat(double value, int count) {
        double[] a = new double[count];
        java.util.Arrays.fill(a, value);
        return a;
    }

    static double[] concat(double[]... parts) {
        int n = 0;
        for (double[] p : parts) {
            n += p.length;
        }
        double[] out = new double[n];
        int k = 0;
        for (double[] p : parts) {
            System.arraycopy(p, 0, out, k, p.length);
            k += p.length;
        }
        return out;
    }

    static RuleSet rules(double target, double maxDd, double dailyLoss, int maxTrades, int minDays) {
        return new RuleSet(1000, 0, target, maxDd, dailyLoss, maxTrades, minDays);
    }

    /** Steps every candle, collecting the events. */
    static List<CompetitionEngine.Event> runAll(CompetitionEngine e, EntryState s, int n) {
        List<CompetitionEngine.Event> all = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            all.addAll(e.step(s, i));
        }
        return all;
    }

    private static final String BUY_ALWAYS = "rule a { IF CLOSE > 0 THEN BUY ALL }";
    private static final String BUY_AND_CUT = """
            rule a { IF CLOSE > 0 THEN BUY ALL }
            rule b { IF CLOSE < 95 THEN SELL ALL }
            """;

    @Nested
    @DisplayName("same fills as the backtester")
    class Equivalence {

        @Test
        @DisplayName("with every limit off, it makes exactly the backtester's trades")
        void matchesBacktester() {
            CompiledStrategy s = compile("""
                    rule a { IF CLOSE > SMA(CLOSE, 10) THEN BUY ALL ELSE BUY qty = 10% OF EQUITY }
                    rule b { IF RSI(14) > 60 THEN SELL qty = 50% OF POSITION ELSE SET TAKEPROFIT = 4% }
                    rule c { IF CROSSUNDER(CLOSE, SMA(CLOSE, 20)) THEN SELL ALL }
                    rule d { IF CLOSE > 0 THEN SET STOPLOSS = 3% }
                    """);
            for (long seed = 1; seed <= 5; seed++) {
                Random r = new Random(seed);
                double[] close = new double[1500];
                double px = 100;
                for (int i = 0; i < close.length; i++) {
                    px *= Math.exp(r.nextGaussian() * 0.02);
                    close[i] = px;
                }
                CandleSeries c = series(close);
                ExchangeRules ex = new ExchangeRules(0.001, 10);
                RuleSet unlimited = new RuleSet(1000, 0.001, 1e9, 100, 0, 0, 0);
                CompetitionEngine engine = new CompetitionEngine(s, c, ex, unlimited);
                int start = 200;
                BacktestResult bt = new Backtester().run(engine.strategy(), c, ex,
                        BarObserver.NONE, start);

                EntryState st = EntryState.start(unlimited);
                List<CompetitionEngine.Closed> closed = new ArrayList<>();
                for (int i = start; i < c.size(); i++) {
                    engine.step(st, i).forEach(e -> {
                        if (e instanceof CompetitionEngine.Closed x) {
                            closed.add(x);
                        }
                    });
                }
                engine.finish(st, c.size() - 1).forEach(e -> closed.add((CompetitionEngine.Closed) e));

                assertEquals(bt.trades().size(), closed.size(), "seed " + seed);
                for (int k = 0; k < closed.size(); k++) {
                    Trade want = bt.trades().get(k);
                    CompetitionEngine.Closed got = closed.get(k);
                    assertEquals(want.entryTime().toEpochMilli(), got.entryTime());
                    assertEquals(want.exitTime().toEpochMilli(), got.exitTime());
                    assertEquals(want.qty(), got.qty(), 1e-12);
                    assertEquals(want.exitPrice(), got.exitPrice(), 1e-12);
                    assertEquals(want.pnl(), got.pnl(), 1e-9);
                }
                assertEquals(bt.finalEquity(), st.equity, 1e-6);
            }
        }

        @Test
        @DisplayName("a signal at a close fills at the NEXT candle's open")
        void nextOpen() {
            CompetitionEngine e = new CompetitionEngine(compile(BUY_ALWAYS),
                    series(100, 110, 120), ExchangeRules.none(), rules(5, 50, 0, 0, 0));
            EntryState s = EntryState.start(rules(5, 50, 0, 0, 0));
            assertTrue(e.step(s, 0).isEmpty(), "signal only — nothing fills at this close");
            List<CompetitionEngine.Event> ev = e.step(s, 1);
            CompetitionEngine.Opened o = (CompetitionEngine.Opened) ev.get(0);
            assertEquals(100, o.price(), 1e-12); // bar 1 opens at bar 0's close
            assertEquals(HOUR, o.time());
        }
    }

    @Nested
    @DisplayName("rules")
    class Rules {

        @Test
        @DisplayName("max drawdown breached: eliminated, position closed, skipped afterwards")
        void eliminated() {
            RuleSet r = rules(5, 10, 0, 0, 0);
            CompetitionEngine e = new CompetitionEngine(compile(BUY_ALWAYS),
                    series(100, 100, 100, 80, 80, 120), ExchangeRules.none(), r);
            EntryState s = EntryState.start(r);
            e.step(s, 0);
            e.step(s, 1); // bought at 100
            e.step(s, 2);
            List<CompetitionEngine.Event> ev = e.step(s, 3); // close 80: down 20%
            assertEquals(EntryState.Status.ELIMINATED, s.status);
            assertEquals(3 * HOUR, s.statusCandle);
            assertTrue(s.statusReason.contains("Max drawdown"));
            assertEquals("ELIMINATED", ((CompetitionEngine.Closed) ev.get(0)).reason());
            assertFalse(s.inTrade());
            double frozen = s.equity;

            assertTrue(e.step(s, 4).isEmpty(), "eliminated earlier: skip this entry");
            assertTrue(e.step(s, 5).isEmpty());
            assertEquals(frozen, s.equity);
            e.finish(s, 5);
            assertEquals(EntryState.Status.ELIMINATED, s.status, "still eliminated at the end");
        }

        @Test
        @DisplayName("daily loss limit: halted for the rest of the day, free again tomorrow")
        void dailyLossHalt() {
            RuleSet r = rules(5, 50, 5, 0, 0);
            // Day 0: buy at 100, sell at 90 (−10%) → halted. Day 1 starts at bar 24.
            double[] closes = concat(new double[]{100, 100, 90, 90}, repeat(100, 26));
            CompetitionEngine e = new CompetitionEngine(compile(BUY_AND_CUT),
                    series(closes), ExchangeRules.none(), r);
            EntryState s = EntryState.start(r);
            List<CompetitionEngine.Event> ev = new ArrayList<>();
            for (int i = 0; i <= 3; i++) {
                ev.addAll(e.step(s, i));
            }
            assertTrue(s.haltedToday);
            assertTrue(s.haltReason.contains("Daily loss"));
            assertEquals(2, ev.size()); // opened, closed

            for (int i = 4; i < 24; i++) {
                assertTrue(e.step(s, i).isEmpty(), "halted: cannot enter at bar " + i);
            }
            e.step(s, 24); // new day: halt cleared, buy signal at this close
            assertFalse(s.haltedToday);
            assertEquals(1, s.tradingDays, "day 0 had trades");
            CompetitionEngine.Opened o = (CompetitionEngine.Opened) e.step(s, 25).get(0);
            assertEquals(25 * HOUR, o.time());
        }

        @Test
        @DisplayName("max trades per day: opening and closing each count once")
        void maxTradesHalt() {
            RuleSet r = rules(5, 50, 0, 2, 0);
            double[] closes = concat(new double[]{100, 100, 90, 90}, repeat(100, 10));
            CompetitionEngine e = new CompetitionEngine(compile(BUY_AND_CUT),
                    series(closes), ExchangeRules.none(), r);
            EntryState s = EntryState.start(r);
            for (int i = 0; i <= 3; i++) {
                e.step(s, i);
            }
            assertEquals(2, s.tradesToday);
            assertTrue(s.haltedToday);
            assertTrue(s.haltReason.contains("Max trades"));
            for (int i = 4; i < 14; i++) {
                assertTrue(e.step(s, i).isEmpty());
            }
        }

        @Test
        @DisplayName("passed: target hit and enough trading days")
        void passed() {
            RuleSet r = rules(10, 50, 0, 0, 1);
            CompetitionEngine e = new CompetitionEngine(compile(BUY_ALWAYS),
                    series(100, 100, 110, 120), ExchangeRules.none(), r);
            EntryState s = EntryState.start(r);
            runAll(e, s, 4);
            List<CompetitionEngine.Event> end = e.finish(s, 3);
            assertEquals("END_OF_COMPETITION", ((CompetitionEngine.Closed) end.get(0)).reason());
            assertEquals(EntryState.Status.PASSED, s.status);
            assertEquals(20, s.returnPct(r), 1e-9);
            assertEquals(1, s.tradingDays);
        }

        @Test
        @DisplayName("failed: target not met")
        void failedTarget() {
            RuleSet r = rules(50, 50, 0, 0, 0);
            CompetitionEngine e = new CompetitionEngine(compile(BUY_ALWAYS),
                    series(100, 100, 110, 120), ExchangeRules.none(), r);
            EntryState s = EntryState.start(r);
            runAll(e, s, 4);
            e.finish(s, 3);
            assertEquals(EntryState.Status.FAILED, s.status);
            assertTrue(s.statusReason.contains("Profit target"));
        }

        @Test
        @DisplayName("failed: target met but not enough trading days")
        void failedDays() {
            RuleSet r = rules(10, 50, 0, 0, 3);
            CompetitionEngine e = new CompetitionEngine(compile(BUY_ALWAYS),
                    series(100, 100, 110, 120), ExchangeRules.none(), r);
            EntryState s = EntryState.start(r);
            runAll(e, s, 4);
            e.finish(s, 3);
            assertEquals(EntryState.Status.FAILED, s.status);
            assertTrue(s.statusReason.contains("trading days"));
        }

        @Test
        @DisplayName("every entry plays with the competition's capital, not its own")
        void competitionMoney() {
            RuleSet r = new RuleSet(5000, 0.001, 10, 50, 0, 0, 0);
            CompetitionEngine e = new CompetitionEngine(compile(BUY_ALWAYS),
                    series(100, 100), ExchangeRules.none(), r);
            assertEquals(5000, e.strategy().capital());
            assertEquals(0.001, e.strategy().feePercent());
        }
    }

    @Test
    @DisplayName("pending orders survive a save and load")
    void pendingRoundTrip() {
        EntryState s = new EntryState();
        s.pending.add(new EntryState.Pending(true, 0, 1, false));
        s.pending.add(new EntryState.Pending(false, 2, 0, true));
        assertEquals(s.pending, EntryState.parsePending(s.pendingText()));
        assertEquals(List.of(), EntryState.parsePending(""));
    }
}
