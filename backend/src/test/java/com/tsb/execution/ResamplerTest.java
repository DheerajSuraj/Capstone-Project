package com.tsb.execution;

import com.tsb.marketdata.CandleSeries;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Building bigger candles, and which one each small candle may see. */
@DisplayName("Resampler")
class ResamplerTest {

    static final long HOUR = 3_600_000L;
    static final long FOUR_H = 4 * HOUR;
    static final long DAY = 24 * HOUR;
    /** 2024-01-01T00:00:00Z, a Monday. */
    static final long MIDNIGHT = 1_704_067_200_000L;

    /** Hourly candles from {@code start}: candle i has open 100+i, close
     *  100+i+0.5, high +1, low -1, volume i+1 — every number traceable. */
    static CandleSeries hourly(long start, int n) {
        long[] t = new long[n];
        double[] o = new double[n], h = new double[n], l = new double[n],
                c = new double[n], v = new double[n];
        for (int i = 0; i < n; i++) {
            t[i] = start + i * HOUR;
            o[i] = 100 + i;
            c[i] = 100 + i + 0.5;
            h[i] = 100 + i + 1;
            l[i] = 100 + i - 1;
            v[i] = i + 1;
        }
        return new CandleSeries(t, o, h, l, c, v);
    }

    @Nested
    @DisplayName("building bigger candles")
    class Building {

        @Test
        @DisplayName("a 4h candle is first open, highest high, lowest low, last close, summed volume")
        void ohlcv() {
            Resampler.Result r = Resampler.resample(hourly(MIDNIGHT, 8), HOUR, "4h", FOUR_H);
            assertEquals(2, r.size());
            CandleSeries big = r.candles();
            assertEquals(MIDNIGHT, big.openTimeMillis()[0]);
            assertEquals(100.0, big.open()[0]);           // hour 0's open
            assertEquals(104.0, big.high()[0]);           // hour 3's high (103 + 1)
            assertEquals(99.0, big.low()[0]);             // hour 0's low
            assertEquals(103.5, big.close()[0]);          // hour 3's close
            assertEquals(1 + 2 + 3 + 4.0, big.volume()[0]);
            assertEquals(MIDNIGHT + FOUR_H, r.closeTime()[0]);
            assertEquals(107.5, big.close()[1]);
        }

        @Test
        @DisplayName("a 4h candle the data starts in the middle of is skipped")
        void skipsPartialStart() {
            // Starts at 02:00: the 00:00–04:00 candle would be missing two hours.
            Resampler.Result r = Resampler.resample(hourly(MIDNIGHT + 2 * HOUR, 10), HOUR, "4h", FOUR_H);
            assertEquals(MIDNIGHT + FOUR_H, r.candles().openTimeMillis()[0]);
            assertEquals(2, r.size());
        }

        @Test
        @DisplayName("the 4h candle still forming at the end is left out")
        void dropsFormingEnd() {
            // 00:00..06:59: the 04:00–08:00 candle has only three hours so far.
            Resampler.Result r = Resampler.resample(hourly(MIDNIGHT, 7), HOUR, "4h", FOUR_H);
            assertEquals(1, r.size());
        }

        @Test
        @DisplayName("days start at 00:00 UTC and weeks on Monday 00:00 UTC")
        void utcAlignment() {
            Resampler.Result days = Resampler.resample(hourly(MIDNIGHT, 72), HOUR, "1d", DAY);
            assertEquals(3, days.size());
            assertEquals(MIDNIGHT + DAY, days.candles().openTimeMillis()[1]);

            long sunday = MIDNIGHT - DAY; // 2023-12-31, a Sunday
            Resampler.Result weeks = Resampler.resample(hourly(sunday, 24 * 16), HOUR, "1w", 7 * DAY);
            // The Sunday starts in the middle of a week (skipped); the first
            // full week opens on Monday 1 January.
            assertEquals(MIDNIGHT, weeks.candles().openTimeMillis()[0]);
            assertEquals(DayOfWeek.MONDAY, Instant.ofEpochMilli(
                    weeks.candles().openTimeMillis()[0]).atZone(ZoneOffset.UTC).getDayOfWeek());
        }
    }

    @Nested
    @DisplayName("which bigger candle a small candle may see")
    class Visibility {

        @Test
        @DisplayName("the 03:00–04:00 candle sees the 00:00–04:00 candle; the 02:00–03:00 one does not")
        void closesTogether() {
            CandleSeries hours = hourly(MIDNIGHT, 9);
            Resampler.Result r = Resampler.resample(hours, HOUR, "4h", FOUR_H);
            int[] vis = Resampler.visible(r, hours, HOUR);
            assertEquals(-1, vis[0]); // 00–01
            assertEquals(-1, vis[1]);
            assertEquals(-1, vis[2]); // 02–03: the 4h close is an hour away
            assertEquals(0, vis[3]);  // 03–04 closes with it at 04:00
            assertEquals(0, vis[6]);  // 06–07: still the 00–04 candle
            assertEquals(1, vis[7]);  // 07–08 closes with the 04–08 candle
            assertEquals(1, vis[8]);  // 08–09: 08–12 is still forming
        }

        @Test
        @DisplayName("with a missing hour, the 4h candle becomes visible once its time has passed")
        void gap() {
            // Hours 0,1,2 then a gap (03:00 missing), then 04:00 onwards.
            long[] t = {MIDNIGHT, MIDNIGHT + HOUR, MIDNIGHT + 2 * HOUR,
                    MIDNIGHT + 4 * HOUR, MIDNIGHT + 5 * HOUR};
            double[] x = {1, 2, 3, 4, 5};
            CandleSeries s = new CandleSeries(t, x, x, x, x, x);
            Resampler.Result r = Resampler.resample(s, HOUR, "4h", FOUR_H);
            assertEquals(1, r.size()); // 00–04 built from the three hours that exist
            assertEquals(3.0, r.candles().close()[0]);
            int[] vis = Resampler.visible(r, s, HOUR);
            assertEquals(-1, vis[2]); // 02–03 closes at 03:00, before 04:00
            assertEquals(0, vis[3]);  // 04–05 is the first candle closing after 04:00
        }

        @Test
        @DisplayName("never a bigger candle that closes after the small one")
        void neverEarly() {
            CandleSeries hours = hourly(MIDNIGHT + 3 * HOUR, 200);
            Resampler.Result r = Resampler.resample(hours, HOUR, "4h", FOUR_H);
            int[] vis = Resampler.visible(r, hours, HOUR);
            for (int i = 0; i < hours.size(); i++) {
                long closes = hours.openTimeMillis()[i] + HOUR;
                if (vis[i] >= 0) {
                    assertTrue(r.closeTime()[vis[i]] <= closes, "candle " + i);
                }
                if (vis[i] + 1 < r.size()) {
                    assertTrue(r.closeTime()[vis[i] + 1] > closes,
                            "candle " + i + " should already see the next one");
                }
            }
        }
    }
}
