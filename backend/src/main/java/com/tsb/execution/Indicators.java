package com.tsb.execution;

/**
 * The seven MVP indicators as pure static functions: {@code double[]} in,
 * aligned {@code double[]} out. This class imports nothing outside
 * {@code java.*} — no Spring, no database, no compiler types — which is
 * what makes every function verifiable against hand-computed values.
 *
 * <p><b>These are the only implementations.</b> The engine precomputes from
 * the indicator manifest using these; the chart endpoints will serve these
 * same arrays; nothing else in the platform computes an indicator
 * (addendum §5.4: "computed exactly once" — two implementations of the same
 * maths is the bug factory).
 *
 * <p><b>Warm-up convention:</b> outputs are {@link Double#NaN} until the
 * indicator has enough history. NaN, not zero: a zero is a plausible number
 * a strategy could silently trade against ({@code CLOSE > SMA} is trivially
 * true vs 0); NaN poisons every comparison to false and makes accidental
 * warm-up use loud. The engine additionally refuses to trade before the
 * compile-time warmupBars — two independent layers.
 *
 * <p><b>Formulas follow the reference definitions</b> (Wilder's smoothing
 * for RSI/ATR, EMA-seeded-by-SMA, population stddev Bollinger, UTC-session
 * VWAP) so values agree with TradingView — which matters the moment users
 * compare, and the moment the Pine exporter exists.
 */
public final class Indicators {

    private static final long DAY_MILLIS = 24L * 60 * 60 * 1000;

    // ── SMA ─────────────────────────────────────────────────────────────

    /** Simple moving average. First value at index period-1. O(n) via a
     *  rolling sum: add the entering value, subtract the leaving one. */
    public static double[] sma(double[] src, int period) {
        requirePeriod(period);
        double[] out = nanArray(src.length);
        double sum = 0;
        for (int i = 0; i < src.length; i++) {
            sum += src[i];
            if (i >= period) {
                sum -= src[i - period];
            }
            if (i >= period - 1) {
                out[i] = sum / period;
            }
        }
        return out;
    }

    // ── EMA ─────────────────────────────────────────────────────────────

    /** Exponential moving average, seeded with the SMA of the first
     *  {@code period} values (the reference convention), then
     *  {@code ema = prev + k * (x - prev)} with {@code k = 2/(period+1)}. */
    public static double[] ema(double[] src, int period) {
        return emaFrom(src, period, 0);
    }

    /** EMA over a series that may begin with a NaN prefix (used to chain
     *  EMAs, e.g. the MACD signal line = EMA of the MACD line). */
    static double[] emaFrom(double[] src, int period, int firstValid) {
        requirePeriod(period);
        double[] out = nanArray(src.length);
        int seedIndex = firstValid + period - 1;
        if (seedIndex >= src.length) {
            return out;
        }
        double seed = 0;
        for (int i = firstValid; i <= seedIndex; i++) {
            seed += src[i];
        }
        double emaValue = seed / period;
        out[seedIndex] = emaValue;
        double k = 2.0 / (period + 1);
        for (int i = seedIndex + 1; i < src.length; i++) {
            emaValue = emaValue + k * (src[i] - emaValue);
            out[i] = emaValue;
        }
        return out;
    }

    // ── RSI (Wilder) ────────────────────────────────────────────────────

    /**
     * Relative Strength Index exactly as Wilder defined it: average
     * gain/loss over the first {@code period} price changes as the seed,
     * then Wilder smoothing {@code avg = (prev*(period-1) + current)/period}.
     * First value at index {@code period}.
     *
     * <p>Edge cases: no losses in the window -> 100; no gains -> 0; a
     * completely flat window (both zero) -> 50, the neutral reading.
     */
    public static double[] rsi(double[] close, int period) {
        requirePeriod(period);
        double[] out = nanArray(close.length);
        if (close.length <= period) {
            return out;
        }
        double avgGain = 0;
        double avgLoss = 0;
        for (int i = 1; i <= period; i++) {
            double change = close[i] - close[i - 1];
            if (change > 0) {
                avgGain += change;
            } else {
                avgLoss -= change;
            }
        }
        avgGain /= period;
        avgLoss /= period;
        out[period] = rsiValue(avgGain, avgLoss);

        for (int i = period + 1; i < close.length; i++) {
            double change = close[i] - close[i - 1];
            double gain = Math.max(change, 0);
            double loss = Math.max(-change, 0);
            avgGain = (avgGain * (period - 1) + gain) / period;
            avgLoss = (avgLoss * (period - 1) + loss) / period;
            out[i] = rsiValue(avgGain, avgLoss);
        }
        return out;
    }

    private static double rsiValue(double avgGain, double avgLoss) {
        if (avgLoss == 0 && avgGain == 0) {
            return 50; // flat market: neutral
        }
        if (avgLoss == 0) {
            return 100;
        }
        return 100 - 100 / (1 + avgGain / avgLoss);
    }

