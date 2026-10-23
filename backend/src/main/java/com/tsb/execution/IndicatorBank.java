package com.tsb.execution;

import com.tsb.compiler.CompiledStrategy;
import com.tsb.compiler.ConstFold;
import com.tsb.compiler.Expr;
import com.tsb.compiler.Registry;
import com.tsb.compiler.Timeframe;
import com.tsb.marketdata.CandleSeries;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * Phase 1 of the two-phase execution model (roadmap §8): compute every
 * indicator in the strategy's manifest EXACTLY ONCE over the whole candle
 * series, before any bar executes. The result is a map from canonical key
 * ({@code "RSI(14)"}, {@code "SMA(CLOSE,200)"}) to an aligned
 * {@code double[]} — after this, the per-bar interpreter never computes an
 * indicator; it indexes an array.
 *
 * <p>This is where the three subsystems meet: the COMPILER's manifest says
 * what to compute, the DATA layer's columnar series says what to compute
 * over, and the maths lives in {@link Indicators}. The keys are generated
 * by {@link CompiledStrategy.IndicatorInstance#key()} on both sides —
 * analyzer at compile time, {@link #instanceFor} at execution time — using
 * the same {@link ConstFold}, so they agree by construction.
 */
public final class IndicatorBank {

    /** Everything a compiled strategy reads, over its own timeframe's
     *  candles. The one entry point the engines use. */
    public static Map<String, double[]> compute(CompiledStrategy strategy,
                                                CandleSeries series) {
        return compute(strategy.indicators(), series, strategy.timeframe());
    }

    /** For manifests with no bigger timeframe in them. */
    public static Map<String, double[]> compute(
            Set<CompiledStrategy.IndicatorInstance> manifest,
            CandleSeries series) {
        return compute(manifest, series, null);
    }

    /**
     * Computes every manifest entry. Throws if the registry and this
     * dispatch ever drift — a new indicator must be added in BOTH places,
     * and the registry-consistency test enforces it.
     *
     * <p>An entry on a bigger timeframe ({@code EMA(CLOSE,50) ON 4h}) is
     * computed on bigger candles built from {@code series} by the
     * {@link Resampler}, then lined up with the small candles: each small
     * candle gets the value of the last bigger candle that had closed when
     * it closed, so it can never see a bigger candle still forming. For the
     * debugger, the bank also files, per bigger timeframe, the open time of
     * that visible candle under {@link #visibleCandleKey}.
     *
     * @param ownTimeframe the timeframe of {@code series}; needed only when
     *                     the manifest has entries on a bigger timeframe
     */
    public static Map<String, double[]> compute(
            Set<CompiledStrategy.IndicatorInstance> manifest,
            CandleSeries series, String ownTimeframe) {
        Map<String, double[]> bank = new HashMap<>();
        Map<String, List<CompiledStrategy.IndicatorInstance>> bigger = new TreeMap<>();
        for (CompiledStrategy.IndicatorInstance instance : manifest) {
            if (instance.timeframe() == null) {
                bank.put(instance.key(), computeOne(instance, series));
            } else {
                bigger.computeIfAbsent(instance.timeframe(), k -> new ArrayList<>())
                        .add(instance);
            }
        }
        if (bigger.isEmpty()) {
            return bank;
        }
        long ownMillis = Timeframe.millis(ownTimeframe).orElseThrow(() ->
                new IllegalArgumentException("indicators on a bigger timeframe need "
                        + "the candles' own timeframe, got " + ownTimeframe));
        for (Map.Entry<String, List<CompiledStrategy.IndicatorInstance>> e : bigger.entrySet()) {
            String tf = e.getKey();
            long bigMillis = Timeframe.millis(tf).orElseThrow();
            if (bigMillis <= ownMillis || bigMillis % ownMillis != 0) {
                throw new IllegalArgumentException(tf + " is not a bigger multiple of "
                        + ownTimeframe);
            }
            Resampler.Result big = Resampler.resample(series, ownMillis, tf, bigMillis);
            int[] visible = Resampler.visible(big, series, ownMillis);

            double[] when = new double[series.size()];
            for (int i = 0; i < when.length; i++) {
                when[i] = visible[i] < 0 ? Double.NaN
                        : big.candles().openTimeMillis()[visible[i]];
            }
            bank.put(visibleCandleKey(tf), when);

            for (CompiledStrategy.IndicatorInstance instance : e.getValue()) {
                double[] values = big.size() == 0 ? new double[0]
                        : instance.name().equals(CompiledStrategy.IndicatorInstance.PRICE)
                        ? column(big.candles(), instance.source())
                        : computeOne(instance.onOwnCandles(), big.candles());
                double[] aligned = new double[series.size()];
                for (int i = 0; i < aligned.length; i++) {
                    int j = visible[i] - instance.offset();
                    aligned[i] = j >= 0 ? values[j] : Double.NaN;
                }
                bank.put(instance.key(), aligned);
            }
        }
        return bank;
    }

    /** Bank key holding, per small candle, the open time (epoch ms) of the
     *  {@code timeframe} candle it can see — NaN before the first one. */
    public static String visibleCandleKey(String timeframe) {
        return "@" + timeframe;
    }

    static double[] computeOne(CompiledStrategy.IndicatorInstance instance,
                               CandleSeries s) {
        List<Double> a = instance.constArgs();
        return switch (instance.name()) {
            case "RSI" -> Indicators.rsi(s.close(), a.get(0).intValue());
            case "SMA" -> Indicators.sma(column(s, instance.source()),
                    a.get(0).intValue());
            case "EMA" -> Indicators.ema(column(s, instance.source()),
                    a.get(0).intValue());
            case "ATR" -> Indicators.atr(s.high(), s.low(), s.close(),
                    a.get(0).intValue());
            case "VWAP" -> Indicators.vwap(s.openTimeMillis(), s.high(),
                    s.low(), s.close(), s.volume());
            case "BB_UPPER" -> Indicators.bbUpper(column(s, instance.source()),
                    a.get(0).intValue(), a.get(1));
            case "BB_LOWER" -> Indicators.bbLower(column(s, instance.source()),
                    a.get(0).intValue(), a.get(1));
            case "MACD_LINE" -> Indicators.macdLine(s.close(),
                    a.get(0).intValue(), a.get(1).intValue());
            case "MACD_SIGNAL" -> Indicators.macdSignal(s.close(),
                    a.get(0).intValue(), a.get(1).intValue(),
                    a.get(2).intValue());

            // ── Expansion set ───────────────────────────────────────────
            case "STOCH_K" -> Indicators.stochK(s.high(), s.low(), s.close(),
                    a.get(0).intValue());
            case "STOCH_D" -> Indicators.stochD(s.high(), s.low(), s.close(),
                    a.get(0).intValue(), a.get(1).intValue());
            case "WILLR" -> Indicators.willr(s.high(), s.low(), s.close(),
                    a.get(0).intValue());
            case "CCI" -> Indicators.cci(s.high(), s.low(), s.close(),
                    a.get(0).intValue());
            case "MFI" -> Indicators.mfi(s.high(), s.low(), s.close(),
                    s.volume(), a.get(0).intValue());
            case "ROC" -> Indicators.roc(column(s, instance.source()),
                    a.get(0).intValue());
            case "MOM" -> Indicators.mom(column(s, instance.source()),
                    a.get(0).intValue());
            case "ADX" -> Indicators.adx(s.high(), s.low(), s.close(),
                    a.get(0).intValue());
            case "PLUS_DI" -> Indicators.plusDi(s.high(), s.low(), s.close(),
                    a.get(0).intValue());
            case "MINUS_DI" -> Indicators.minusDi(s.high(), s.low(), s.close(),
                    a.get(0).intValue());
            case "SUPERTREND" -> Indicators.supertrend(s.high(), s.low(),
                    s.close(), a.get(0).intValue(), a.get(1));
            case "WMA" -> Indicators.wma(column(s, instance.source()),
                    a.get(0).intValue());
            case "HMA" -> Indicators.hma(column(s, instance.source()),
                    a.get(0).intValue());
            case "DONCHIAN_UPPER" -> Indicators.donchianUpper(s.high(),
                    a.get(0).intValue());
            case "DONCHIAN_LOWER" -> Indicators.donchianLower(s.low(),
                    a.get(0).intValue());
            case "STDDEV" -> Indicators.stddev(column(s, instance.source()),
                    a.get(0).intValue());
            case "HIGHEST" -> Indicators.highest(column(s, instance.source()),
                    a.get(0).intValue());
            case "LOWEST" -> Indicators.lowest(column(s, instance.source()),
                    a.get(0).intValue());
            case "OBV" -> Indicators.obv(s.close(), s.volume());
            default -> throw new IllegalStateException(
                    "indicator '" + instance.name() + "' is in the registry "
                            + "but not in IndicatorBank — add it here too");
        };
    }

    /** The manifest instance for {@code X ON tf} — the execution-time twin
     *  of what the Analyzer registered for it. */
    public static CompiledStrategy.IndicatorInstance instanceFor(Expr.On on) {
        Expr core = on.target();
        int offset = 0;
        if (core instanceof Expr.Lookback l) {
            core = l.target();
            offset = l.offset();
        }
        if (core instanceof Expr.PriceRef p) {
            return new CompiledStrategy.IndicatorInstance(
                    CompiledStrategy.IndicatorInstance.PRICE, p.field(), List.of(),
                    on.timeframe(), offset);
        }
        if (core instanceof Expr.Call c) {
            int k = offset;
            return instanceFor(c).map(i -> i.on(on.timeframe(), k))
                    .orElseThrow(() -> new IllegalStateException(
                            "ON on a non-indicator survived the analyzer: " + c.name()));
        }
        throw new IllegalStateException("ON target survived the analyzer: " + core);
    }

    /** Rebuilds the manifest-style instance for a Call node — the
     *  execution-time twin of what the Analyzer did at compile time.
     *  Empty for functions (CROSSOVER, MAX...): those are interpreted per
     *  bar, never precomputed. */
    public static Optional<CompiledStrategy.IndicatorInstance> instanceFor(
            Expr.Call call) {
        Optional<Registry.Signature> sig = Registry.lookup(call.name());
        if (sig.isEmpty() || !sig.get().precomputed()) {
            return Optional.empty();
        }
        Expr.PriceField source = null;
        List<Double> constArgs = new ArrayList<>();
        for (int i = 0; i < sig.get().arity(); i++) {
            Expr arg = call.args().get(i);
            switch (sig.get().params().get(i).kind()) {
                case PRICE_SERIES -> source = ((Expr.PriceRef) arg).field();
                case CONST_NUMBER -> constArgs.add(ConstFold.fold(arg)
                        .orElseThrow(() -> new IllegalStateException(
                                "non-constant indicator arg survived the "
                                        + "analyzer: " + call.name())));
                case NUMERIC -> {
                    // functions only; unreachable for precomputed
                }
            }
        }
        return Optional.of(new CompiledStrategy.IndicatorInstance(
                call.name(), source, List.copyOf(constArgs)));
    }

    static double[] column(CandleSeries s, Expr.PriceField field) {
        return switch (field) {
            case OPEN -> s.open();
            case HIGH -> s.high();
            case LOW -> s.low();
            case CLOSE -> s.close();
            case VOLUME -> s.volume();
        };
    }

    private IndicatorBank() {
    }
}