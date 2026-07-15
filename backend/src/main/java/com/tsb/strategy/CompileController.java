package com.tsb.strategy;

import com.tsb.compiler.CompiledStrategy;
import com.tsb.compiler.Diagnostic;
import com.tsb.compiler.Span;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * POST /api/compile — compile TSL source, get diagnostics and (on success) a
 * strategy summary. This is the exact contract the Blockly frontend will
 * consume: it sends the DSL text it generated, and paints red outlines on
 * blocks using the {@code blockId} inside each diagnostic's span.
 *
 * <p><b>API design decisions, documented for the report:</b>
 * <ul>
 *   <li><b>Compile errors are HTTP 200.</b> A strategy with a typo is a
 *       successfully handled request whose RESULT contains diagnostics; the
 *       {@code ok} flag says which. 400 is reserved for malformed requests
 *       (blank body, source over the size limit) via Bean Validation.</li>
 *   <li><b>DTOs, not internal types.</b> The JSON shape below is a public
 *       promise; the compiler's internal records are free to evolve. The
 *       mapping happens in {@link CompileResponse#from} and nowhere else.</li>
 *   <li><b>Thin controller.</b> All real work lives in
 *       {@link CompilationService}; this class only translates HTTP.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/compile")
@Validated
public class CompileController {

    private final CompilationService compilationService;

    /** Constructor injection — the only injection style used in this
     *  codebase: dependencies are explicit, final, and test-constructable. */
    public CompileController(CompilationService compilationService) {
        this.compilationService = compilationService;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public CompileResponse compile(@RequestBody @jakarta.validation.Valid CompileRequest request) {
        CompilationService.Outcome outcome =
                compilationService.compile(request.source());
        return CompileResponse.from(outcome);
    }

    // ── The public contract ─────────────────────────────────────────────

    /** Request body: the TSL source. 64 KiB is generous for a strategy and
     *  cheap insurance against abuse. */
    public record CompileRequest(
            @NotBlank(message = "source must not be blank")
            @Size(max = 65_536, message = "source too large (max 64 KiB)")
            String source
    ) {
    }

    /**
     * Response body. {@code summary} is present iff {@code ok}.
     * Example (error case):
     * <pre>{@code
     * { "ok": false,
     *   "diagnostics": [ { "severity": "ERROR", "code": "SEM001",
     *       "message": "unknown name 'rsii' — did you mean 'rsi'?",
     *       "span": { "startLine": 9, "startCol": 12, "endLine": 9,
     *                 "endCol": 16, "blockId": null } } ],
     *   "summary": null }
     * }</pre>
     */
    public record CompileResponse(
            boolean ok,
            List<DiagnosticDto> diagnostics,
            StrategySummary summary
    ) {
        static CompileResponse from(CompilationService.Outcome outcome) {
            return new CompileResponse(
                    outcome.ok(),
                    outcome.diagnostics().stream()
                            .map(DiagnosticDto::from).toList(),
                    outcome.ok()
                            ? StrategySummary.from(outcome.strategy().orElseThrow())
                            : null);
        }
    }

    public record DiagnosticDto(
            String severity,
            String code,
            String message,
            SpanDto span
    ) {
        static DiagnosticDto from(Diagnostic d) {
            return new DiagnosticDto(d.severity().name(), d.code(),
                    d.message(), SpanDto.from(d.span()));
        }
    }

    /** blockId rides along so the frontend can highlight the exact Blockly
     *  block — the payoff of threading it through Span since day one. */
    public record SpanDto(
            int startLine,
            int startCol,
            int endLine,
            int endCol,
            String blockId
    ) {
        static SpanDto from(Span s) {
            return new SpanDto(s.startLine(), s.startCol(),
                    s.endLine(), s.endCol(), s.blockId());
        }
    }

    /** What the UI shows on a successful compile. */
    public record StrategySummary(
            String name,
            String symbol,
            String timeframe,
            double capital,
            double feePercent,
            List<String> indicators,
            int warmupBars
    ) {
        static StrategySummary from(CompiledStrategy s) {
            return new StrategySummary(
                    s.name(), s.symbol(), s.timeframe(), s.capital(),
                    s.feePercent(),
                    s.indicators().stream()
                            .map(CompiledStrategy.IndicatorInstance::key)
                            .sorted().toList(),
                    s.warmupBars());
        }
    }
}