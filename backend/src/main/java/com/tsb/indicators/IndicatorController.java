package com.tsb.indicators;

import com.tsb.compiler.CompiledStrategy;
import com.tsb.compiler.Expr;
import com.tsb.compiler.Registry;
import com.tsb.execution.IndicatorBank;
import com.tsb.indicators.IndicatorDtos.ComputeRequest;
import com.tsb.indicators.IndicatorDtos.ComputeResponse;
import com.tsb.indicators.IndicatorDtos.ErrorResponse;
import com.tsb.indicators.IndicatorDtos.IndicatorView;
import com.tsb.indicators.IndicatorDtos.ParamView;
import com.tsb.indicators.IndicatorDtos.SeriesView;
import com.tsb.indicators.IndicatorDtos.SpecRequest;
import com.tsb.marketdata.CandleSeries;
import jakarta.validation.Valid;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Indicator values for the chart.
 *
 * <p>The whole point of this controller is that it computes NOTHING. It builds
 * the same {@link CompiledStrategy.IndicatorInstance} objects the analyzer
 * builds, hands them to the same {@link IndicatorBank} the engine uses, and
 * returns what comes back.
 *
 * <p>The alternative — a moving average implemented in JavaScript on the
 * frontend — would be a second implementation of every indicator. Wilder
 * smoothing is recursive, so two implementations drift on floating-point
 * rounding: the chart would show RSI 29.97 while the engine sees 30.01. Once
 * the decision debugger exists it would say "RSI was 30.01, needed below 30"
 * while the line on screen sits visibly below 30, and the user would be right
 * to trust neither. Same argument as re-running the full backtest on every
 * competition tick rather than writing an incremental engine.
 */
@RestController
@RequestMapping("/api/indicators")
public class IndicatorController {

    /** Enough history for a 200-period average to have warmed up long before
     *  the visible window starts. */
    private static final int DEFAULT_BARS = 1500;

    private final CandleSeriesProvider candles;

    public IndicatorController(CandleSeriesProvider candles) {
        this.candles = candles;
    }

    /* ---------------------------------------------------------- catalog */

    /**
     * Everything the engine can draw, built from the registry itself.
     *
     * <p>Generated rather than hardcoded so the picker can never offer an
     * indicator the compiler does not know, and so adding indicator #29 to the
     * registry makes it appear on the chart with no frontend change.
     *
     * <p>Functions (CROSSOVER, MAX, ABS) are excluded: they are interpreted per
     * bar and produce no series to plot.
     */
    @GetMapping("/catalog")
    public List<IndicatorView> catalog() {
        List<IndicatorView> out = new ArrayList<>();

        for (String name : Registry.allNames()) {
            Registry.Signature sig = Registry.lookup(name).orElseThrow();
            if (!sig.precomputed()) continue;

            IndicatorPresentation.Look look = IndicatorPresentation.TABLE.get(name);
            if (look == null) {
                // The drift test should have caught this at build time. If it
                // reaches here, show the indicator with its registry name
                // rather than hiding it — a missing label is a smaller problem
                // than an indicator the user cannot find.
                look = new IndicatorPresentation.Look(name,
                        IndicatorPresentation.Placement.SEPARATE, "CLOSE",
                        List.of(), null, null, List.of(), List.of());
            }

            List<ParamView> params = new ArrayList<>();
            int numberIndex = 0;
            for (Registry.Param p : sig.params()) {
                switch (p.kind()) {
                    case PRICE_SERIES -> params.add(new ParamView(
                            p.name(), humanise(p.name()), "series",
                            null,
                            look.defaultSource() == null ? "CLOSE" : look.defaultSource(),
                            false));
                    case CONST_NUMBER -> {
                        Double fallback = numberIndex < look.defaults().size()
                                ? look.defaults().get(numberIndex)
                                : 14.0;
                        params.add(new ParamView(
                                p.name(), humanise(p.name()), "number",
                                fallback, null, p.positiveInt()));
                        numberIndex++;
                    }
                    case NUMERIC -> {
                        // Functions only; precomputed indicators never have one.
                    }
                }
            }

            out.add(new IndicatorView(
                    name,
                    look.label(),
                    params,
                    look.placement() == IndicatorPresentation.Placement.PRICE
                            ? "price" : "separate",
                    look.scaleMin(),
                    look.scaleMax(),
                    look.guides().isEmpty() ? null : look.guides(),
                    look.companions().isEmpty() ? null : look.companions()));
        }

        return out;
    }

    /* ---------------------------------------------------------- compute */

