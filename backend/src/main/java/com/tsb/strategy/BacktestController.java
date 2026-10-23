package com.tsb.strategy;

import com.tsb.compiler.CompiledStrategy;
import com.tsb.compiler.Timeframe;
import com.tsb.execution.BacktestResult;
import com.tsb.execution.Benchmark;
import com.tsb.execution.EquityCurves;
import com.tsb.execution.Metrics;
import com.tsb.execution.PerformanceMeasures;
import com.tsb.execution.PeriodReport;
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
import java.util.Comparator;
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
            List<EquityCurves.CurvePoint> equityCurve,
            List<UsedIndicator> indicators,
            // What simply buying at the first candle the strategy could
            // trade, and holding to the end, would have returned — same
            // fee, same candles. The number every strategy has to beat.
            Double buyHoldReturnPct,
            // The fee charged on every fill, as a fraction (0.001 = 0.1%),
            // so a result always says what it assumed trading costs.
            double feeRate,
            // TradAO's measures (yield, drawdown lengths, VaR, volatility,
            // activeness); Sharpe, Sortino and win rate are above.
            MeasuresDto measures,
            // The account over time for the cash-usage chart: net asset
            // value, cash, the coins' value and buy & hold, on the same
            // candles as equityCurve plus every candle a trade changed them.
            List<BookPoint> book,
            // The run by period, top down: the whole run, years, months,
            // weeks, days, and by weekday / hour / month of the year. Null
            // when no candle is left after the warm-up.
            PeriodReport report
    ) {
        static Result from(BacktestResult r, CompiledStrategy strategy,
                           CandleSeries series) {
            // Metrics run on the FULL-resolution curve, before downsampling,
            // so Sharpe/Sortino are exact even though the plotted line is
            // thinned for transport.
            Metrics metrics = Metrics.compute(r.equityCurve(),
                    r.warmupBars(), r.trades(),
                    Metrics.barsPerYear(strategy.timeframe()));
            long barMs = Timeframe.millis(strategy.timeframe()).orElse(3_600_000L);
            PerformanceMeasures measures = PerformanceMeasures.compute(
                    series.openTimeMillis(), r.equityCurve(), r.positionCurve(),
                    r.warmupBars(), r.trades(), barMs);
            double[] hold = Benchmark.curve(series, r.warmupBars(), r.initialCapital(),
                    strategy.feePercent());
            // Also on the full-resolution curve: every day, every hour.
            PeriodReport report = PeriodReport.build(series.openTimeMillis(),
                    r.equityCurve(), r.positionCurve(), r.warmupBars(), r.trades(),
                    strategy.feePercent(), hold, barMs, r.initialCapital());
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
                    downsample(series.openTimeMillis(), r.equityCurve()),
                    // Sorted because the manifest is a Set: without an order
                    // the same strategy would hand its indicators back in a
                    // different sequence between runs, and the chart would
                    // recolour them for no reason the user can see.
                    strategy.indicators().stream()
                            .map(UsedIndicator::from)
                            .sorted(Comparator.comparing(UsedIndicator::key))
                            .toList(),
                    buyHoldReturnPct(series, r.warmupBars(), strategy.feePercent()),
                    strategy.feePercent(),
                    MeasuresDto.from(measures),
                    bookCurve(series, r, hold),
                    report);
        }

        /**
         * Buy at the open after the first bar a rule can run on (exactly
         * when a "BUY ALL" on that bar would fill), sell at the last close,
         * paying the fee both ways. Null when there is no such bar.
         */
        static Double buyHoldReturnPct(CandleSeries series, int warmupBars, double fee) {
            return Benchmark.returnPct(series, warmupBars, fee);
        }
    }

    /** One point of the account over time (see {@link Result#book}). */
    public record BookPoint(long t, double equity, double cash, double positionValue,
                            Double benchmark) {
    }

    /** Most candles kept just because a trade happened on them. */
    private static final int MAX_TRADE_POINTS = 3_000;

    /**
     * The same strided candles as the equity curve — and, because cash only
     * moves when a trade fills, the candle of every fill and the one before
     * it, so the cash line steps exactly where the trades were instead of
     * somewhere between two samples. (A very active strategy keeps only the
     * strided candles.)
     */
    static List<BookPoint> bookCurve(CandleSeries series, BacktestResult r, double[] hold) {
        int n = series.size();
        if (n == 0) {
            return List.of();
        }
        int stride = Math.max(1, (int) Math.ceil((double) n / MAX_CURVE_POINTS));
        boolean[] keep = new boolean[n];
        for (int i = 0; i < n; i += stride) {
            keep[i] = true;
        }
        keep[n - 1] = true;
        double[] cash = r.cashCurve();
        int changes = 0;
        for (int i = 1; i < n; i++) {
            if (cash[i] != cash[i - 1]) {
                changes++;
            }
        }
        if (changes * 2 <= MAX_TRADE_POINTS) {
            for (int i = 1; i < n; i++) {
                if (cash[i] != cash[i - 1]) {
                    keep[i - 1] = true;
                    keep[i] = true;
                }
            }
        }
        long[] t = series.openTimeMillis();
        double[] close = series.close();
        List<BookPoint> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            if (keep[i]) {
                out.add(new BookPoint(t[i], r.equityCurve()[i], cash[i],
                        r.positionCurve()[i] * close[i], hold == null ? null : hold[i]));
            }
        }
        return out;
    }

    /** TradAO's measures; non-finite values become null (see MetricsDto). */
    public record MeasuresDto(
            Double yieldPct,
            Double maxDrawdownPct,
            Double maxDrawdownDays,
            Double avgDrawdownDays,
            Double var99Pct,
            Double volatilityPct,
            Double tradesPer30Days,
            Double timeInMarketPct,
            int days
    ) {
        static MeasuresDto from(PerformanceMeasures m) {
            return new MeasuresDto(f(m.yieldPct()), f(m.maxDrawdownPct()),
                    f(m.maxDrawdownDays()), f(m.avgDrawdownDays()), f(m.var99Pct()),
                    f(m.volatilityPct()), f(m.tradesPer30Days()), f(m.timeInMarketPct()),
                    m.days());
        }

        private static Double f(double v) {
            return Double.isFinite(v) ? v : null;
        }
    }

    /**
     * One indicator the strategy actually used, as the chart needs to ask for
     * it back.
     *
     * <p>Only the IDENTITY travels here, never the values. The chart fetches
     * those from /api/indicators, which runs the same {@code IndicatorBank}
     * over the same candles — and because a backtest is deterministic, what it
     * draws is necessarily what the interpreter indexed during the run.
     *
     * <p>Sending the arrays instead would triple the size of an already large
     * response to say the same thing, and would still need the endpoint for
     * any indicator the user added afterwards to investigate the result.
     */
    public record UsedIndicator(
            String name,
            String source,
            List<Double> args,
            /** The engine's own cache key, e.g. {@code SMA(CLOSE,200)}. */
            String key,
            /** Null on the strategy's own candles; "4h" for {@code ... ON 4h}. */
            String timeframe,
            /** Lookback in those bigger candles ({@code CLOSE[1] ON 1d} = 1). */
            int offset
    ) {
        static UsedIndicator from(CompiledStrategy.IndicatorInstance i) {
            return new UsedIndicator(
                    i.name(),
                    i.source() == null ? null : i.source().name(),
                    i.constArgs(),
                    i.key(),
                    i.timeframe(),
                    i.offset());
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

    /** Delegates to the shared sampler (also used when persisting runs) —
     *  promoted to EquityCurves once a second caller appeared, same rule
     *  as ConstFold. */
    static List<EquityCurves.CurvePoint> downsample(long[] times, double[] equity) {
        return EquityCurves.downsample(times, equity, MAX_CURVE_POINTS);
    }
}