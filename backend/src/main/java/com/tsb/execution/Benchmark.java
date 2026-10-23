package com.tsb.execution;

import com.tsb.marketdata.CandleSeries;

/**
 * Buy &amp; hold: the line every strategy has to beat. Buys with all the
 * capital at the open after the first candle a rule can run on — exactly
 * when a {@code BUY ALL} on that candle would fill — and holds to the end,
 * paying the strategy's own fee both ways. Same candles, same costs.
 *
 * <p>One place for it, so the headline number, the line on the chart and the
 * candle panel's "the market over the same stretch" always agree. (The chart
 * used to draw its own from whatever candles it had loaded: from the first
 * candle instead of after the warm-up, without fees, and from a different
 * start altogether when a long 5-minute range was cut to its newest 20,000
 * candles.)
 */
public final class Benchmark {

    private Benchmark() {
    }

    /** The candle whose open buy &amp; hold buys at, or -1 if there is none. */
    public static int entryBar(CandleSeries series, int warmupBars) {
        int entry = warmupBars + 1;
        return entry < series.size() ? entry : -1;
    }

    /**
     * Buy &amp; hold's value at every close: the capital until it buys, then
     * the coins at each close, and at the last close what selling them
     * returns after the fee. Null when it never gets to buy.
     */
    public static double[] curve(CandleSeries series, int warmupBars, double capital,
                                 double fee) {
        int entry = entryBar(series, warmupBars);
        if (entry < 0) {
            return null;
        }
        int n = series.size();
        double coins = capital / (series.open()[entry] * (1 + fee));
        double[] out = new double[n];
        for (int i = 0; i < n; i++) {
            out[i] = i < entry ? capital : coins * series.close()[i];
        }
        out[n - 1] = coins * series.close()[n - 1] * (1 - fee);
        return out;
    }

    /** Return to the end, percent; null when it never gets to buy. */
    public static Double returnPct(CandleSeries series, int warmupBars, double fee) {
        int entry = entryBar(series, warmupBars);
        if (entry < 0) {
            return null;
        }
        double buy = series.open()[entry] * (1 + fee);
        double sell = series.close()[series.size() - 1] * (1 - fee);
        return (sell / buy - 1) * 100.0;
    }

    /** Return up to one candle's close, percent (0 before it buys); the last
     *  candle includes the selling fee, like {@link #returnPct}. Null when it
     *  never gets to buy. */
    public static Double returnPctAt(CandleSeries series, int warmupBars, double fee, int bar) {
        int entry = entryBar(series, warmupBars);
        if (entry < 0) {
            return null;
        }
        if (bar < entry) {
            return 0.0;
        }
        double buy = series.open()[entry] * (1 + fee);
        double value = series.close()[bar] * (bar == series.size() - 1 ? 1 - fee : 1);
        return (value / buy - 1) * 100.0;
    }
}
