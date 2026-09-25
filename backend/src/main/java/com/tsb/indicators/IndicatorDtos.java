package com.tsb.indicators;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * Wire shapes for /api/indicators. These mirror the TypeScript types in
 * {@code src/indicators/catalog.ts} — change one, change the other.
 */
public final class IndicatorDtos {

    private IndicatorDtos() {
    }

    /* ---------------------------------------------------------- catalog */

    /**
     * One parameter, as the picker needs to render it.
     *
     * @param kind "series" for a price field the user picks from a dropdown,
     *             "number" for a typed value
     * @param positiveInt periods must be whole and positive; a Bollinger
     *                    multiplier need not be
     */
    public record ParamView(
            String name,
            String label,
            String kind,
            @JsonInclude(JsonInclude.Include.NON_NULL) Double defaultNumber,
            @JsonInclude(JsonInclude.Include.NON_NULL) String defaultSource,
            boolean positiveInt) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record IndicatorView(
            String name,
            String label,
            List<ParamView> params,
            /** "price" or "separate". */
            String placement,
            Double scaleMin,
            Double scaleMax,
            List<Double> guides,
            List<String> companions) {
    }

    /* ---------------------------------------------------------- compute */

    /**
     * One indicator to compute.
     *
     * @param id    the client's own handle for this line, echoed back on the
     *              response. The server also returns its canonical key, but
     *              matching on an id the client chose means the frontend never
     *              has to reproduce the key format exactly.
     * @param name registry name, e.g. "SMA"
     * @param source price field for the PRICE_SERIES parameter, or null
     * @param args  the CONST_NUMBER arguments in declaration order
     */
    public record SpecRequest(
            @NotBlank @Size(max = 80) String id,
            @NotBlank @Size(max = 40) String name,
            @Size(max = 10) String source,
            List<Double> args) {
    }

    public record ComputeRequest(
            @NotBlank @Size(max = 20) String symbol,
            @NotBlank @Size(max = 5) String timeframe,
            String from,
            String to,
            @NotEmpty @Size(max = 12) List<SpecRequest> specs) {
    }

    /**
     * One computed line.
     *
     * @param id     the id the client sent, echoed back
     * @param key    the canonical key, e.g. "SMA(CLOSE,200)" — identical to
     *               {@code IndicatorInstance.key()}, so what the chart calls a
     *               line is exactly what the engine calls it. Shown in the
     *               legend tooltip; useful when a user asks why the chart and
     *               a strategy disagree, because they cannot if the keys match.
     * @param values one per candle in {@code t}; null where the indicator has
     *               no value yet
     */
    public record SeriesView(String id, String key, List<Double> values) {
    }

    /**
     * @param t candle open times in seconds. Closed candles only — the forming
     *          candle has no indicator value, for the same reason it is never
     *          stored.
     */
    public record ComputeResponse(List<Long> t, List<SeriesView> series) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ErrorResponse(String code, String message) {
    }
}