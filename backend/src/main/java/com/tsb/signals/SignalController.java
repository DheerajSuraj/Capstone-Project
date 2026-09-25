package com.tsb.signals;

import com.tsb.debugger.DecisionExplainer;
import com.tsb.strategy.BacktestService;
import com.tsb.strategy.CompileController;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
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
import java.util.List;

/**
 * POST /api/signals — signal statistics and confluence for one backtest.
 *
 * <p>Same inputs as the backtest (source, from, to), so it re-runs exactly
 * that backtest and measures it. Kept separate from /api/backtest because
 * it walks every bar of every condition: worth doing when asked, not on
 * every run.
 */
@RestController
@RequestMapping("/api/signals")
@Validated
public class SignalController {

    private final BacktestService backtests;

    public SignalController(BacktestService backtests) {
        this.backtests = backtests;
    }

    public record SignalRequest(
            @NotBlank @Size(max = 65_536) String source,
            String from,
            String to,
            /** Near-miss threshold as a fraction; default 0.02 (2%). */
            @DecimalMin("0.001") @DecimalMax("0.5") Double nearMiss
    ) {
    }

    public record SignalResponse(
            boolean ok,
            List<CompileController.DiagnosticDto> diagnostics,
            String runError,
            SignalStatistics.Report statistics,
            Confluence.Report confluence
    ) {
        static SignalResponse error(String message) {
            return new SignalResponse(false, List.of(), message, null, null);
        }
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public SignalResponse analyze(@RequestBody @Valid SignalRequest request) {
        Instant from;
        Instant to;
        try {
            from = request.from() == null ? null : Instant.parse(request.from());
            to = request.to() == null ? null : Instant.parse(request.to());
        } catch (DateTimeParseException e) {
            return SignalResponse.error(
                    "invalid from/to — use ISO-8601 like 2025-01-01T00:00:00Z");
        }

        BacktestService.Preparation prep = backtests.prepare(request.source(), from, to);
        if (!prep.diagnostics().isEmpty()) {
            return new SignalResponse(false, prep.diagnostics().stream()
                    .map(CompileController.DiagnosticDto::from).toList(),
                    null, null, null);
        }
        if (prep.runError().isPresent()) {
            return SignalResponse.error(prep.runError().get());
        }
        BacktestService.Prepared p = prep.prepared().orElseThrow();

        DecisionExplainer explainer =
                new DecisionExplainer(p.strategy(), p.series(), p.rules());
        double nearMiss = request.nearMiss() == null
                ? SignalStatistics.DEFAULT_NEAR_MISS : request.nearMiss();
        return new SignalResponse(true, List.of(), null,
                SignalStatistics.compute(explainer, nearMiss),
                Confluence.analyze(p.strategy(), p.series(),
                        explainer.result().trades()));
    }
}
