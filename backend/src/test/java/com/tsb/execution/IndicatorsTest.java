package com.tsb.execution;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every expected value in this file was computed BY HAND first (the
 * arithmetic is in the comments) — because an indicator that is slightly
 * wrong everywhere invalidates every backtest silently. These tests are the
 * platform's ground truth; the ta4j cross-check (test scope) can be layered
 * on later as a second opinion.
 */
@DisplayName("Indicators")
class IndicatorsTest {

    private static final double EPS = 1e-9;

    /** Index of the first non-NaN value — the indicator's true warm-up. */
    private static int firstValid(double[] a) {
        for (int i = 0; i < a.length; i++) {
            if (!Double.isNaN(a[i])) {
                return i;
            }
        }
        return -1;
    }

    // ── SMA ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("SMA")
    class Sma {

        @Test
        @DisplayName("hand-computed: SMA(3) over 1..5 is [_, _, 2, 3, 4]")
        void handComputed() {
            double[] out = Indicators.sma(new double[]{1, 2, 3, 4, 5}, 3);
            assertEquals(2, firstValid(out));
            assertEquals(2.0, out[2], EPS); // (1+2+3)/3
            assertEquals(3.0, out[3], EPS); // (2+3+4)/3
            assertEquals(4.0, out[4], EPS); // (3+4+5)/3
        }

        @Test
        @DisplayName("output is aligned with input and NaN through the warm-up")
        void alignmentAndNaN() {
            double[] out = Indicators.sma(new double[]{1, 2, 3, 4, 5}, 3);
            assertEquals(5, out.length);
            assertTrue(Double.isNaN(out[0]));
            assertTrue(Double.isNaN(out[1]));
        }
    }

    // ── EMA ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("EMA")
    class Ema {

        @Test
        @DisplayName("hand-computed: EMA(3) over 1..5 seeds at SMA=2 then 3, 4")
        void handComputed() {
            // k = 2/(3+1) = 0.5. Seed at i=2: (1+2+3)/3 = 2.
            // i=3: 2 + 0.5*(4-2) = 3.   i=4: 3 + 0.5*(5-3) = 4.
            double[] out = Indicators.ema(new double[]{1, 2, 3, 4, 5}, 3);
            assertEquals(2, firstValid(out));
            assertEquals(2.0, out[2], EPS);
            assertEquals(3.0, out[3], EPS);
            assertEquals(4.0, out[4], EPS);
        }

        @Test
        @DisplayName("a flat series has EMA equal to the price")
        void flatSeries() {
            double[] out = Indicators.ema(new double[]{7, 7, 7, 7, 7, 7}, 4);
            assertEquals(7.0, out[5], EPS);
        }
    }

    // ── RSI ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("RSI (Wilder)")
    class Rsi {

        @Test
        @DisplayName("hand-computed Wilder walk: closes [1,2,3,2,3], p=2 -> 100, 50, 75")
        void handComputedWilderWalk() {
            // changes: +1, +1, -1, +1
            // seed i=2: avgG=(1+1)/2=1, avgL=0            -> RSI 100
            // i=3 (-1): avgG=(1*1+0)/2=.5, avgL=(0+1)/2=.5 -> RS=1 -> RSI 50
            // i=4 (+1): avgG=(.5+1)/2=.75, avgL=(.5+0)/2=.25 -> RS=3 -> RSI 75
            double[] out = Indicators.rsi(new double[]{1, 2, 3, 2, 3}, 2);
            assertEquals(2, firstValid(out));
            assertEquals(100.0, out[2], EPS);
            assertEquals(50.0, out[3], EPS);
            assertEquals(75.0, out[4], EPS);
        }

        @Test
        @DisplayName("monotonic rise reads 100; flat reads the neutral 50")
        void edgeReadings() {
            double[] rising = Indicators.rsi(new double[]{1, 2, 3, 4, 5, 6}, 3);
            assertEquals(100.0, rising[5], EPS);

            double[] flat = Indicators.rsi(new double[]{5, 5, 5, 5, 5, 5}, 3);
            assertEquals(50.0, flat[5], EPS);
        }

        @Test
        @DisplayName("warm-up: first value at index period (needs period changes)")
        void warmup() {
            double[] out = Indicators.rsi(new double[]{1, 2, 3, 4, 5, 6}, 4);
            assertEquals(4, firstValid(out));
        }
    }

    // ── ATR ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("ATR (Wilder)")
    class Atr {

