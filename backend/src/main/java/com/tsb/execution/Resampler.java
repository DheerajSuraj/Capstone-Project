package com.tsb.execution;

import com.tsb.marketdata.CandleSeries;

import java.util.Arrays;

/**
 * Builds bigger candles (4h, 1d, 1w…) out of the strategy's own candles, and
 * says which bigger candle each small candle is allowed to see.
 *
 * <p><b>The one rule that keeps a higher timeframe honest:</b> a small candle
 * may only use a bigger candle that had already CLOSED when the small candle
 * closed. The 1h candle 03:00–04:00 may read the 4h candle 00:00–04:00
 * (both close at 04:00); the 1h candle 02:00–03:00 may not — that 4h candle's
 * close was still an hour away. Joining by START time instead is the classic
 * multi-timeframe bug that silently hands a backtest the future.
 *
 * <p><b>Buckets:</b> UTC-aligned, like the exchange's candles — 4h candles
 * start at 00:00, 04:00…; days at 00:00 UTC; weeks on Monday 00:00 UTC.
 * Open = first open, high = highest high, low = lowest low, close = last
 * close, volume = summed.
 *
 * <ul>
 *   <li>A bucket the series starts in the middle of is skipped: its open and
 *       high/low would be missing the candles before the series began.</li>
 *   <li>The bucket still forming at the end is left out: it is not a candle
 *       yet.</li>
 *   <li>A bucket with a gap in the data (a missing hour) is built from the
 *       candles that exist, and becomes visible from the first small candle
 *       that closes at or after the bucket's close.</li>
 * </ul>
 */
public final class Resampler {

    private static final long DAY = 86_400_000L;
    /** 1970-01-01 was a Thursday; Monday 00:00 UTC is 3 days earlier. */
    private static final long WEEK_SHIFT = 3 * DAY;

    /**
     * Bigger candles plus when each one closes. {@code closeTime[j]} is the
     * nominal end of bucket j (start + length), even when a gap means its
     * last small candle is missing.
     */
    public record Result(CandleSeries candles, long[] closeTime) {
        public int size() {
            return closeTime.length;
        }
    }

    private Resampler() {
    }

    /** Start of the UTC bucket containing {@code t}. */
    public static long bucketStart(long t, String timeframe, long bigMillis) {
        if (timeframe.endsWith("w")) {
            return Math.floorDiv(t + WEEK_SHIFT, bigMillis) * bigMillis - WEEK_SHIFT;
        }
        return Math.floorDiv(t, bigMillis) * bigMillis;
    }

    /** Groups the small candles into the bigger, closed candles. */
    public static Result resample(CandleSeries small, long smallMillis,
                                  String timeframe, long bigMillis) {
        int n = small.size();
        long[] t = small.openTimeMillis();
        long[] bt = new long[n];
        long[] bc = new long[n];
        double[] bo = new double[n], bh = new double[n], bl = new double[n],
                bcl = new double[n], bv = new double[n];
        int m = 0;
        if (n == 0) {
            return new Result(CandleSeries.empty(), new long[0]);
        }

        int i = 0;
        // Skip a bucket the series starts in the middle of.
        long first = bucketStart(t[0], timeframe, bigMillis);
        if (t[0] != first) {
            while (i < n && bucketStart(t[i], timeframe, bigMillis) == first) {
                i++;
            }
        }

        long lastClose = t[n - 1] + smallMillis;
        while (i < n) {
            long start = bucketStart(t[i], timeframe, bigMillis);
            double open = small.open()[i];
            double high = Double.NEGATIVE_INFINITY;
            double low = Double.POSITIVE_INFINITY;
            double close = Double.NaN;
            double volume = 0;
            while (i < n && bucketStart(t[i], timeframe, bigMillis) == start) {
                high = Math.max(high, small.high()[i]);
                low = Math.min(low, small.low()[i]);
                close = small.close()[i];
                volume += small.volume()[i];
                i++;
            }
            long end = start + bigMillis;
            if (end > lastClose) {
                break; // still forming: not a candle yet
            }
            bt[m] = start;
            bc[m] = end;
            bo[m] = open;
            bh[m] = high;
            bl[m] = low;
            bcl[m] = close;
            bv[m] = volume;
            m++;
        }
        CandleSeries big = new CandleSeries(Arrays.copyOf(bt, m), Arrays.copyOf(bo, m),
                Arrays.copyOf(bh, m), Arrays.copyOf(bl, m), Arrays.copyOf(bcl, m),
                Arrays.copyOf(bv, m));
        return new Result(big, Arrays.copyOf(bc, m));
    }

    /**
     * For each small candle, the index of the last bigger candle that had
     * closed by the time the small candle closed, or -1 if none had.
     */
    public static int[] visible(Result big, CandleSeries small, long smallMillis) {
        int n = small.size();
        int[] out = new int[n];
        int j = -1;
        for (int i = 0; i < n; i++) {
            long closesAt = small.openTimeMillis()[i] + smallMillis;
            while (j + 1 < big.size() && big.closeTime()[j + 1] <= closesAt) {
                j++;
            }
            out[i] = j;
        }
        return out;
    }
}