    // ── ATR (Wilder) ────────────────────────────────────────────────────

    /** Average True Range with Wilder smoothing. TR[0] = high-low (no
     *  previous close exists); thereafter the max of the three classic
     *  ranges. Seeded with the SMA of the first {@code period} TRs; first
     *  value at index period-1. */
    public static double[] atr(double[] high, double[] low, double[] close,
                               int period) {
        requirePeriod(period);
        int n = high.length;
        double[] out = nanArray(n);
        if (n == 0) {
            return out;
        }
        double[] tr = new double[n];
        tr[0] = high[0] - low[0];
        for (int i = 1; i < n; i++) {
            tr[i] = Math.max(high[i] - low[i], Math.max(
                    Math.abs(high[i] - close[i - 1]),
                    Math.abs(low[i] - close[i - 1])));
        }
        if (n < period) {
            return out;
        }
        double atrValue = 0;
        for (int i = 0; i < period; i++) {
            atrValue += tr[i];
        }
        atrValue /= period;
        out[period - 1] = atrValue;
        for (int i = period; i < n; i++) {
            atrValue = (atrValue * (period - 1) + tr[i]) / period;
            out[i] = atrValue;
        }
        return out;
    }

    // ── Bollinger Bands ─────────────────────────────────────────────────

    /** Middle band SMA plus {@code k} population standard deviations.
     *  Rolling sum + sum-of-squares keeps it O(n); at price magnitudes
     *  (~1e5) and window sizes (~hundreds) double precision leaves ~9
     *  significant digits for the variance, far beyond indicator needs. */
    public static double[] bbUpper(double[] src, int period, double k) {
        return bollinger(src, period, k);
    }

    public static double[] bbLower(double[] src, int period, double k) {
        return bollinger(src, period, -k);
    }

    private static double[] bollinger(double[] src, int period, double k) {
        requirePeriod(period);
        double[] out = nanArray(src.length);
        double sum = 0;
        double sumSq = 0;
        for (int i = 0; i < src.length; i++) {
            sum += src[i];
            sumSq += src[i] * src[i];
            if (i >= period) {
                sum -= src[i - period];
                sumSq -= src[i - period] * src[i - period];
            }
            if (i >= period - 1) {
                double mean = sum / period;
                double variance = Math.max(0, sumSq / period - mean * mean);
                out[i] = mean + k * Math.sqrt(variance);
            }
        }
        return out;
    }

    // ── MACD ────────────────────────────────────────────────────────────

    /** MACD line = EMA(fast) - EMA(slow). First value where both exist:
     *  index slow-1. */
    public static double[] macdLine(double[] close, int fast, int slow) {
        double[] fastEma = ema(close, fast);
        double[] slowEma = ema(close, slow);
        double[] out = nanArray(close.length);
        for (int i = 0; i < close.length; i++) {
            out[i] = fastEma[i] - slowEma[i]; // NaN propagates correctly
        }
        return out;
    }

    /** Signal line = EMA(signal) of the MACD line, respecting the line's
     *  NaN prefix. First value at index slow-1 + signal-1. */
    public static double[] macdSignal(double[] close, int fast, int slow,
                                      int signal) {
        double[] line = macdLine(close, fast, slow);
        return emaFrom(line, signal, slow - 1);
    }

    // ── VWAP (UTC session) ──────────────────────────────────────────────

    /**
     * Volume-weighted average price, anchored to UTC midnight — the crypto
     * convention (TradingView's choice for 24/7 markets). Cumulative
     * (typicalPrice x volume) / cumulative volume since session start;
     * both accumulators reset when the bar's UTC day changes. A session
     * with zero volume so far reads NaN, not a fabricated price.
     */
    public static double[] vwap(long[] openTimeMillis, double[] high,
                                double[] low, double[] close, double[] volume) {
        int n = openTimeMillis.length;
        double[] out = nanArray(n);
        double cumPV = 0;
        double cumV = 0;
        long currentDay = Long.MIN_VALUE;
        for (int i = 0; i < n; i++) {
            long day = Math.floorDiv(openTimeMillis[i], DAY_MILLIS);
            if (day != currentDay) {
                currentDay = day;
                cumPV = 0;
                cumV = 0;
            }
            double typical = (high[i] + low[i] + close[i]) / 3.0;
            cumPV += typical * volume[i];
            cumV += volume[i];
            out[i] = cumV == 0 ? Double.NaN : cumPV / cumV;
        }
        return out;
    }

    // ── Shared ──────────────────────────────────────────────────────────

    private static double[] nanArray(int n) {
        double[] a = new double[n];
        java.util.Arrays.fill(a, Double.NaN);
        return a;
    }

    private static void requirePeriod(int period) {
        if (period < 1) {
            throw new IllegalArgumentException("period must be >= 1, got " + period);
        }
    }

    private Indicators() {
    }
}