        @Test
        @DisplayName("hand-computed: constant true range of 2 stays 2 under smoothing")
        void handComputed() {
            // Bars (h,l,c): (10,8,9) (11,9,10) (12,10,11)
            // TR: 2, max(2,|11-9|,|9-9|)=2, max(2,|12-10|,|10-10|)=2
            // p=2: seed i=1 = 2; i=2 = (2*1+2)/2 = 2.
            double[] out = Indicators.atr(
                    new double[]{10, 11, 12},
                    new double[]{8, 9, 10},
                    new double[]{9, 10, 11}, 2);
            assertEquals(1, firstValid(out));
            assertEquals(2.0, out[1], EPS);
            assertEquals(2.0, out[2], EPS);
        }

        @Test
        @DisplayName("gap risk is captured: TR uses the previous close")
        void gapCaptured() {
            // Second bar gaps: h=20,l=19, prev close 9 -> TR = |20-9| = 11.
            double[] out = Indicators.atr(
                    new double[]{10, 20},
                    new double[]{8, 19},
                    new double[]{9, 20}, 2);
            // seed = (TR0 + TR1)/2 = (2 + 11)/2 = 6.5
            assertEquals(6.5, out[1], EPS);
        }
    }

    // ── Bollinger ───────────────────────────────────────────────────────

    @Nested
    @DisplayName("Bollinger Bands")
    class Bollinger {

        @Test
        @DisplayName("hand-computed: window [1,3] has mean 2, sd 1 -> bands at 4 and 0")
        void handComputed() {
            // var = (1+9)/2 - 2^2 = 1, sd = 1; k=2 -> upper 2+2=4, lower 2-2=0.
            double[] upper = Indicators.bbUpper(new double[]{1, 3}, 2, 2);
            double[] lower = Indicators.bbLower(new double[]{1, 3}, 2, 2);
            assertEquals(4.0, upper[1], EPS);
            assertEquals(0.0, lower[1], EPS);
        }

        @Test
        @DisplayName("constant prices collapse both bands onto the mean")
        void constantCollapses() {
            double[] upper = Indicators.bbUpper(new double[]{5, 5, 5, 5}, 3, 2);
            double[] lower = Indicators.bbLower(new double[]{5, 5, 5, 5}, 3, 2);
            assertEquals(5.0, upper[3], EPS);
            assertEquals(5.0, lower[3], EPS);
        }
    }

    // ── MACD ────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("MACD")
    class Macd {

        private final double[] close =
                {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15};

        @Test
        @DisplayName("the line IS ema(fast) - ema(slow) wherever both exist")
        void lineDefinition() {
            double[] line = Indicators.macdLine(close, 3, 6);
            double[] fast = Indicators.ema(close, 3);
            double[] slow = Indicators.ema(close, 6);
            assertEquals(5, firstValid(line)); // slow-1
            for (int i = 5; i < close.length; i++) {
                assertEquals(fast[i] - slow[i], line[i], EPS);
            }
        }

        @Test
        @DisplayName("the signal line warms up at slow-1 + signal-1")
        void signalWarmup() {
            double[] signal = Indicators.macdSignal(close, 3, 6, 4);
            assertEquals(8, firstValid(signal)); // (6-1) + (4-1)
        }
    }

    // ── VWAP ────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("VWAP (UTC session)")
    class Vwap {

        private static final long DAY = 24L * 60 * 60 * 1000;

        @Test
        @DisplayName("hand-computed accumulation, then a hard reset at UTC midnight")
        void accumulatesAndResets() {
            // Day 1 bar 1: typical (12+8+10)/3 = 10, vol 10  -> vwap 10
            // Day 1 bar 2: typical (15+9+12)/3 = 12, vol 30
            //              -> (10*10 + 12*30) / 40 = 460/40 = 11.5
            // Day 2 bar 1: typical (21+18+21)/3 = 20, vol 5  -> RESET -> 20
            long[] t = {0, 3_600_000, DAY};
            double[] h = {12, 15, 21};
            double[] l = {8, 9, 18};
            double[] c = {10, 12, 21};
            double[] v = {10, 30, 5};

            double[] out = Indicators.vwap(t, h, l, c, v);
            assertEquals(10.0, out[0], EPS);
            assertEquals(11.5, out[1], EPS);
            assertEquals(20.0, out[2], EPS);
        }

        @Test
        @DisplayName("a zero-volume session start reads NaN, never a fabricated price")
        void zeroVolumeIsNaN() {
            double[] out = Indicators.vwap(new long[]{0},
                    new double[]{10}, new double[]{9},
                    new double[]{9.5}, new double[]{0});
            assertTrue(Double.isNaN(out[0]));
        }
    }

    // ── Expansion set ───────────────────────────────────────────────────

    @Nested
    @DisplayName("WMA / HMA")
    class WmaHma {

