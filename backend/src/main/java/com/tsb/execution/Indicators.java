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


    // ═══════════════════ Expansion set (indicators 8-20+) ═══════════════
    // Same rules as the original seven: reference formulas, NaN warm-up
    // prefixes, O(n) or O(n*p) single passes (p is small; 214k*200 ops is
    // milliseconds), and windowed helpers that yield NaN whenever their
    // window still contains NaN — which is what lets HMA chain WMAs over a
    // NaN-prefixed input correctly.

    // ── Weighted / Hull moving averages ─────────────────────────────────

    /** Linearly weighted MA: weight i+1 on the i-th oldest value. */
    public static double[] wma(double[] src, int period) {
        requirePeriod(period);
        double[] out = nanArray(src.length);
        double denom = period * (period + 1) / 2.0;
        for (int i = period - 1; i < src.length; i++) {
            double sum = 0;
            boolean valid = true;
            for (int j = 0; j < period; j++) {
                double v = src[i - period + 1 + j];
                if (Double.isNaN(v)) {
                    valid = false;
                    break;
                }
                sum += (j + 1) * v;
            }
            out[i] = valid ? sum / denom : Double.NaN;
        }
        return out;
    }

    /** Hull MA: WMA(sqrt(p)) of (2*WMA(p/2) - WMA(p)) — fast AND smooth.
     *  For a perfectly linear series the extrapolation is exact, which the
     *  tests exploit as a hand-checkable property. */
    public static double[] hma(double[] src, int period) {
        requirePeriod(period);
        int half = Math.max(1, period / 2);
        int sqrt = Math.max(1, (int) Math.round(Math.sqrt(period)));
        double[] fast = wma(src, half);
        double[] slow = wma(src, period);
        double[] raw = nanArray(src.length);
        for (int i = 0; i < src.length; i++) {
            raw[i] = 2 * fast[i] - slow[i]; // NaN propagates
        }
        return wma(raw, sqrt); // NaN-window tolerance makes chaining safe
    }

    // ── Momentum family ─────────────────────────────────────────────────

    /** Rate of change, percent: 100 * (x - x[p]) / x[p]. */
    public static double[] roc(double[] src, int period) {
        requirePeriod(period);
        double[] out = nanArray(src.length);
        for (int i = period; i < src.length; i++) {
            out[i] = src[i - period] == 0 ? Double.NaN
                    : (src[i] - src[i - period]) / src[i - period] * 100.0;
        }
        return out;
    }

    /** Raw momentum: x - x[p]. */
    public static double[] mom(double[] src, int period) {
        requirePeriod(period);
        double[] out = nanArray(src.length);
        for (int i = period; i < src.length; i++) {
            out[i] = src[i] - src[i - period];
        }
        return out;
    }

    // ── Stochastic / Williams / CCI / MFI ───────────────────────────────

    /** Stochastic %K: where the close sits in the k-period high-low range.
     *  A zero range (flat window) reads the neutral 50. */
    public static double[] stochK(double[] high, double[] low, double[] close,
                                  int kPeriod) {
        requirePeriod(kPeriod);
        double[] out = nanArray(close.length);
        for (int i = kPeriod - 1; i < close.length; i++) {
            double hh = Double.NEGATIVE_INFINITY;
            double ll = Double.POSITIVE_INFINITY;
            for (int j = i - kPeriod + 1; j <= i; j++) {
                hh = Math.max(hh, high[j]);
                ll = Math.min(ll, low[j]);
            }
            out[i] = hh == ll ? 50 : (close[i] - ll) / (hh - ll) * 100.0;
        }
        return out;
    }

    /** Stochastic %D: an SMA of %K (NaN-tolerant window). */
    public static double[] stochD(double[] high, double[] low, double[] close,
                                  int kPeriod, int dSmooth) {
        return smaNanTolerant(stochK(high, low, close, kPeriod), dSmooth);
    }

    /** Williams %R: like %K but measured from the top, range 0..-100. */
    public static double[] willr(double[] high, double[] low, double[] close,
                                 int period) {
        requirePeriod(period);
        double[] out = nanArray(close.length);
        for (int i = period - 1; i < close.length; i++) {
            double hh = Double.NEGATIVE_INFINITY;
            double ll = Double.POSITIVE_INFINITY;
            for (int j = i - period + 1; j <= i; j++) {
                hh = Math.max(hh, high[j]);
                ll = Math.min(ll, low[j]);
            }
            out[i] = hh == ll ? -50 : -(hh - close[i]) / (hh - ll) * 100.0;
        }
        return out;
    }

    /** Commodity Channel Index over typical price, Lambert's 0.015 factor. */
    public static double[] cci(double[] high, double[] low, double[] close,
                               int period) {
        requirePeriod(period);
        int n = close.length;
        double[] tp = new double[n];
        for (int i = 0; i < n; i++) {
            tp[i] = (high[i] + low[i] + close[i]) / 3.0;
        }
        double[] out = nanArray(n);
        for (int i = period - 1; i < n; i++) {
            double mean = 0;
            for (int j = i - period + 1; j <= i; j++) {
                mean += tp[j];
            }
            mean /= period;
            double dev = 0;
            for (int j = i - period + 1; j <= i; j++) {
                dev += Math.abs(tp[j] - mean);
            }
            dev /= period;
            out[i] = dev == 0 ? 0 : (tp[i] - mean) / (0.015 * dev);
        }
        return out;
    }

    /** Money Flow Index: volume-weighted RSI over typical price. All-up
     *  window -> 100; dead window -> 50. */
    public static double[] mfi(double[] high, double[] low, double[] close,
                               double[] volume, int period) {
        requirePeriod(period);
        int n = close.length;
        double[] out = nanArray(n);
        double[] tp = new double[n];
        for (int i = 0; i < n; i++) {
            tp[i] = (high[i] + low[i] + close[i]) / 3.0;
        }
        for (int i = period; i < n; i++) {
            double pos = 0;
            double neg = 0;
            for (int j = i - period + 1; j <= i; j++) {
                double flow = tp[j] * volume[j];
                if (tp[j] > tp[j - 1]) {
                    pos += flow;
                } else if (tp[j] < tp[j - 1]) {
                    neg += flow;
                }
            }
            out[i] = (pos == 0 && neg == 0) ? 50
                    : neg == 0 ? 100
                    : 100 - 100 / (1 + pos / neg);
        }
        return out;
    }

    // ── Wilder's directional system (ADX / DI) ──────────────────────────

    /** +DI: Wilder-smoothed positive directional movement over ATR. */
    public static double[] plusDi(double[] high, double[] low, double[] close,
                                  int period) {
        return dmi(high, low, close, period)[0];
    }

    public static double[] minusDi(double[] high, double[] low, double[] close,
                                   int period) {
        return dmi(high, low, close, period)[1];
    }

    /** ADX: Wilder smoothing of DX, itself the normalized DI spread. First
     *  value at index 2p-1 — the double smoothing chain is why the registry
     *  charges it the largest warm-up of any indicator. */
    public static double[] adx(double[] high, double[] low, double[] close,
                               int period) {
        return dmi(high, low, close, period)[2];
    }

    private static double[][] dmi(double[] high, double[] low, double[] close,
                                  int period) {
        requirePeriod(period);
        int n = close.length;
        double[] pDi = nanArray(n);
        double[] mDi = nanArray(n);
        double[] adx = nanArray(n);
        if (n <= period) {
            return new double[][]{pDi, mDi, adx};
        }
        // Raw TR / +DM / -DM per bar (index >= 1).
        double smTr = 0;
        double smPdm = 0;
        double smMdm = 0;
        for (int i = 1; i <= period; i++) {
            smTr += trueRange(high, low, close, i);
            double up = high[i] - high[i - 1];
            double down = low[i - 1] - low[i];
            smPdm += (up > down && up > 0) ? up : 0;
            smMdm += (down > up && down > 0) ? down : 0;
        }
        double[] dx = nanArray(n);
        for (int i = period; i < n; i++) {
            if (i > period) {
                // Wilder running smooth: sum - sum/p + current.
                smTr = smTr - smTr / period + trueRange(high, low, close, i);
                double up = high[i] - high[i - 1];
                double down = low[i - 1] - low[i];
                smPdm = smPdm - smPdm / period + ((up > down && up > 0) ? up : 0);
                smMdm = smMdm - smMdm / period + ((down > up && down > 0) ? down : 0);
            }
            pDi[i] = smTr == 0 ? 0 : 100 * smPdm / smTr;
            mDi[i] = smTr == 0 ? 0 : 100 * smMdm / smTr;
            double sum = pDi[i] + mDi[i];
            dx[i] = sum == 0 ? 0 : 100 * Math.abs(pDi[i] - mDi[i]) / sum;
        }
        // ADX: seed with the average of the first p DX values, then Wilder.
        int seedEnd = 2 * period - 1;
        if (seedEnd < n) {
            double seed = 0;
            for (int i = period; i <= seedEnd; i++) {
                seed += dx[i];
            }
            double a = seed / period;
            adx[seedEnd] = a;
            for (int i = seedEnd + 1; i < n; i++) {
                a = (a * (period - 1) + dx[i]) / period;
                adx[i] = a;
            }
        }
        return new double[][]{pDi, mDi, adx};
    }

    private static double trueRange(double[] high, double[] low, double[] close,
                                    int i) {
        return Math.max(high[i] - low[i], Math.max(
                Math.abs(high[i] - close[i - 1]),
                Math.abs(low[i] - close[i - 1])));
    }

    // ── SuperTrend ──────────────────────────────────────────────────────

    /**
     * SuperTrend line value: in an uptrend the line is the carried lower
     * band (below price), in a downtrend the carried upper band (above).
     * Price crossing the carried band flips the trend — the classic
     * ratchet. Output is price-comparable, so strategies write
     * {@code CLOSE > SUPERTREND(10, 3)}.
     */
    public static double[] supertrend(double[] high, double[] low,
                                      double[] close, int period, double mult) {
        requirePeriod(period);
        int n = close.length;
        double[] out = nanArray(n);
        double[] atr = atr(high, low, close, period);
        double fUpper = Double.NaN;
        double fLower = Double.NaN;
        boolean up = true;
        for (int i = period - 1; i < n; i++) {
            double mid = (high[i] + low[i]) / 2.0;
            double upper = mid + mult * atr[i];
            double lower = mid - mult * atr[i];
            if (Double.isNaN(fUpper)) { // first bar with ATR
                fUpper = upper;
                fLower = lower;
                up = close[i] >= mid;
            } else {
                // Band ratchet: bands only tighten unless price broke them.
                fUpper = (upper < fUpper || close[i - 1] > fUpper) ? upper : fUpper;
                fLower = (lower > fLower || close[i - 1] < fLower) ? lower : fLower;
                if (up && close[i] < fLower) {
                    up = false;
                } else if (!up && close[i] > fUpper) {
                    up = true;
                }
            }
            out[i] = up ? fLower : fUpper;
        }
        return out;
    }

    // ── Volume ──────────────────────────────────────────────────────────

    /** On-balance volume: cumulative signed volume, anchored at 0. */
    public static double[] obv(double[] close, double[] volume) {
        double[] out = new double[close.length];
        if (close.length == 0) {
            return out;
        }
        out[0] = 0;
        for (int i = 1; i < close.length; i++) {
            double sign = Math.signum(close[i] - close[i - 1]);
            out[i] = out[i - 1] + sign * volume[i];
        }
        return out;
    }

    // ── Channels & window stats ─────────────────────────────────────────

    public static double[] donchianUpper(double[] high, int period) {
        return windowExtreme(high, period, true);
    }

    public static double[] donchianLower(double[] low, int period) {
        return windowExtreme(low, period, false);
    }

    /** Highest value of a series over the window — with lookback, the
     *  breakout primitive: {@code CLOSE > HIGHEST(HIGH, 20)[1]}. */
    public static double[] highest(double[] src, int period) {
        return windowExtreme(src, period, true);
    }

    public static double[] lowest(double[] src, int period) {
        return windowExtreme(src, period, false);
    }

    /** Population standard deviation over the window (the Bollinger sigma,
     *  exposed directly). */
    public static double[] stddev(double[] src, int period) {
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
                out[i] = Math.sqrt(Math.max(0, sumSq / period - mean * mean));
            }
        }
        return out;
    }

    // ── Shared windowed helpers ─────────────────────────────────────────

    private static double[] windowExtreme(double[] src, int period, boolean max) {
        requirePeriod(period);
        double[] out = nanArray(src.length);
        for (int i = period - 1; i < src.length; i++) {
            double best = max ? Double.NEGATIVE_INFINITY : Double.POSITIVE_INFINITY;
            boolean valid = true;
            for (int j = i - period + 1; j <= i; j++) {
                if (Double.isNaN(src[j])) {
                    valid = false;
                    break;
                }
                best = max ? Math.max(best, src[j]) : Math.min(best, src[j]);
            }
            out[i] = valid ? best : Double.NaN;
        }
        return out;
    }

    /** SMA that yields NaN while its window still contains NaN — for
     *  smoothing already-NaN-prefixed series (stochD over stochK). */
    private static double[] smaNanTolerant(double[] src, int period) {
        requirePeriod(period);
        double[] out = nanArray(src.length);
        for (int i = period - 1; i < src.length; i++) {
            double sum = 0;
            boolean valid = true;
            for (int j = i - period + 1; j <= i; j++) {
                if (Double.isNaN(src[j])) {
                    valid = false;
                    break;
                }
                sum += src[j];
            }
            out[i] = valid ? sum / period : Double.NaN;
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