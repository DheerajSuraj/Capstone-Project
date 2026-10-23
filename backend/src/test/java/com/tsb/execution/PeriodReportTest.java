package com.tsb.execution;

import com.tsb.compiler.CompiledStrategy;
import com.tsb.marketdata.CandleSeries;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The run by period, on equity curves small enough to check by hand. */
@DisplayName("PeriodReport")
class PeriodReportTest {

    static final long H = 3_600_000L;
    static final long D = 86_400_000L;

    static long at(String instant) {
        return Instant.parse(instant).toEpochMilli();
    }

    static long[] hourly(String first, int n) {
        long[] t = new long[n];
        long start = at(first);
        for (int i = 0; i < n; i++) {
            t[i] = start + i * H;
        }
        return t;
    }

    static PeriodReport report(long[] t, double[] eq, double[] held, int firstBar,
                               List<Trade> trades, double[] hold, long barMs) {
        return PeriodReport.build(t, eq, held, firstBar, trades, 0.001, hold, barMs, 100);
    }

    /** A seeded random walk over n hourly candles, in and out of a position. */
    static double[][] walk(long seed, int n) {
        Random r = new Random(seed);
        double[] eq = new double[n];
        double[] held = new double[n];
        double e = 100;
        boolean in = false;
        for (int i = 0; i < n; i++) {
            if (r.nextInt(12) == 0) {
                in = !in;
            }
            if (in) {
                e *= 1 + r.nextGaussian() * 0.01;
            }
            eq[i] = e;
            held[i] = in ? 1 : 0;
        }
        return new double[][]{eq, held};
    }

    static List<PeriodReport.Node> all(PeriodReport.Node n) {
        List<PeriodReport.Node> out = new ArrayList<>();
        out.add(n);
        for (PeriodReport.Node c : n.children()) {
            out.addAll(all(c));
        }
        return out;
    }

    static PeriodReport.Node find(PeriodReport.Node n, PeriodReport.Level level, String key) {
        for (PeriodReport.Node x : all(n)) {
            if (x.level() == level && x.key().equals(key)) {
                return x;
            }
        }
        throw new AssertionError("no " + level + " " + key);
    }

    @Nested
    @DisplayName("one day")
    class OneDay {

        @Test
        @DisplayName("runs from the close before it to its last close, open positions included")
        void fromCloseToClose() {
            // Day 1: 100 -> dips to 90 -> ends at 110. Day 2: ends at 99.
            long[] t = hourly("2025-03-03T00:00:00Z", 48);
            double[] eq = new double[48];
            double[] held = new double[48];
            for (int i = 0; i < 48; i++) {
                eq[i] = i < 10 ? 100 : i < 12 ? 90 : i < 24 ? 110 : 99;
                held[i] = i >= 5 && i < 30 ? 1 : 0;
            }
            PeriodReport r = report(t, eq, held, 0, List.of(), null, H);
            PeriodReport.Node d1 = find(r.all(), PeriodReport.Level.DAY, "2025-03-03");
            PeriodReport.Node d2 = find(r.all(), PeriodReport.Level.DAY, "2025-03-04");

            assertEquals(100, d1.stats().startEquity(), 1e-12); // the capital
            assertEquals(10, d1.stats().returnPct(), 1e-9);
            assertEquals(10, d1.stats().pnl(), 1e-9);
            assertEquals(10, d1.stats().maxDrawdownPct(), 1e-9); // 100 -> 90
            assertEquals(19 * 100.0 / 24, d1.stats().timeInMarketPct(), 1e-9);
            assertEquals(110, d2.stats().startEquity(), 1e-12);
            assertEquals(-10, d2.stats().returnPct(), 1e-9);
            assertEquals(24, d2.stats().candles());

            assertEquals(-1, r.all().stats().returnPct(), 1e-9);
            assertEquals(1, r.all().stats().greenDays());
            assertEquals(1, r.all().stats().redDays());
            assertEquals(t[0], r.all().start());
            assertEquals(t[47] + H, r.all().end());
        }

        @Test
        @DisplayName("a fall on the first candle is a drawdown: the high starts at the period's start")
        void drawdownFromTheStart() {
            long[] t = hourly("2025-03-03T00:00:00Z", 24);
            double[] eq = new double[24];
            java.util.Arrays.fill(eq, 80);
            eq[23] = 100;
            PeriodReport r = report(t, eq, new double[24], 0, List.of(), null, H);
            assertEquals(20, r.all().stats().maxDrawdownPct(), 1e-9);
            assertEquals(0, r.all().stats().returnPct(), 1e-9);
        }
    }

