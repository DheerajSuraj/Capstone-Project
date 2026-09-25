package com.tsb.debugger;

import com.tsb.strategy.BacktestService;
import com.tsb.strategy.CompileController;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
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
import java.util.Optional;

/**
 * POST /api/debug/explain — "why did the strategy do that at this bar?"
 *
 * <p>The request carries the same source and from/to as the backtest being
 * inspected, plus the time of the bar the user clicked. We re-run the
 * backtest (it is deterministic, so this reproduces it exactly) and explain
 * that bar. Re-running instead of storing a trace keeps nothing on the
 * server and cannot drift from what the engine does.
 */
@RestController
@RequestMapping("/api/debug")
@Validated
public class DebugController {

    private final BacktestService backtests;

    public DebugController(BacktestService backtests) {
        this.backtests = backtests;
    }

    public record ExplainRequest(
            @NotBlank @Size(max = 65_536) String source,
            String from,
            String to,
            /** Open time of the clicked bar, epoch milliseconds. */
            @NotNull Long time
    ) {
    }

    public record ExplainResponse(
            boolean ok,
            List<CompileController.DiagnosticDto> diagnostics,
            String runError,
            Explanation.Bar explanation
    ) {
        static ExplainResponse error(String message) {
            return new ExplainResponse(false, List.of(), message, null);
        }
    }

    @PostMapping(path = "/explain", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ExplainResponse explain(@RequestBody @Valid ExplainRequest request) {
        Instant from;
        Instant to;
        try {
            from = request.from() == null ? null : Instant.parse(request.from());
            to = request.to() == null ? null : Instant.parse(request.to());
        } catch (DateTimeParseException e) {
            return ExplainResponse.error(
                    "invalid from/to — use ISO-8601 like 2025-01-01T00:00:00Z");
        }

        BacktestService.Preparation prep = backtests.prepare(request.source(), from, to);
        if (!prep.diagnostics().isEmpty()) {
            return new ExplainResponse(false, prep.diagnostics().stream()
                    .map(CompileController.DiagnosticDto::from).toList(), null, null);
        }
        if (prep.runError().isPresent()) {
            return ExplainResponse.error(prep.runError().get());
        }
        BacktestService.Prepared p = prep.prepared().orElseThrow();

        DecisionExplainer explainer =
                new DecisionExplainer(p.strategy(), p.series(), p.rules());
        Optional<Integer> bar = explainer.barAt(request.time());
        if (bar.isEmpty()) {
            return ExplainResponse.error("that time is before the first bar of this run");
        }
        return new ExplainResponse(true, List.of(), null, explainer.explain(bar.get()));
    }
}
