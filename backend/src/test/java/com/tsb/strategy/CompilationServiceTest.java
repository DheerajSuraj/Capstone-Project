package com.tsb.strategy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure-Java tests — note {@code new CompilationService()}: no Spring, no
 * database, no mocks. This is the payoff of a stateless service over a pure
 * compiler.
 */
@DisplayName("CompilationService")
class CompilationServiceTest {

    private final CompilationService service = new CompilationService();

    private static final String VALID = """
            strategy "T" {
                symbol = BTCUSDT
                timeframe = 1h
                capital = 10000
                rule r { IF RSI(14) < 30 THEN BUY ALL }
            }
            """;

    @Test
    @DisplayName("a valid strategy compiles: ok, no diagnostics, strategy present")
    void validCompiles() {
        CompilationService.Outcome out = service.compile(VALID);
        assertTrue(out.ok());
        assertEquals(0, out.diagnostics().size());
        assertEquals(15, out.strategy().orElseThrow().warmupBars());
    }

    @Test
    @DisplayName("lex, parse, and semantic diagnostics all flow through one outcome")
    void diagnosticsFromEveryPhase() {
        // Lex error: stray '@'.
        assertTrue(service.compile("strategy @").diagnostics().stream()
                .anyMatch(d -> d.code().startsWith("LEX")));

        // Parse error: missing THEN.
        assertTrue(service.compile("""
                strategy "T" { rule r { IF CLOSE > 5 BUY ALL } }
                """).diagnostics().stream()
                .anyMatch(d -> d.code().startsWith("PAR")));

        // Semantic error: unknown indicator.
        assertTrue(service.compile(VALID.replace("RSI", "RSII"))
                .diagnostics().stream()
                .anyMatch(d -> d.code().equals("SEM001")));
    }

    @Test
    @DisplayName("phase gating: a parse error means the analyzer never adds noise")
    void phaseGating() {
        CompilationService.Outcome out = service.compile("""
                strategy "T" { rule r { IF CLOSE > 5 BUY ALL } }
                """);
        assertFalse(out.ok());
        assertTrue(out.strategy().isEmpty());
        // Only parser codes — no SEM diagnostics about a half-built tree.
        assertTrue(out.diagnostics().stream()
                .allMatch(d -> d.code().startsWith("PAR")));
    }
}