    @PostMapping
    public ComputeResponse compute(@Valid @RequestBody ComputeRequest req) {
        // LinkedHashSet: IndicatorBank takes a Set, and two requests for the
        // same indicator collapse to one computation — while insertion order
        // keeps the response readable.
        Set<CompiledStrategy.IndicatorInstance> manifest = new LinkedHashSet<>();
        // Keeps each requested line paired with the key the engine will file
        // it under, so two clients asking for SMA(CLOSE,20) under different
        // ids still share one computation.
        List<String[]> requested = new ArrayList<>();

        for (SpecRequest spec : req.specs()) {
            CompiledStrategy.IndicatorInstance instance = toInstance(spec);
            manifest.add(instance);
            requested.add(new String[] { spec.id(), instance.key() });
        }

        CandleSeries series = candles.load(
                req.symbol(),
                req.timeframe(),
                parse(req.from()),
                parse(req.to()));

        if (series == null || series.isEmpty()) {
            return new ComputeResponse(List.of(), List.of());
        }

        Map<String, double[]> bank = IndicatorBank.compute(manifest, series);

        long[] openTimes = series.openTimeMillis();
        List<Long> t = new ArrayList<>(openTimes.length);
        for (long ms : openTimes) {
            // Seconds, because that is what the chart library wants.
            t.add(ms / 1000L);
        }

        List<SeriesView> out = new ArrayList<>(requested.size());
        for (String[] pair : requested) {
            String id = pair[0];
            String key = pair[1];
            double[] values = bank.get(key);
            if (values == null) continue;

            List<Double> boxed = new ArrayList<>(values.length);
            for (double v : values) {
                // NaN is the warm-up prefix — the indicator genuinely has no
                // value yet. It becomes null, not 0: JSON has no NaN, and a
                // zero would draw a line along the bottom of the chart that
                // looks like real data.
                boxed.add(Double.isFinite(v) ? v : null);
            }
            out.add(new SeriesView(id, key, boxed));
        }

        return new ComputeResponse(t, out);
    }

    /* ------------------------------------------------------------ plumbing */

    /**
     * Builds the same instance the analyzer would build for this call, so the
     * key matches and {@code IndicatorBank} dispatches identically.
     */
    private CompiledStrategy.IndicatorInstance toInstance(SpecRequest spec) {
        Registry.Signature sig = Registry.lookup(spec.name())
                .orElseThrow(() -> new IllegalArgumentException(
                        "No indicator called " + spec.name() + "."));

        if (!sig.precomputed()) {
            throw new IllegalArgumentException(
                    spec.name() + " is a function, not an indicator, so it has "
                            + "no series to draw.");
        }

        Expr.PriceField source = null;
        List<Double> constArgs = new ArrayList<>();
        int argIndex = 0;
        List<Double> given = spec.args() == null ? List.of() : spec.args();

        for (Registry.Param p : sig.params()) {
            switch (p.kind()) {
                case PRICE_SERIES -> source = priceField(spec.source(), spec.name());
                case CONST_NUMBER -> {
                    if (argIndex >= given.size()) {
                        throw new IllegalArgumentException(
                                sig.describe() + " needs a value for " + p.name() + ".");
                    }
                    double v = given.get(argIndex++);
                    if (p.positiveInt() && (v < 1 || v != Math.floor(v))) {
                        throw new IllegalArgumentException(
                                p.name() + " must be a whole number of 1 or more.");
                    }
                    if (!Double.isFinite(v)) {
                        throw new IllegalArgumentException(
                                p.name() + " must be a number.");
                    }
                    constArgs.add(v);
                }
                case NUMERIC -> {
                    // unreachable for precomputed indicators
                }
            }
        }

        return new CompiledStrategy.IndicatorInstance(
                spec.name(), source, List.copyOf(constArgs));
    }

    private static Expr.PriceField priceField(String raw, String indicator) {
        if (raw == null || raw.isBlank()) return Expr.PriceField.CLOSE;
        try {
            return Expr.PriceField.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    indicator + ": '" + raw + "' is not a price field. Use "
                            + "OPEN, HIGH, LOW, CLOSE or VOLUME.");
        }
    }

    private static Instant parse(String iso) {
        if (iso == null || iso.isBlank()) return null;
        try {
            return Instant.parse(iso);
        } catch (Exception e) {
            throw new IllegalArgumentException("Bad timestamp: " + iso);
        }
    }

    private static String humanise(String paramName) {
        return switch (paramName) {
            case "period" -> "Length";
            case "source" -> "Source";
            case "fast" -> "Fast";
            case "slow" -> "Slow";
            case "signal" -> "Signal";
            case "kPeriod" -> "%K length";
            case "dSmooth" -> "%D smoothing";
            case "k" -> "Std dev";
            case "multiplier" -> "Multiplier";
            default -> paramName.substring(0, 1).toUpperCase() + paramName.substring(1);
        };
    }

    /* ----------------------------------------------------------- errors */

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<ErrorResponse> badInput(IllegalArgumentException e) {
        return ResponseEntity.badRequest()
                .body(new ErrorResponse("INDICATOR_INVALID", e.getMessage()));
    }

    /**
     * Thrown by {@code IndicatorBank.computeOne} when a name is in the registry
     * but missing from its dispatch — a real bug, not bad input.
     */
    @ExceptionHandler(IllegalStateException.class)
    ResponseEntity<ErrorResponse> notWired(IllegalStateException e) {
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED)
                .body(new ErrorResponse("INDICATOR_UNAVAILABLE",
                        "That indicator cannot be drawn yet."));
    }
}