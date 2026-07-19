package com.tsb.compiler;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.ToIntFunction;
import java.util.stream.Collectors;

/**
 * The single source of truth for every callable name in TSL: the seven MVP
 * indicators and the built-in functions. The semantic analyzer resolves
 * {@link Expr.Call} nodes against this table; nothing else in the compiler
 * knows indicator names. Adding indicator #8 is ONE entry here (plus its
 * Java implementation in the engine) — no lexer, parser, or analyzer changes.
 *
 * <p><b>Indicators vs functions:</b> indicators ({@code precomputed=true})
 * are computed once over the whole candle series by the engine's precompute
 * phase — which is why their non-series parameters must be compile-time
 * constants. Functions (CROSSOVER, MAX, ...) are evaluated per bar by the
 * interpreter and never enter the indicator manifest.
 *
 * <p>Multi-output indicators (MACD, Bollinger) are exposed as separate
 * single-output names (MACD_LINE / MACD_SIGNAL, BB_UPPER / BB_LOWER): one
 * name = one {@code double[]} in the engine, which keeps the manifest, the
 * cache keys, and the chart payloads all trivially flat.
 */
public final class Registry {

    /** What a parameter accepts. */
    public enum ParamKind {
        /** A raw price series: OPEN/HIGH/LOW/CLOSE/VOLUME. (MVP restriction:
         *  indicator-of-indicator is deliberately not supported yet.) */
        PRICE_SERIES,
        /** Any numeric expression, evaluated per bar by the interpreter. */
        NUMERIC,
        /** A compile-time constant number — required because indicators are
         *  precomputed before any bar runs. */
        CONST_NUMBER
    }

    /** One parameter: its kind, a name for error messages, and whether it
     *  must be a positive whole number (periods) or merely a number (e.g.
     *  the Bollinger multiplier k). */
    public record Param(ParamKind kind, String name, boolean positiveInt) {
        static Param series(String name) {
            return new Param(ParamKind.PRICE_SERIES, name, false);
        }
        static Param period(String name) {
            return new Param(ParamKind.CONST_NUMBER, name, true);
        }
        static Param constant(String name) {
            return new Param(ParamKind.CONST_NUMBER, name, false);
        }
        static Param numeric(String name) {
            return new Param(ParamKind.NUMERIC, name, false);
        }
    }

    /**
     * One callable. {@code warmup} receives the CONST_NUMBER argument values
     * in declaration order and returns how many bars this indicator needs
     * before its output is meaningful. {@code extraBars} is added on top of
     * the arguments' own warm-up (CROSSOVER looks one bar back, so 1).
     */
    public record Signature(
            String name,
            List<Param> params,
            boolean returnsBool,
            boolean precomputed,
            ToIntFunction<double[]> warmup,
            int extraBars
    ) {
        public int arity() {
            return params.size();
        }

        /** e.g. {@code SMA(source, period)} — used in arity error messages. */
        public String describe() {
            return name + "(" + params.stream().map(Param::name)
                    .collect(Collectors.joining(", ")) + ")";
        }
    }

    private static Signature indicator(String name, List<Param> params,
                                       ToIntFunction<double[]> warmup) {
        return new Signature(name, params, false, true, warmup, 0);
    }

    private static Signature function(String name, List<Param> params,
                                      boolean returnsBool, int extraBars) {
        return new Signature(name, params, returnsBool, false, c -> 0, extraBars);
    }