    @Nested
    @DisplayName("periods")
    class Periods {

        @Test
        @DisplayName("chain exactly: days compound to weeks, weeks to months, months to years")
        void chain() {
            int n = 24 * 100; // 2024-12-15 .. 2025-03-24: a year end and three month ends
            long[] t = hourly("2024-12-15T00:00:00Z", n);
            double[][] w = walk(7, n);
            PeriodReport r = report(t, w[0], w[1], 0, List.of(), null, H);
            for (PeriodReport.Node p : all(r.all())) {
                if (p.children().isEmpty()) {
                    continue;
                }
                double growth = 1;
                double pnl = 0;
                int candles = 0;
                int days = 0;
                for (PeriodReport.Node c : p.children()) {
                    growth *= 1 + c.stats().returnPct() / 100;
                    pnl += c.stats().pnl();
                    candles += c.stats().candles();
                    days += c.stats().days();
                }
                String where = p.level() + " " + p.key();
                assertEquals(p.stats().returnPct(), (growth - 1) * 100, 1e-9, where);
                assertEquals(p.stats().pnl(), pnl, 1e-9, where);
                assertEquals(p.stats().candles(), candles, where);
                assertEquals(p.stats().days(), days, where);
                assertEquals(p.stats().startEquity(),
                        p.children().get(0).stats().startEquity(), 0, where);
                assertEquals(p.stats().endEquity(),
                        p.children().get(p.children().size() - 1).stats().endEquity(), 0, where);
            }
            assertEquals(2, r.all().children().size());
            assertEquals("2024", r.all().children().get(0).key());
            assertEquals(List.of("2025-01", "2025-02", "2025-03"),
                    r.all().children().get(1).children().stream()
                            .map(PeriodReport.Node::key).toList());
            assertEquals(100, r.all().stats().days());
        }

        @Test
        @DisplayName("weeks start on Monday and stop at the end of the month")
        void weeks() {
            // Monday 27 Jan .. Sunday 9 Feb 2025.
            long[] t = hourly("2025-01-27T00:00:00Z", 24 * 14);
            double[][] w = walk(3, t.length);
            PeriodReport r = report(t, w[0], w[1], 0, List.of(), null, H);
            PeriodReport.Node jan = find(r.all(), PeriodReport.Level.MONTH, "2025-01");
            PeriodReport.Node feb = find(r.all(), PeriodReport.Level.MONTH, "2025-02");

            assertEquals(1, jan.children().size());
            PeriodReport.Node lastJan = jan.children().get(0);
            assertEquals("2025-01-27", lastJan.firstDay());
            assertEquals("2025-01-31", lastJan.lastDay()); // cut at the month's end
            assertEquals(5, lastJan.stats().days());

            assertEquals(List.of("2025-02-01", "2025-02-03"),
                    feb.children().stream().map(PeriodReport.Node::key).toList());
            assertEquals(2, feb.children().get(0).stats().days()); // Sat 1, Sun 2
            assertEquals(7, feb.children().get(1).stats().days()); // Mon 3 .. Sun 9
            assertEquals(7, feb.children().get(1).children().size());
        }

        @Test
        @DisplayName("the warm-up is not part of it: the first period starts after it, at the capital")
        void afterWarmup() {
            long[] t = hourly("2025-03-03T00:00:00Z", 72);
            double[] eq = new double[72];
            java.util.Arrays.fill(eq, 100);
            for (int i = 30; i < 72; i++) {
                eq[i] = 100 + (i - 29);
            }
            PeriodReport r = report(t, eq, new double[72], 30, List.of(), null, H);
            assertEquals("2025-03-04", r.all().firstDay()); // candle 30 is on the second day
            assertEquals(42, r.all().stats().candles());
            assertEquals(2, r.all().stats().days());
            assertEquals(100, r.all().stats().startEquity(), 0);
            assertEquals(t[30], r.all().start());
        }

        @Test
        @DisplayName("buy & hold over the same candles as each period")
        void buyHold() {
            long[] t = hourly("2025-03-03T00:00:00Z", 48);
            double[] hold = new double[48];
            for (int i = 0; i < 48; i++) {
                hold[i] = i < 24 ? 100 + i : 200;
            }
            PeriodReport r = report(t, new double[48], new double[48], 0, List.of(), hold, H);
            PeriodReport.Node d1 = find(r.all(), PeriodReport.Level.DAY, "2025-03-03");
            PeriodReport.Node d2 = find(r.all(), PeriodReport.Level.DAY, "2025-03-04");
            assertEquals((123.0 / 100 - 1) * 100, d1.stats().buyHoldPct(), 1e-9);
            assertEquals((200.0 / 123 - 1) * 100, d2.stats().buyHoldPct(), 1e-9);
            assertEquals(100, r.all().stats().buyHoldPct(), 1e-9);
            assertNull(report(t, new double[48], new double[48], 0, List.of(), null, H)
                    .all().stats().buyHoldPct());
        }

