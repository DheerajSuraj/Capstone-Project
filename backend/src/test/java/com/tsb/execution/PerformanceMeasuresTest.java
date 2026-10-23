package com.tsb.execution;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** TradAO's measures, on equity curves small enough to check by hand. */
@DisplayName("PerformanceMeasures")
class PerformanceMeasuresTest {

    static final long DAY = PerformanceMeasures.DAY_MS;

    static long[] days(int n) {
        long[] t = new long[n];
        for (int i = 0; i < n; i++) {
            t[i] = i * DAY;
        }
        return t;
    }

    static Trade trade(int in, int out) {
        return new Trade(in, out, Instant.ofEpochMilli(in * DAY), Instant.ofEpochMilli(out * DAY),
                1, 100, 100, 0, 0, Trade.ExitReason.SIGNAL);
    }

    @Test
    @DisplayName("drawdown lengths: from the peak until equity is back at it, or the end")
    void drawdownDurations() {
        // Peak 110 on day 1, back at 110 on day 3: 2 days.
        // Peak 120 on day 4, never back by the last day (7): 4 days to the
        // end of that last daily candle.
        double[] eq = {100, 110, 100, 110, 120, 90, 95, 100};
        PerformanceMeasures m = PerformanceMeasures.compute(days(8), eq, new double[8], 0,
                List.of(), DAY);
        assertEquals(4, m.maxDrawdownDays(), 1e-9);
        assertEquals(3, m.avgDrawdownDays(), 1e-9);
        assertEquals(25, m.maxDrawdownPct(), 1e-9); // 120 -> 90
    }

    @Test
    @DisplayName("yield, volatility and VaR come from daily returns")
    void dailyReturns() {
        double[] eq = {100, 110, 99, 99, 108.9};
        PerformanceMeasures m = PerformanceMeasures.compute(days(5), eq, new double[5], 0,
                List.of(), DAY);
        // Day returns: 0, +10%, -10%, 0, +10%.
        double[] r = {0, 0.10, -0.10, 0, 0.10};
        double mean = (0 + 0.10 - 0.10 + 0 + 0.10) / 5;
        double ss = 0;
        for (double x : r) {
            ss += (x - mean) * (x - mean);
        }
        assertEquals(5, m.days());
        assertEquals(mean * 365 * 100, m.yieldPct(), 1e-6);
        assertEquals(Math.sqrt(ss / 4) * Math.sqrt(365) * 100, m.volatilityPct(), 1e-6);
        assertEquals(10, m.var99Pct(), 1e-6); // fewer than 100 days: the worst day
    }

    @Test
    @DisplayName("hourly candles are measured by UTC day, not by candle")
    void hourlyToDaily() {
        int n = 48;
        long[] t = new long[n];
        double[] eq = new double[n];
        for (int i = 0; i < n; i++) {
            t[i] = i * 3_600_000L;
            eq[i] = i < 24 ? 100 + (i % 2) : 110; // wiggles all day 1, then 110
        }
        PerformanceMeasures m = PerformanceMeasures.compute(t, eq, new double[n], 0,
                List.of(), 3_600_000L);
        assertEquals(2, m.days());
        // Day 1 ends at 101 (from 100), day 2 at 110.
        assertEquals(((101.0 / 100 - 1) + (110.0 / 101 - 1)) / 2 * 365 * 100, m.yieldPct(), 1e-6);
    }

    @Test
    @DisplayName("activeness: trades per 30 days and time holding a position")
    void activeness() {
        double[] held = new double[60];
        for (int i = 10; i < 25; i++) {
            held[i] = 1;
        }
        double[] eq = new double[60];
        java.util.Arrays.fill(eq, 100);
        PerformanceMeasures m = PerformanceMeasures.compute(days(60), eq, held, 0,
                List.of(trade(10, 15), trade(16, 25)), DAY);
        assertEquals(1.0, m.tradesPer30Days(), 1e-9); // 2 trades in 60 days
        assertEquals(15 * 100.0 / 60, m.timeInMarketPct(), 1e-9);
        assertEquals(0, m.var99Pct(), 0.0);
        assertTrue(1 / m.var99Pct() > 0, "no -0.0 when nothing was lost");
    }

    @Test
    @DisplayName("the warm-up is not measured")
    void skipsWarmup() {
        // The engine keeps the warm-up at the starting capital.
        double[] eq = {100, 100, 100, 100, 80, 88};
        PerformanceMeasures m = PerformanceMeasures.compute(days(6), eq, new double[6], 4,
                List.of(), DAY);
        assertEquals(2, m.days());
        assertEquals((-0.2 + 0.1) / 2 * 365 * 100, m.yieldPct(), 1e-6);
    }
}