        @Test
        @DisplayName("hand-computed WMA(3): weights 1,2,3")
        void wmaHand() {
            // i=2: (1*1 + 2*2 + 3*3)/6 = 14/6 ; i=3: (1*2+2*3+3*4)/6 = 20/6
            double[] out = Indicators.wma(new double[]{1, 2, 3, 4}, 3);
            assertEquals(14.0 / 6, out[2], EPS);
            assertEquals(20.0 / 6, out[3], EPS);
        }

        @Test
        @DisplayName("HMA of a perfect line IS the line (the extrapolation property)")
        void hmaLinearExact() {
            // For linear input, 2*WMA(p/2) - WMA(p) extrapolates exactly to
            // the current value, and a WMA of a line is the line — so
            // HMA(linear) == input wherever it is defined. Hand-derivable.
            double[] src = {1, 2, 3, 4, 5, 6, 7, 8};
            double[] out = Indicators.hma(src, 4);
            for (int i = 4; i < src.length; i++) {
                assertEquals(src[i], out[i], 1e-9);
            }
        }
    }

    @Nested
    @DisplayName("momentum family")
    class Momentum {

        @Test
        @DisplayName("ROC and MOM hand-computed")
        void rocMom() {
            double[] roc = Indicators.roc(new double[]{10, 10, 20}, 2);
            assertEquals(100.0, roc[2], EPS); // (20-10)/10*100
            double[] mom = Indicators.mom(new double[]{10, 10, 20}, 2);
            assertEquals(10.0, mom[2], EPS);
        }
    }

    @Nested
    @DisplayName("Stochastic / Williams / CCI / MFI")
    class Oscillators {

        private final double[] h = {10, 11, 12};
        private final double[] l = {8, 9, 10};
        private final double[] c = {9, 10, 11};

        @Test
        @DisplayName("hand-computed %K: close position in the 2-bar range")
        void stochKHand() {
            // i=2: hh=12, ll=9 -> (11-9)/(12-9)*100 = 66.66..
            double[] out = Indicators.stochK(h, l, c, 2);
            assertEquals(200.0 / 3, out[1], EPS);
            assertEquals(200.0 / 3, out[2], EPS);
        }

        @Test
        @DisplayName("%D warms up at k-1 + d-1")
        void stochDWarmup() {
            double[] out = Indicators.stochD(h, l, c, 2, 2);
            assertEquals(2, firstValid(out));
        }

        @Test
        @DisplayName("hand-computed Williams %R is %K measured from the top")
        void willrHand() {
            // i=2: -(12-11)/(12-9)*100 = -33.33..
            double[] out = Indicators.willr(h, l, c, 2);
            assertEquals(-100.0 / 3, out[2], EPS);
        }

        @Test
        @DisplayName("hand-computed CCI with Lambert's 0.015")
        void cciHand() {
            // tp = 9,10,11 ; i=1: mean 9.5, meanDev 0.5
            // -> (10-9.5)/(0.015*0.5) = 66.66..
            double[] out = Indicators.cci(h, l, c, 2);
            assertEquals(200.0 / 3, out[1], EPS);
        }

        @Test
        @DisplayName("MFI reads 100 when every flow in the window is positive")
        void mfiAllUp() {
            double[] out = Indicators.mfi(h, l, c,
                    new double[]{5, 5, 5}, 2);
            assertEquals(100.0, out[2], EPS);
        }
    }

    @Nested
    @DisplayName("ADX / DI (Wilder's directional system)")
    class Adx {

        /** A relentless uptrend: +1 per bar, tight bars. */
        private double[][] uptrend(int n) {
            double[] h = new double[n];
            double[] l = new double[n];
            double[] c = new double[n];
            for (int i = 0; i < n; i++) {
                c[i] = 100 + i;
                h[i] = c[i] + 0.5;
                l[i] = c[i] - 0.5;
            }
            return new double[][]{h, l, c};
        }

        @Test
        @DisplayName("a relentless uptrend drives ADX toward 100, +DI over -DI")
        void uptrendReadsTrending() {
            double[][] s = uptrend(40);
            double[] adx = Indicators.adx(s[0], s[1], s[2], 5);
            double[] pdi = Indicators.plusDi(s[0], s[1], s[2], 5);
            double[] mdi = Indicators.minusDi(s[0], s[1], s[2], 5);

            assertEquals(9, firstValid(adx)); // 2p-1
            assertTrue(adx[39] > 90, "steady trend -> DX 100 -> ADX high");
            assertTrue(pdi[39] > mdi[39]);
            assertEquals(0.0, mdi[39], EPS); // lows never fall in this series
        }

        @Test
        @DisplayName("ADX stays within [0, 100] and NaN-prefixes correctly")
        void bounded() {
            double[][] s = uptrend(30);
            double[] adx = Indicators.adx(s[0], s[1], s[2], 4);
            for (int i = firstValid(adx); i < 30; i++) {
                assertTrue(adx[i] >= 0 && adx[i] <= 100);
            }
        }
    }

