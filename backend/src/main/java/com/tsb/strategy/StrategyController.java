package com.tsb.strategy;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;

/**
 * Strategy CRUD + run endpoints. Same contract philosophy as /api/compile:
 * compile failures are 200 with diagnostics (the request worked; the
 * source has problems), unknown ids are 404, malformed requests are 400.
 */
@RestController
@RequestMapping("/api/strategies")
@Validated
public class StrategyController {

    private final StrategyService service;

    public StrategyController(StrategyService service) {
        this.service = service;
    }

    // ── Create & version ────────────────────────────────────────────────

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public SaveResponse create(@RequestBody @jakarta.validation.Valid CreateRequest req) {
        return SaveResponse.from(service.create(req.name(), req.source()));
    }

    @PostMapping(path = "/{id}/versions", consumes = MediaType.APPLICATION_JSON_VALUE)
    public SaveResponse addVersion(@PathVariable long id,
                                   @RequestBody @jakarta.validation.Valid VersionRequest req) {
        try {
            return SaveResponse.from(service.addVersion(id, req.source()));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        }
    }

    // ── Run ─────────────────────────────────────────────────────────────

    @PostMapping(path = "/{id}/versions/{version}/run",
            consumes = MediaType.APPLICATION_JSON_VALUE)
    public RunResponse run(@PathVariable long id, @PathVariable int version,
                           @RequestBody RunRequest req) {
        Instant from;
        Instant to;
        try {
            from = req.from() == null ? null : Instant.parse(req.from());
            to = req.to() == null ? null : Instant.parse(req.to());
        } catch (DateTimeParseException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "invalid from/to — use ISO-8601 like 2025-01-01T00:00:00Z");
        }
        try {
            StrategyService.RunOutcome run = service.runVersion(id, version, from, to);
            BacktestService.Outcome o = run.outcome();
            if (!o.ok()) {
                return new RunResponse(false, null,
                        o.diagnostics().stream()
                                .map(CompileController.DiagnosticDto::from).toList(),
                        o.runError().orElse(null), null);
            }
            return new RunResponse(true, run.runId().orElseThrow(), List.of(), null,
                    BacktestController.Result.from(o.result().orElseThrow(),
                            o.strategy().orElseThrow(), o.series().orElseThrow()));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        }
    }

    // ── Queries ─────────────────────────────────────────────────────────

    @GetMapping
    public List<StrategyDto> list() {
        return service.listStrategies().stream().map(s -> StrategyDto.from(s,
                service.listVersions(s.getId()))).toList();
    }

    @GetMapping("/{id}")
    public StrategyDto get(@PathVariable long id) {
        Strategy s = service.getStrategy(id).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "no strategy " + id));
        return StrategyDto.from(s, service.listVersions(id));
    }

    @GetMapping("/{id}/versions/{version}")
    public VersionDto getVersion(@PathVariable long id, @PathVariable int version) {
        return service.getVersion(id, version).map(VersionDto::from)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "no version " + version));
    }

    @GetMapping("/{id}/runs")
    public List<RunSummaryDto> runs(@PathVariable long id) {
        return service.listRuns(id).stream().map(RunSummaryDto::from).toList();
    }

    @ResponseStatus(HttpStatus.NOT_FOUND)
    private static class NotFound extends RuntimeException {
    }

    // ── Contract ────────────────────────────────────────────────────────

    public record CreateRequest(
            @NotBlank @Size(max = 100) String name,
            @NotBlank @Size(max = 65_536) String source
    ) {
    }

    public record VersionRequest(
            @NotBlank @Size(max = 65_536) String source
    ) {
    }

    public record RunRequest(String from, String to) {
    }

    public record SaveResponse(
            boolean ok,
            Long strategyId,
            Integer versionNumber,
            List<CompileController.DiagnosticDto> diagnostics
    ) {
        static SaveResponse from(StrategyService.SaveOutcome outcome) {
            return outcome.ok()
                    ? new SaveResponse(true,
                    outcome.version().orElseThrow().getStrategyId(),
                    outcome.version().orElseThrow().getVersionNumber(),
                    List.of())
                    : new SaveResponse(false, null, null,
                    outcome.diagnostics().stream()
                            .map(CompileController.DiagnosticDto::from)
                            .toList());
        }
    }

    public record RunResponse(
            boolean ok,
            Long runId,
            List<CompileController.DiagnosticDto> diagnostics,
            String runError,
            BacktestController.Result result
    ) {
    }

    public record StrategyDto(
            long id, String name, int latestVersion,
            String symbol, String timeframe, String updatedAt
    ) {
        static StrategyDto from(Strategy s, List<StrategyVersion> versions) {
            StrategyVersion latest = versions.isEmpty() ? null : versions.get(0);
            return new StrategyDto(s.getId(), s.getName(),
                    latest == null ? 0 : latest.getVersionNumber(),
                    latest == null ? null : latest.getSymbol(),
                    latest == null ? null : latest.getTimeframe(),
                    s.getUpdatedAt().toString());
        }
    }

    public record VersionDto(
            long strategyId, int versionNumber, String source,
            String symbol, String timeframe, int warmupBars, String createdAt
    ) {
        static VersionDto from(StrategyVersion v) {
            return new VersionDto(v.getStrategyId(), v.getVersionNumber(),
                    v.getSource(), v.getSymbol(), v.getTimeframe(),
                    v.getWarmupBars(), v.getCreatedAt().toString());
        }
    }

    public record RunSummaryDto(
            long id, long strategyVersionId, double totalReturnPct,
            double maxDrawdownPct, double winRate, int tradeCount,
            Double sharpeRatio, Double profitFactor, String createdAt
    ) {
        static RunSummaryDto from(BacktestRun r) {
            return new RunSummaryDto(r.getId(), r.getStrategyVersionId(),
                    r.getTotalReturnPct(), r.getMaxDrawdownPct(),
                    r.getWinRate(), r.getTradeCount(), r.getSharpeRatio(),
                    r.getProfitFactor(), r.getCreatedAt().toString());
        }
    }
}