    /**
     * The table. Warm-up notes:
     * RSI/ATR use Wilder smoothing seeded by a p-bar average → p+1.
     * SMA/BB need a full window → p.
     * EMA/MACD are infinite-impulse; 3× the (slowest) period is the standard
     * convergence heuristic (documented as such in the report).
     * VWAP is session-anchored → 1 bar.
     */
    private static final Map<String, Signature> TABLE = List.of(
            // ── Indicators (precomputed by the engine) ──────────────────
            indicator("RSI", List.of(Param.period("period")),
                    c -> (int) c[0] + 1),
            indicator("SMA", List.of(Param.series("source"), Param.period("period")),
                    c -> (int) c[0]),
            indicator("EMA", List.of(Param.series("source"), Param.period("period")),
                    c -> 3 * (int) c[0]),
            indicator("ATR", List.of(Param.period("period")),
                    c -> (int) c[0] + 1),
            indicator("VWAP", List.of(),
                    c -> 1),
            indicator("BB_UPPER", List.of(Param.series("source"),
                            Param.period("period"), Param.constant("k")),
                    c -> (int) c[0]),
            indicator("BB_LOWER", List.of(Param.series("source"),
                            Param.period("period"), Param.constant("k")),
                    c -> (int) c[0]),
            indicator("MACD_LINE", List.of(Param.period("fast"), Param.period("slow")),
                    c -> 3 * (int) c[1]),
            indicator("MACD_SIGNAL", List.of(Param.period("fast"),
                            Param.period("slow"), Param.period("signal")),
                    c -> 3 * ((int) c[1] + (int) c[2])),

            // ── Expansion set: oscillators ──────────────────────────────
            indicator("STOCH_K", List.of(Param.period("kPeriod")),
                    c -> (int) c[0]),
            indicator("STOCH_D", List.of(Param.period("kPeriod"),
                            Param.period("dSmooth")),
                    c -> (int) c[0] + (int) c[1]),
            indicator("WILLR", List.of(Param.period("period")),
                    c -> (int) c[0]),
            indicator("CCI", List.of(Param.period("period")),
                    c -> (int) c[0]),
            indicator("MFI", List.of(Param.period("period")),
                    c -> (int) c[0] + 1),
            indicator("ROC", List.of(Param.series("source"), Param.period("period")),
                    c -> (int) c[0] + 1),
            indicator("MOM", List.of(Param.series("source"), Param.period("period")),
                    c -> (int) c[0] + 1),

            // ── Expansion set: trend ────────────────────────────────────
            // ADX chains two Wilder smoothings -> the largest warm-up here.
            indicator("ADX", List.of(Param.period("period")),
                    c -> 2 * (int) c[0] + 1),
            indicator("PLUS_DI", List.of(Param.period("period")),
                    c -> (int) c[0] + 1),
            indicator("MINUS_DI", List.of(Param.period("period")),
                    c -> (int) c[0] + 1),
            indicator("SUPERTREND", List.of(Param.period("period"),
                            Param.constant("multiplier")),
                    c -> (int) c[0] + 1),

            // ── Expansion set: moving averages ──────────────────────────
            indicator("WMA", List.of(Param.series("source"), Param.period("period")),
                    c -> (int) c[0]),
            indicator("HMA", List.of(Param.series("source"), Param.period("period")),
                    c -> (int) c[0] + (int) Math.round(Math.sqrt(c[0]))),

            // ── Expansion set: channels & window stats ──────────────────
            indicator("DONCHIAN_UPPER", List.of(Param.period("period")),
                    c -> (int) c[0]),
            indicator("DONCHIAN_LOWER", List.of(Param.period("period")),
                    c -> (int) c[0]),
            indicator("STDDEV", List.of(Param.series("source"), Param.period("period")),
                    c -> (int) c[0]),
            indicator("HIGHEST", List.of(Param.series("source"), Param.period("period")),
                    c -> (int) c[0]),
            indicator("LOWEST", List.of(Param.series("source"), Param.period("period")),
                    c -> (int) c[0]),

            // ── Expansion set: volume ───────────────────────────────────
            indicator("OBV", List.of(), c -> 1),

            // ── Functions (interpreted per bar) ─────────────────────────
            function("CROSSOVER", List.of(Param.numeric("a"), Param.numeric("b")),
                    true, 1),
            function("CROSSUNDER", List.of(Param.numeric("a"), Param.numeric("b")),
                    true, 1),
            function("MAX", List.of(Param.numeric("a"), Param.numeric("b")),
                    false, 0),
            function("MIN", List.of(Param.numeric("a"), Param.numeric("b")),
                    false, 0),
            function("ABS", List.of(Param.numeric("a")),
                    false, 0)
    ).stream().collect(Collectors.toUnmodifiableMap(Signature::name, s -> s));

    public static Optional<Signature> lookup(String name) {
        return Optional.ofNullable(TABLE.get(name));
    }

    /** All callable names — used for "did you mean ...?" suggestions. */
    public static List<String> allNames() {
        return TABLE.keySet().stream().sorted().toList();
    }

    private Registry() {
    }
}