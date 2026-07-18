package com.tsb.execution;

import java.util.List;

/**
 * Everything one backtest produced. The equity curve is per-bar
 * marked-to-market (cash + position at that bar's close), so drawdowns in
 * OPEN positions are visible — a result format that only shows realized
 * trades hides exactly the pain a user needs to see.
 *
 * @param equityCurve equity at every bar close, length = series size
 * @param maxDrawdownPct worst peak-to-trough on the equity curve, percent
 */
public record BacktestResult(
        double initialCapital,
        double finalEquity,
        double[] equityCurve,
        List<Trade> trades,
        int warmupBars,
        int barsProcessed,
        double maxDrawdownPct
) {

    public double totalReturnPct() {
        return (finalEquity - initialCapital) / initialCapital * 100.0;
    }

    public double winRate() {
        if (trades.isEmpty()) {
            return 0;
        }
        long wins = trades.stream().filter(Trade::isWin).count();
        return (double) wins / trades.size() * 100.0;
    }

    public double totalFees() {
        return trades.stream().mapToDouble(Trade::fees).sum();
    }
}