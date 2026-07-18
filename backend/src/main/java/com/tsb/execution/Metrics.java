package com.tsb.execution;

import java.util.List;

/**
 * Risk-adjusted performance metrics over a completed backtest. Pure static
 * math like {@link Indicators} — and like them, hand-verified in tests,
 * because a subtly wrong Sharpe silently mis-ranks every competition.
 *
 * <p><b>Annualization is crypto-correct:</b> markets here trade 24/7, so a
 * 1h timeframe has 8760 bars/year — NOT the 252-trading-days arithmetic of
 * equity markets. Using stock-market constants on crypto data understates
 * annualized figures by ~2x and is a classic copied-formula bug.
 *
 * <p>Undefined values (zero volatility, no losing trades) are returned as
 * NaN/Infinity honestly; the API layer maps non-finite to JSON null rather
 * than inventing a number.
 *
 * @param sharpeRatio   annualized mean/stddev of per-bar returns (rf = 0)
 * @param sortinoRatio  like Sharpe but only downside deviation penalizes
 * @param profitFactor  gross profit / gross loss across trades
 * @param avgTradePnl   mean net PnL per trade, quote currency
 * @param bestTradePnl  largest single-trade net PnL
 * @param worstTradePnl most negative single-trade net PnL
 */
public record Metrics(
        double sharpeRatio,
        double sortinoRatio,
        double profitFactor,
        double avgTradePnl,
        double bestTradePnl,
        double worstTradePnl
) {

    /** Bars per year for a timeframe in a 24/7 market. */
    public static double barsPerYear(String timeframe) {
        return switch (timeframe) {
            case "1m" -> 365.0 * 24 * 60;
            case "5m" -> 365.0 * 24 * 12;
            case "15m" -> 365.0 * 24 * 4;
            case "1h" -> 365.0 * 24;
            case "4h" -> 365.0 * 6;
            case "1d" -> 365.0;
            default -> throw new IllegalArgumentException(
                    "no annualization factor for timeframe " + timeframe);
        };
    }

    /**
     * @param equity   full-resolution equity curve
     * @param firstBar first executed bar (warm-up prefix is flat capital
     *                 and must not dilute the volatility estimate)
     */
    public static Metrics compute(double[] equity, int firstBar,
                                  List<Trade> trades, double barsPerYear) {
        // ── Per-bar simple returns over the executed region ─────────────
        int n = equity.length;
        int count = 0;
        double sum = 0;
        for (int i = firstBar + 1; i < n; i++) {
            if (equity[i - 1] > 0) {
                sum += equity[i] / equity[i - 1] - 1;
                count++;
            }
        }
        double sharpe = Double.NaN;
        double sortino = Double.NaN;
        if (count >= 2) {
            double mean = sum / count;
            double varSum = 0;
            double downSum = 0;
            for (int i = firstBar + 1; i < n; i++) {
                if (equity[i - 1] > 0) {
                    double r = equity[i] / equity[i - 1] - 1;
                    varSum += (r - mean) * (r - mean);
                    double down = Math.min(r, 0);
                    downSum += down * down;
                }
            }
            double std = Math.sqrt(varSum / (count - 1)); // sample stddev
            double downsideDev = Math.sqrt(downSum / count);
            double annual = Math.sqrt(barsPerYear);
            sharpe = std == 0 ? Double.NaN : mean / std * annual;
            sortino = downsideDev == 0
                    ? (mean > 0 ? Double.POSITIVE_INFINITY : Double.NaN)
                    : mean / downsideDev * annual;
        }

        // ── Trade-level metrics ─────────────────────────────────────────
        double grossWin = 0;
        double grossLoss = 0;
        double best = Double.NaN;
        double worst = Double.NaN;
        double pnlSum = 0;
        for (Trade t : trades) {
            pnlSum += t.pnl();
            if (t.pnl() >= 0) {
                grossWin += t.pnl();
            } else {
                grossLoss += -t.pnl();
            }
            best = Double.isNaN(best) ? t.pnl() : Math.max(best, t.pnl());
            worst = Double.isNaN(worst) ? t.pnl() : Math.min(worst, t.pnl());
        }
        double profitFactor = trades.isEmpty() ? Double.NaN
                : grossLoss == 0
                ? (grossWin > 0 ? Double.POSITIVE_INFINITY : Double.NaN)
                : grossWin / grossLoss;
        double avgTrade = trades.isEmpty() ? Double.NaN
                : pnlSum / trades.size();

        return new Metrics(sharpe, sortino, profitFactor, avgTrade, best, worst);
    }
}