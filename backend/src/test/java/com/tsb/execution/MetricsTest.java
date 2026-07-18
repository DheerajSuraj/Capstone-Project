package com.tsb.execution;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Hand-computed metric verification — the arithmetic for every expected
 * value is in the comments, small enough to redo on paper.
 */
@DisplayName("Metrics")
class MetricsTest {

    private static Trade trade(double pnl) {
        return new Trade(0, 1, Instant.EPOCH, Instant.EPOCH,
                1, 100, 100 + pnl, 0, pnl,
                Trade.ExitReason.SIGNAL);
    }

    @Test
    @DisplayName("hand-computed Sharpe & Sortino on returns +10%, -10%, +10%")
    void handComputedRatios() {
        // equity 100 -> 110 -> 99 -> 108.9 ; returns r = .10, -.10, .10
        // mean = .0333...; sample var = ((.0667)^2 + (.1333)^2 + (.0667)^2)/2
        //      = (.004444 + .017778 + .004444)/2 = .013333 ; std = .115470
        // Sharpe (barsPerYear=1) = .033333/.115470 = .288675
        // downside dev = sqrt((0 + .01 + 0)/3) = .057735
        // Sortino = .033333/.057735 = .577350
        Metrics m = Metrics.compute(
                new double[]{100, 110, 99, 108.9}, 0, List.of(), 1.0);
        assertEquals(0.2886751346, m.sharpeRatio(), 1e-9);
        assertEquals(0.5773502692, m.sortinoRatio(), 1e-9);
    }

    @Test
    @DisplayName("annualization multiplies by sqrt(barsPerYear)")
    void annualization() {
        Metrics perBar = Metrics.compute(
                new double[]{100, 110, 99, 108.9}, 0, List.of(), 1.0);
        Metrics annual = Metrics.compute(
                new double[]{100, 110, 99, 108.9}, 0, List.of(), 8760.0);
        assertEquals(perBar.sharpeRatio() * Math.sqrt(8760),
                annual.sharpeRatio(), 1e-9);
    }

    @Test
    @DisplayName("the flat warm-up prefix is excluded from volatility")
    void warmupExcluded() {
        // Same three returns, but with a flat 5-bar prefix. If the prefix
        // leaked in as zero-returns it would depress the mean and std;
        // firstBar=5 must make this identical to the prefix-free case.
        double[] withPrefix = {100, 100, 100, 100, 100, 100, 110, 99, 108.9};
        Metrics m = Metrics.compute(withPrefix, 5, List.of(), 1.0);
        assertEquals(0.2886751346, m.sharpeRatio(), 1e-9);
    }

    @Test
    @DisplayName("crypto bars per year: 24/7, not stock-market 252 days")
    void barsPerYear() {
        assertEquals(8760.0, Metrics.barsPerYear("1h"), 1e-9);
        assertEquals(105120.0, Metrics.barsPerYear("5m"), 1e-9);
        assertEquals(2190.0, Metrics.barsPerYear("4h"), 1e-9);
    }

    @Test
    @DisplayName("profit factor: gross wins over gross losses, hand-checked")
    void profitFactor() {
        // wins 30 + 10 = 40 gross; losses 20 gross -> PF 2.0
        Metrics m = Metrics.compute(new double[]{100, 101, 102}, 0,
                List.of(trade(30), trade(-20), trade(10)), 1.0);
        assertEquals(2.0, m.profitFactor(), 1e-9);
        assertEquals(20.0 / 3, m.avgTradePnl(), 1e-9);
        assertEquals(30.0, m.bestTradePnl(), 1e-9);
        assertEquals(-20.0, m.worstTradePnl(), 1e-9);
    }

    @Test
    @DisplayName("no losing trades -> profit factor is honestly infinite")
    void infinitePf() {
        Metrics m = Metrics.compute(new double[]{100, 101, 102}, 0,
                List.of(trade(30), trade(10)), 1.0);
        assertTrue(Double.isInfinite(m.profitFactor()));
    }

    @Test
    @DisplayName("no trades / too few bars -> NaN, never a fabricated zero")
    void undefinedIsNaN() {
        Metrics m = Metrics.compute(new double[]{100}, 0, List.of(), 1.0);
        assertTrue(Double.isNaN(m.sharpeRatio()));
        assertTrue(Double.isNaN(m.profitFactor()));
        assertTrue(Double.isNaN(m.avgTradePnl()));
    }
}