        @Test
        @DisplayName("no candle after the warm-up: no report")
        void empty() {
            long[] t = hourly("2025-03-03T00:00:00Z", 10);
            assertNull(report(t, new double[10], new double[10], 10, List.of(), null, H));
        }
    }

    @Nested
    @DisplayName("trades")
    class Trades {

        @Test
        @DisplayName("count on the day they close; each fill's fee on its own day")
        void byDay() {
            long[] t = hourly("2025-03-03T00:00:00Z", 48);
            double[] eq = new double[48];
            java.util.Arrays.fill(eq, 100);
            // Bought 1 at 50 on day 1 (fee 0.05), sold at 60 on day 2 (fee 0.06).
            Trade win = Sizing.trade(5, 30, t[5], t[30], 1, 50, 60, 0.05, 0.001,
                    Trade.ExitReason.SIGNAL);
            // Bought and sold on day 2, at a loss.
            Trade loss = Sizing.trade(35, 40, t[35], t[40], 1, 60, 55, 0.06, 0.001,
                    Trade.ExitReason.STOPLOSS);
            PeriodReport r = report(t, eq, new double[48], 0, List.of(win, loss), null, H);
            PeriodReport.Stats d1 = find(r.all(), PeriodReport.Level.DAY, "2025-03-03").stats();
            PeriodReport.Stats d2 = find(r.all(), PeriodReport.Level.DAY, "2025-03-04").stats();

            assertEquals(1, d1.tradesOpened());
            assertEquals(0, d1.tradesClosed());
            assertNull(d1.winRatePct());
            assertEquals(0.05, d1.fees(), 1e-12);

            assertEquals(1, d2.tradesOpened());
            assertEquals(2, d2.tradesClosed());
            assertEquals(1, d2.wins());
            assertEquals(50, d2.winRatePct(), 1e-12);
            assertEquals(win.pnl() + loss.pnl(), d2.realizedPnl(), 1e-12);
            assertEquals(0.06 + 0.06 + 0.055, d2.fees(), 1e-12);

            PeriodReport.Stats all = r.all().stats();
            assertEquals(win.fees() + loss.fees(), all.fees(), 1e-12);
            assertEquals(2, all.tradesClosed());
        }
    }

    @Nested
    @DisplayName("when the money was made")
    class When {

        @Test
        @DisplayName("by weekday and by hour, each adds up to the period")
        void addsUp() {
            int n = 24 * 60;
            long[] t = hourly("2025-01-01T00:00:00Z", n);
            double[][] w = walk(11, n);
            List<Trade> trades = new ArrayList<>();
            for (int i = 10; i + 5 < n; i += 37) {
                trades.add(Sizing.trade(i, i + 5, t[i], t[i + 5], 1, 100, 100 + (i % 3) - 1,
                        0.1, 0.001, Trade.ExitReason.SIGNAL));
            }
            PeriodReport r = report(t, w[0], w[1], 0, trades, null, H);
            for (PeriodReport.Node p : all(r.all())) {
                if (p.breakdown() == null) {
                    continue;
                }
                String where = p.level() + " " + p.key();
                double weekdayPnl = 0;
                int weekdayDays = 0;
                int weekdayTrades = 0;
                for (PeriodReport.WeekdayRow row : p.breakdown().weekdays()) {
                    weekdayPnl += row.pnl();
                    weekdayDays += row.days();
                    weekdayTrades += row.tradesEntered();
                }
                double hourPnl = 0;
                int slots = 0;
                int hourTrades = 0;
                for (PeriodReport.HourRow row : p.breakdown().hours()) {
                    hourPnl += row.pnl();
                    slots += row.slots();
                    hourTrades += row.tradesEntered();
                }
                assertEquals(7, p.breakdown().weekdays().size(), where);
                assertEquals(24, p.breakdown().hours().size(), where);
                assertEquals(p.stats().pnl(), weekdayPnl, 1e-9, where);
                assertEquals(p.stats().pnl(), hourPnl, 1e-9, where);
                assertEquals(p.stats().days(), weekdayDays, where);
                assertEquals(p.stats().candles(), slots, where); // one-hour candles
                assertEquals(p.stats().tradesOpened(), weekdayTrades, where);
                assertEquals(p.stats().tradesOpened(), hourTrades, where);
            }
            assertNotNull(r.all().breakdown().monthsOfYear());
            assertNull(r.all().children().get(0).breakdown().monthsOfYear());
        }

