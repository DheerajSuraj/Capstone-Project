package com.tsb.execution;

import java.util.Arrays;
import java.util.List;

/**
 * The performance measures TradAO uses to compare trading algorithms (Tsang
 * et al., "TradAO: A Visual Analytics System for Trading Algorithm
 * Optimization", IEEE VIS 2020), in its six categories:
 *
 * <table>
 *   <tr><td>Profitability</td><td>Yield (mean annualized return), MD (max drawdown)</td></tr>
 *   <tr><td>Consistency</td><td>Sharpe, Sortino — in {@link Metrics}</td></tr>
 *   <tr><td>Prediction</td><td>Win rate — in {@link BacktestResult}</td></tr>
 *   <tr><td>Recovery</td><td>MaxDD and AvgDD duration</td></tr>
 *   <tr><td>Robustness</td><td>VaR99 (1-day), Vol (annualized)</td></tr>
 *   <tr><td>Activeness</td><td>trades per 30 days, time in the market</td></tr>
 * </table>
 *
 * <p>The paper names no measure for Activeness ("how frequently an algorithm
 * executes transactions"); trades per 30 days and the share of time holding
 * a position are our reading of it.
 *
 * <p><b>Daily, 365 days a year.</b> Yield, volatility and VaR are worked out
 * on daily returns — the equity at each UTC day's last close — whatever the
 * strategy's candle size, so a 5-minute and a 4-hour strategy are measured
 * the same way. Crypto trades every day, so a year is 365 of them, not the
 * 252 trading days of a stock market.
 *
 * <p>Undefined values (fewer than two days, no days at all) are NaN; the API
 * layer turns them into null rather than inventing a number.
 *
 * @param yieldPct        mean daily return x 365, percent
 * @param maxDrawdownPct  worst fall from a peak, percent
 * @param maxDrawdownDays longest time from a peak until equity was back at
 *                        it (or the end, if it never was), days
 * @param avgDrawdownDays mean length of all such periods, days (0 if none)
 * @param var99Pct        1-day 99% value at risk: on 99% of days the loss
 *                        was smaller than this, percent (historical; with
 *                        fewer than 100 days it is simply the worst day)
 * @param volatilityPct   standard deviation of daily returns x sqrt(365), percent
 * @param tradesPer30Days completed trades per 30 days
 * @param timeInMarketPct share of candles with a position open, percent
 * @param days            daily returns measured
 */
public record PerformanceMeasures(
        double yieldPct,
        double maxDrawdownPct,
        double maxDrawdownDays,
        double avgDrawdownDays,
        double var99Pct,
        double volatilityPct,
        double tradesPer30Days,
        double timeInMarketPct,
        int days
) {

    static final long DAY_MS = 86_400_000L;

    /**
     * @param times    candle open times, epoch ms
     * @param equity   equity at each close
     * @param held     coins held at each close
     * @param firstBar first candle the rules ran on (the warm-up before it
     *                 is flat capital and is not measured)
     * @param barMs    length of one candle, ms
     */
    public static PerformanceMeasures compute(long[] times, double[] equity, double[] held,
                                              int firstBar, List<Trade> trades, long barMs) {
        int n = equity.length;
        int from = Math.max(0, firstBar);
        if (from >= n) {
            return new PerformanceMeasures(Double.NaN, 0, 0, 0, Double.NaN, Double.NaN,
                    0, 0, 0);
        }

        // ── Daily returns ───────────────────────────────────────────────
        double before = from > 0 ? equity[from - 1] : equity[from];
        double[] daily = new double[n - from + 1];
        int days = 0;
        long day = Math.floorDiv(times[from], DAY_MS);
        double prev = before;
        for (int i = from; i < n; i++) {
            boolean lastOfDay = i == n - 1 || Math.floorDiv(times[i + 1], DAY_MS) != day;
            if (lastOfDay) {
                if (prev > 0) {
                    daily[days++] = equity[i] / prev - 1;
                }
                prev = equity[i];
                if (i + 1 < n) {
                    day = Math.floorDiv(times[i + 1], DAY_MS);
                }
            }
        }
        double mean = 0;
        for (int d = 0; d < days; d++) {
            mean += daily[d];
        }
        mean = days > 0 ? mean / days : Double.NaN;
        double var = 0;
        for (int d = 0; d < days; d++) {
            var += (daily[d] - mean) * (daily[d] - mean);
        }
        double vol = days > 1 ? Math.sqrt(var / (days - 1)) * Math.sqrt(365) * 100 : Double.NaN;
        double var99 = Double.NaN;
        if (days > 0) {
            double[] sorted = Arrays.copyOf(daily, days);
            Arrays.sort(sorted);
            var99 = -sorted[(int) Math.floor(0.01 * days)] * 100 + 0.0; // + 0.0: no "-0"
        }

        // ── Drawdowns and how long they lasted ─────────────────────────
        double peak = equity[from];
        long peakTime = times[from];
        double maxDd = 0;
        boolean under = false;
        double longest = 0;
        double totalLength = 0;
        int periods = 0;
        for (int i = from; i < n; i++) {
            if (equity[i] >= peak) {
                if (under) {
                    double length = (times[i] - peakTime) / (double) DAY_MS;
                    longest = Math.max(longest, length);
                    totalLength += length;
                    periods++;
                    under = false;
                }
                peak = equity[i];
                peakTime = times[i];
            } else {
                under = true;
                if (peak > 0) {
                    maxDd = Math.max(maxDd, (peak - equity[i]) / peak * 100);
                }
            }
        }
        if (under) { // still below the peak when the data ends
            double length = (times[n - 1] + barMs - peakTime) / (double) DAY_MS;
            longest = Math.max(longest, length);
            totalLength += length;
            periods++;
        }

        // ── Activeness ──────────────────────────────────────────────────
        int bars = n - from;
        int inMarket = 0;
        for (int i = from; i < n; i++) {
            if (held[i] > 0) {
                inMarket++;
            }
        }
        double spanDays = bars * (double) barMs / DAY_MS;
        double perMonth = spanDays > 0 ? trades.size() * 30.0 / spanDays : 0;

        return new PerformanceMeasures(mean * 365 * 100, maxDd, longest,
                periods > 0 ? totalLength / periods : 0, var99, vol, perMonth,
                inMarket * 100.0 / bars, days);
    }
}
