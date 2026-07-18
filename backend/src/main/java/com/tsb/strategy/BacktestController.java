package com.tsb.strategy;

import com.tsb.compiler.CompiledStrategy;
import com.tsb.execution.BacktestResult;
import com.tsb.execution.Metrics;
import com.tsb.execution.Trade;
import com.tsb.marketdata.CandleSeries;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

/**
 * POST /api/backtest — compile TSL and run it over ingested candles.
 *
 * <p>Response design mirrors /api/compile and extends it: compile problems
 * arrive as {@code diagnostics} (spans included, frontend paints them on
 * blocks); platform problems (unknown symbol, empty range) arrive as
 * {@code runError}; success carries stats, the trade list, and a
 * DOWNSAMPLED equity curve.
 *
 * <p><b>Why downsample:</b> a 5m backtest holds ~214k bars; a chart has
 * ~2000 horizontal pixels. The curve is strided to at most 1000 points
 * (final point always kept, so final equity is exact). All statistics are
 * computed on the FULL-resolution curve before thinning — max drawdown is
 * exact even though the plotted line is sampled.
 */
@RestController
@RequestMapping("/api/backtest")
@Validated
public class BacktestController {

    private static final int MAX_CURVE_POINTS = 1_000;

    private final BacktestService backtestService;

    public BacktestController(BacktestService backtestService) {
        this.backtestService = backtestService;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public BacktestResponse run(
            @RequestBody @jakarta.validation.Valid BacktestRequest request) {
        Instant from;
        Instant to;
        try {
            from = request.from() == null ? null : Instant.parse(request.from());
            to = request.to() == null ? null : Instant.parse(request.to());
        } catch (DateTimeParseException e) {
            return BacktestResponse.runError(
                    "invalid from/to — use ISO-8601 like 2025-01-01T00:00:00Z");
        }

        BacktestService.Outcome outcome =
                backtestService.run(request.source(), from, to);

        if (!outcome.diagnostics().isEmpty()) {
            return BacktestResponse.compileError(outcome.diagnostics().stream()
                    .map(CompileController.DiagnosticDto::from).toList());
        }
        if (outcome.runError().isPresent()) {
            return BacktestResponse.runError(outcome.runError().get());
        }
        return BacktestResponse.success(
                outcome.result().orElseThrow(),
                outcome.strategy().orElseThrow(),
                outcome.series().orElseThrow());
    }

    // ── Contract ────────────────────────────────────────────────────────

    public record BacktestRequest(
            @NotBlank(message = "source must not be blank")
            @Size(max = 65_536, message = "source too large (max 64 KiB)")
            String source,
            String from,   // optional ISO-8601 instant
            String to      // optional ISO-8601 instant
    ) {
    }

    public record BacktestResponse(
            boolean ok,
            List<CompileController.DiagnosticDto> diagnostics,
            String runError,
            Result result
    ) {
        static BacktestResponse compileError(
                List<CompileController.DiagnosticDto> diagnostics) {
            return new BacktestResponse(false, diagnostics, null, null);
        }

        static BacktestResponse runError(String message) {
            return new BacktestResponse(false, List.of(), message, null);
        }

        static BacktestResponse success(BacktestResult r,
                                        CompiledStrategy strategy,
                                        CandleSeries series) {
            return new BacktestResponse(true, List.of(), null,
                    Result.from(r, strategy, series));
        }
    }

    public record Result(
            String strategyName,
            String symbol,
            String timeframe,
            double initialCapital,
            double finalEquity,
            double totalReturnPct,
            double maxDrawdownPct,
            double winRate,
            int tradeCount,
            double totalFees,
            int warmupBars,
            int barsProcessed,
            String firstBarTime,
            String lastBarTime,
            MetricsDto metrics,
            List<TradeDto> trades,
            List<CurvePoint> equityCurve
    ) {
        static Result from(BacktestResult r, CompiledStrategy strategy,
                           CandleSeries series) {
            // Metrics run on the FULL-resolution curve, before downsampling,
            // so Sharpe/Sortino are exact even though the plotted line is
            // thinned for transport.
            Metrics metrics = Metrics.compute(r.equityCurve(),
                    r.warmupBars(), r.trades(),
                    Metrics.barsPerYear(strategy.timeframe()));
            return new Result(
                    strategy.name(), strategy.symbol(), strategy.timeframe(),
                    r.initialCapital(), r.finalEquity(), r.totalReturnPct(),
                    r.maxDrawdownPct(), r.winRate(), r.trades().size(),
                    r.totalFees(), r.warmupBars(), r.barsProcessed(),
                    Instant.ofEpochMilli(series.openTimeMillis()[0]).toString(),
                    Instant.ofEpochMilli(series.openTimeMillis()[series.size() - 1])
                            .toString(),
                    MetricsDto.from(metrics),
                    r.trades().stream().map(TradeDto::from).toList(),
                    downsample(series.openTimeMillis(), r.equityCurve()));
        }
    }

    /** Non-finite values (undefined Sharpe, infinite profit factor) map to
     *  JSON null — Infinity is not valid JSON, and a fabricated number
     *  would be a lie. The frontend renders null as an em-dash. */
    public record MetricsDto(
            Double sharpeRatio,
            Double sortinoRatio,
            Double profitFactor,
            Double avgTradePnl,
            Double bestTradePnl,
            Double worstTradePnl
    ) {
        static MetricsDto from(Metrics m) {
            return new MetricsDto(finite(m.sharpeRatio()),
                    finite(m.sortinoRatio()), finite(m.profitFactor()),
                    finite(m.avgTradePnl()), finite(m.bestTradePnl()),
                    finite(m.worstTradePnl()));
        }

        private static Double finite(double v) {
            return Double.isFinite(v) ? v : null;
        }
    }

    public record TradeDto(
            String entryTime, String exitTime,
            double qty, double entryPrice, double exitPrice,
            double pnl, double pnlPercent, double fees, String exitReason
    ) {
        static TradeDto from(Trade t) {
            return new TradeDto(t.entryTime().toString(),
                    t.exitTime().toString(), t.qty(), t.entryPrice(),
                    t.exitPrice(), t.pnl(), t.pnlPercent(), t.fees(),
                    t.exitReason().name());
        }
    }

    public record CurvePoint(long t, double equity) {
    }

    /** Stride-samples the curve to MAX_CURVE_POINTS, always keeping the
     *  last point so final equity in the plot is exact. */
    static List<CurvePoint> downsample(long[] times, double[] equity) {
        int n = equity.length;
        int stride = Math.max(1, (int) Math.ceil((double) n / MAX_CURVE_POINTS));
        List<CurvePoint> points = new ArrayList<>();
        for (int i = 0; i < n; i += stride) {
            points.add(new CurvePoint(times[i], equity[i]));
        }
        if (n > 0 && (n - 1) % stride != 0) {
            points.add(new CurvePoint(times[n - 1], equity[n - 1]));
        }
        return points;
    }
}