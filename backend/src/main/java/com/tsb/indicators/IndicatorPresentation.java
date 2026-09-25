package com.tsb.indicators;

import java.util.List;
import java.util.Map;

/**
 * How each indicator should look on a chart.
 *
 * <p>The {@link com.tsb.compiler.Registry} is the single source of truth for
 * what exists and what arguments it takes; it deliberately knows nothing about
 * presentation. This table adds only the things a chart needs and a compiler
 * does not: a human label, sensible starting values, whether the line belongs
 * on the price scale or in its own pane, and any reference levels.
 *
 * <p>No formula lives here, and no parameter is invented here — the parameter
 * list still comes from the registry. That matters: this table cannot make the
 * chart disagree with the engine about a value, only about a colour.
 *
 * <p>{@code IndicatorPresentationTest} asserts that every precomputed registry
 * name has an entry and that nothing here names an indicator the registry does
 * not have. Same drift guard {@code IndicatorBank} already relies on.
 */
public final class IndicatorPresentation {

    /** Where the line goes. */
    public enum Placement {
        /** Same vertical scale as the candles: moving averages, bands, VWAP. */
        PRICE,
        /**
         * Its own pane below. An RSI of 70 drawn against a Bitcoin price axis
         * is a flat line pinned to the bottom of the chart.
         */
        SEPARATE
    }

    /**
     * @param label        what the picker shows
     * @param placement    price scale or own pane
     * @param defaultSource default for the PRICE_SERIES parameter, or null
     *                      when the indicator has none
     * @param defaults     starting values for the CONST_NUMBER parameters, in
     *                     declaration order
     * @param scaleMin     fixed lower bound for a separate pane, or null
     * @param scaleMax     fixed upper bound, or null
     * @param guides       horizontal reference levels, e.g. RSI's 30 and 70
     * @param companions   other indicators to add alongside this one — a
     *                     Bollinger upper band on its own looks broken
     */
    public record Look(
            String label,
            Placement placement,
            String defaultSource,
            List<Double> defaults,
            Double scaleMin,
            Double scaleMax,
            List<Double> guides,
            List<String> companions) {
    }

    private static Look price(String label, String source, Double... defaults) {
        return new Look(label, Placement.PRICE, source, List.of(defaults),
                null, null, List.of(), List.of());
    }

    private static Look pane(String label, Double... defaults) {
        return new Look(label, Placement.SEPARATE, null, List.of(defaults),
                null, null, List.of(), List.of());
    }

    private static Look bounded(String label, double min, double max,
                                List<Double> guides, Double... defaults) {
        return new Look(label, Placement.SEPARATE, null, List.of(defaults),
                min, max, guides, List.of());
    }

    private static Look with(Look base, List<String> companions) {
        return new Look(base.label(), base.placement(), base.defaultSource(),
                base.defaults(), base.scaleMin(), base.scaleMax(),
                base.guides(), companions);
    }

    static final Map<String, Look> TABLE = Map.ofEntries(
            /* ── On the price scale ─────────────────────────────────────── */
            Map.entry("SMA", price("Simple Moving Average", "CLOSE", 20.0)),
            Map.entry("EMA", price("Exponential Moving Average", "CLOSE", 21.0)),
            Map.entry("WMA", price("Weighted Moving Average", "CLOSE", 20.0)),
            Map.entry("HMA", price("Hull Moving Average", "CLOSE", 21.0)),
            Map.entry("VWAP", price("Volume Weighted Average Price", null)),

            // The two bands are separate registry names, so picking one adds
            // the other. A single band tells you almost nothing.
            Map.entry("BB_UPPER", with(
                    price("Bollinger Band (upper)", "CLOSE", 20.0, 2.0),
                    List.of("BB_LOWER"))),
            Map.entry("BB_LOWER", with(
                    price("Bollinger Band (lower)", "CLOSE", 20.0, 2.0),
                    List.of("BB_UPPER"))),

            Map.entry("DONCHIAN_UPPER", with(
                    price("Donchian Channel (upper)", null, 20.0),
                    List.of("DONCHIAN_LOWER"))),
            Map.entry("DONCHIAN_LOWER", with(
                    price("Donchian Channel (lower)", null, 20.0),
                    List.of("DONCHIAN_UPPER"))),

            Map.entry("HIGHEST", price("Highest High", "HIGH", 20.0)),
            Map.entry("LOWEST", price("Lowest Low", "LOW", 20.0)),
            Map.entry("SUPERTREND", price("SuperTrend", null, 10.0, 3.0)),

            /* ── Own pane, bounded 0..100 ───────────────────────────────── */
            Map.entry("RSI", bounded("Relative Strength Index",
                    0, 100, List.of(30.0, 70.0), 14.0)),
            Map.entry("STOCH_K", with(
                    bounded("Stochastic %K", 0, 100, List.of(20.0, 80.0), 14.0),
                    List.of("STOCH_D"))),
            Map.entry("STOCH_D", with(
                    bounded("Stochastic %D", 0, 100, List.of(20.0, 80.0), 14.0, 3.0),
                    List.of("STOCH_K"))),
            Map.entry("MFI", bounded("Money Flow Index",
                    0, 100, List.of(20.0, 80.0), 14.0)),
            Map.entry("ADX", bounded("Average Directional Index",
                    0, 100, List.of(25.0), 14.0)),
            Map.entry("PLUS_DI", with(
                    bounded("Directional Indicator +DI", 0, 100, List.of(), 14.0),
                    List.of("MINUS_DI"))),
            Map.entry("MINUS_DI", with(
                    bounded("Directional Indicator -DI", 0, 100, List.of(), 14.0),
                    List.of("PLUS_DI"))),

            // Williams %R runs -100..0, not 0..100.
            Map.entry("WILLR", bounded("Williams %R",
                    -100, 0, List.of(-80.0, -20.0), 14.0)),

            /* ── Own pane, unbounded ────────────────────────────────────── */
            Map.entry("MACD_LINE", with(
                    new Look("MACD Line", Placement.SEPARATE, null,
                            List.of(12.0, 26.0), null, null, List.of(0.0),
                            List.of()),
                    List.of("MACD_SIGNAL"))),
            Map.entry("MACD_SIGNAL", with(
                    new Look("MACD Signal", Placement.SEPARATE, null,
                            List.of(12.0, 26.0, 9.0), null, null, List.of(0.0),
                            List.of()),
                    List.of("MACD_LINE"))),

            Map.entry("ATR", pane("Average True Range", 14.0)),
            Map.entry("CCI", new Look("Commodity Channel Index",
                    Placement.SEPARATE, null, List.of(20.0), null, null,
                    List.of(-100.0, 0.0, 100.0), List.of())),
            Map.entry("ROC", new Look("Rate of Change", Placement.SEPARATE,
                    "CLOSE", List.of(12.0), null, null, List.of(0.0), List.of())),
            Map.entry("MOM", new Look("Momentum", Placement.SEPARATE,
                    "CLOSE", List.of(10.0), null, null, List.of(0.0), List.of())),
            Map.entry("STDDEV", new Look("Standard Deviation",
                    Placement.SEPARATE, "CLOSE", List.of(20.0), null, null,
                    List.of(), List.of())),
            Map.entry("OBV", pane("On-Balance Volume"))
    );

    private IndicatorPresentation() {
    }
}