    @Nested
    @DisplayName("SuperTrend")
    class SuperTrend {

        @Test
        @DisplayName("line sits below price in an uptrend, flips above after a crash")
        void ratchetAndFlip() {
            int n = 22;
            double[] h = new double[n];
            double[] l = new double[n];
            double[] c = new double[n];
            for (int i = 0; i < 20; i++) {
                c[i] = 100 + i;
                h[i] = c[i] + 1;
                l[i] = c[i] - 1;
            }
            // Crash: two bars far below any carried lower band.
            c[20] = 80; h[20] = 82; l[20] = 78;
            c[21] = 79; h[21] = 81; l[21] = 77;

            double[] st = Indicators.supertrend(h, l, c, 3, 2);
            assertTrue(st[18] < c[18], "uptrend: line below price");
            assertTrue(st[21] > c[21], "after the crash: line above price");
        }
    }

    @Nested
    @DisplayName("channels, window stats, OBV")
    class ChannelsAndVolume {

        @Test
        @DisplayName("Donchian and HIGHEST/LOWEST are window extremes")
        void windowExtremes() {
            double[] upper = Indicators.donchianUpper(
                    new double[]{10, 11, 12}, 2);
            assertEquals(11.0, upper[1], EPS);
            assertEquals(12.0, upper[2], EPS);

            double[] hi = Indicators.highest(new double[]{1, 3, 2}, 2);
            assertEquals(3.0, hi[1], EPS);
            assertEquals(3.0, hi[2], EPS); // 3 still in the window
            double[] lo = Indicators.lowest(new double[]{1, 3, 2}, 2);
            assertEquals(1.0, lo[1], EPS);
            assertEquals(2.0, lo[2], EPS);
        }

        @Test
        @DisplayName("STDDEV is the Bollinger sigma exposed: sd([1,3]) = 1")
        void stddevHand() {
            double[] out = Indicators.stddev(new double[]{1, 3}, 2);
            assertEquals(1.0, out[1], EPS);
        }

        @Test
        @DisplayName("OBV: +vol on up closes, -vol on down, flat adds nothing")
        void obvHand() {
            double[] out = Indicators.obv(new double[]{10, 11, 10, 10},
                    new double[]{5, 5, 5, 5});
            assertEquals(0.0, out[0], EPS);
            assertEquals(5.0, out[1], EPS);
            assertEquals(0.0, out[2], EPS);
            assertEquals(0.0, out[3], EPS);
        }
    }

    // ── Cross-cutting ───────────────────────────────────────────────────

    @Test
    @DisplayName("every indicator's output is exactly input-length aligned")
    void allAligned() {
        double[] src = {1, 2, 3, 4, 5, 6, 7, 8};
        long[] t = {0, 1, 2, 3, 4, 5, 6, 7};
        assertEquals(8, Indicators.sma(src, 3).length);
        assertEquals(8, Indicators.ema(src, 3).length);
        assertEquals(8, Indicators.rsi(src, 3).length);
        assertEquals(8, Indicators.atr(src, src, src, 3).length);
        assertEquals(8, Indicators.bbUpper(src, 3, 2).length);
        assertEquals(8, Indicators.macdLine(src, 2, 4).length);
        assertEquals(8, Indicators.vwap(t, src, src, src, src).length);
        // Expansion set stays aligned too.
        assertEquals(8, Indicators.wma(src, 3).length);
        assertEquals(8, Indicators.hma(src, 4).length);
        assertEquals(8, Indicators.roc(src, 2).length);
        assertEquals(8, Indicators.stochK(src, src, src, 3).length);
        assertEquals(8, Indicators.cci(src, src, src, 3).length);
        assertEquals(8, Indicators.mfi(src, src, src, src, 3).length);
        assertEquals(8, Indicators.adx(src, src, src, 3).length);
        assertEquals(8, Indicators.supertrend(src, src, src, 3, 2).length);
        assertEquals(8, Indicators.obv(src, src).length);
        assertEquals(8, Indicators.highest(src, 3).length);
        assertEquals(8, Indicators.stddev(src, 3).length);
    }

    @Test
    @DisplayName("series shorter than the period yield all-NaN, never an exception")
    void shortSeries() {
        assertEquals(-1, firstValid(Indicators.sma(new double[]{1, 2}, 5)));
        assertEquals(-1, firstValid(Indicators.rsi(new double[]{1, 2}, 5)));
        assertEquals(-1, firstValid(Indicators.atr(
                new double[]{1}, new double[]{1}, new double[]{1}, 5)));
    }
}