        @Test
        @DisplayName("the mean return of each weekday, Monday first")
        void weekdayMeans() {
            // Monday 3 March 2025: +10%; Tuesday: -10%; the rest of the week flat.
            long[] t = new long[7];
            double[] eq = new double[7];
            for (int d = 0; d < 7; d++) {
                t[d] = at("2025-03-03T00:00:00Z") + d * D;
            }
            eq[0] = 110;
            for (int d = 1; d < 7; d++) {
                eq[d] = 99;
            }
            PeriodReport r = report(t, eq, new double[7], 0, List.of(), null, D);
            List<PeriodReport.WeekdayRow> rows = r.all().breakdown().weekdays();
            assertEquals(1, rows.get(0).weekday());
            assertEquals(10, rows.get(0).avgReturnPct(), 1e-9);
            assertEquals(1, rows.get(0).greenDays());
            assertEquals(-10, rows.get(1).avgReturnPct(), 1e-9);
            assertEquals(0, rows.get(6).avgReturnPct(), 1e-9);
            assertEquals(1, rows.get(6).days());
            assertNull(r.all().breakdown().hours()); // daily candles: no hour to tell
        }

        @Test
        @DisplayName("every January, every February … across the years")
        void monthsOfYear() {
            int days = 400; // 2024-01-01 .. 2025-02-03
            long[] t = new long[days];
            double[] eq = new double[days];
            for (int d = 0; d < days; d++) {
                t[d] = at("2024-01-01T00:00:00Z") + d * D;
                eq[d] = 100 + d;
            }
            PeriodReport r = report(t, eq, new double[days], 0, List.of(), null, D);
            List<PeriodReport.MonthRow> rows = r.all().breakdown().monthsOfYear();
            assertEquals(12, rows.size());
            assertEquals(2, rows.get(0).months()); // January 2024 and 2025
            assertEquals(2, rows.get(1).months()); // February 2024 and the start of 2025's
            assertEquals(1, rows.get(2).months());
            double jan24 = find(r.all(), PeriodReport.Level.MONTH, "2024-01").stats().returnPct();
            double jan25 = find(r.all(), PeriodReport.Level.MONTH, "2025-01").stats().returnPct();
            assertEquals((jan24 + jan25) / 2, rows.get(0).avgReturnPct(), 1e-9);
        }
    }

    @Test
    @DisplayName("the whole run agrees with the backtest's own numbers")
    void agreesWithTheBacktest() {
        CandleSeries s = LookAheadTest.candles(21, 24 * 75);
        CompiledStrategy st = LookAheadTest.compile("""
                let rsi = RSI(14)
                rule entry { IF rsi < 40 THEN BUY ALL }
                rule exit { IF rsi > 60 THEN SELL ALL }
                rule protect { IF CLOSE > 0 THEN SET STOPLOSS = 3% }
                """);
        BacktestResult res = new Backtester().run(st, s, ExchangeRules.none());
        assertTrue(res.trades().size() > 5, "fixture should trade");
        PeriodReport r = PeriodReport.build(s.openTimeMillis(), res.equityCurve(),
                res.positionCurve(), res.warmupBars(), res.trades(), st.feePercent(),
                Benchmark.curve(s, res.warmupBars(), res.initialCapital(), st.feePercent()),
                H, res.initialCapital());
        PeriodReport.Stats all = r.all().stats();
        assertEquals(res.totalReturnPct(), all.returnPct(), 1e-9);
        assertEquals(res.totalFees(), all.fees(), 1e-9);
        assertEquals(res.trades().size(), all.tradesClosed());
        assertEquals(res.trades().size(), all.tradesOpened());
        assertEquals(res.winRate(), all.winRatePct(), 1e-9);
        assertEquals(res.maxDrawdownPct(), all.maxDrawdownPct(), 1e-9);
        assertEquals(Benchmark.returnPct(s, res.warmupBars(), st.feePercent()),
                all.buyHoldPct(), 1e-9);
        double realized = res.trades().stream().mapToDouble(Trade::pnl).sum();
        assertEquals(realized, all.realizedPnl(), 1e-9);
    }
}
