package com.tsb.compiler;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Analyzer")
class AnalyzerTest {

    // ── Helpers ─────────────────────────────────────────────────────────

    /** Full pipeline: lex + parse (must be clean) + analyze. */
    private static Analyzer.AnalysisResult analyze(String source) {
        Lexer.LexResult lexed = new Lexer(source).scan();
        assertEquals(List.of(), lexed.diagnostics(), "must lex cleanly");
        Parser.ParseResult parsed = new Parser(lexed.tokens()).parse();
        assertEquals(List.of(), parsed.diagnostics(), "must parse cleanly");
        return new Analyzer(parsed.strategy().orElseThrow()).analyze();
    }

    private static CompiledStrategy compile(String source) {
        Analyzer.AnalysisResult r = analyze(source);
        assertEquals(List.of(), r.diagnostics(),
                "expected clean analysis, got: " + r.diagnostics());
        return r.strategy().orElseThrow();
    }

    private static boolean hasCode(Analyzer.AnalysisResult r, String code) {
        return r.diagnostics().stream().anyMatch(d -> d.code().equals(code));
    }

    /** Wraps rule-body lines (and optional lets) in a valid strategy.
     *  NOTE the %% — this template goes through String.formatted(), where a
     *  literal '%' must be escaped as '%%' or the formatter treats it as a
     *  conversion specifier and throws. */
    private static String strategyWith(String lets, String ruleBody) {
        return """
                strategy "T" {
                    symbol = BTCUSDT
                    timeframe = 1h
                    capital = 10000
                    fee = 0.1%%
                    %s
                    rule r {
                        %s
                    }
                }
                """.formatted(lets, ruleBody);
    }

    // ── The happy path ──────────────────────────────────────────────────

    @Test
    @DisplayName("compiles the flagship strategy: config, manifest, warm-up")
    void flagship() {
        CompiledStrategy s = compile("""
                strategy "RSI Mean Reversion" {
                    symbol = BTCUSDT
                    timeframe = 1h
                    capital = 10000
                    fee = 0.1%

                    let rsi = RSI(14)

                    rule entry {
                        IF rsi < 30 AND CLOSE > SMA(CLOSE, 200)
                        THEN BUY qty = 25% OF EQUITY
                    }
                    rule exit {
                        IF rsi > 70 THEN SELL ALL
                    }
                }
                """);

        assertEquals("BTCUSDT", s.symbol());
        assertEquals("1h", s.timeframe());
        assertEquals(10000, s.capital());
        assertEquals(0.001, s.feePercent(), 1e-12); // 0.1% -> 0.001, HERE only

        // Manifest = the engine's precompute work order.
        assertEquals(2, s.indicators().size());
        assertTrue(s.indicators().stream()
                .anyMatch(i -> i.key().equals("RSI(14)")));
        assertTrue(s.indicators().stream()
                .anyMatch(i -> i.key().equals("SMA(CLOSE,200)")));

        // Warm-up = the slowest thing used: SMA(...,200) needs 200 bars.
        assertEquals(200, s.warmupBars());
    }

    @Test
    @DisplayName("identical indicator uses deduplicate to one manifest entry")
    void manifestDeduplicates() {
        CompiledStrategy s = compile(strategyWith("",
                """
                IF RSI(14) < 30 THEN BUY ALL
                IF RSI(14) > 70 THEN SELL ALL
                """));
        assertEquals(1, s.indicators().size());
    }

    @Test
    @DisplayName("constant expressions fold: RSI(7 * 2) is RSI(14)")
    void constantFolding() {
        CompiledStrategy s = compile(strategyWith("",
                "IF RSI(7 * 2) < 30 THEN BUY ALL"));
        assertTrue(s.indicators().stream()
                .anyMatch(i -> i.key().equals("RSI(14)")));
        assertEquals(15, s.warmupBars()); // Wilder RSI: period + 1
    }

    // ── Warm-up arithmetic ──────────────────────────────────────────────

    @Nested
    @DisplayName("warm-up")
    class Warmup {

        @Test
        @DisplayName("lookback adds its offset to the target's warm-up")
        void lookbackAdds() {
            CompiledStrategy s = compile(strategyWith(
                    "let rsi = RSI(14)",
                    "IF rsi[2] < 30 THEN BUY ALL"));
            assertEquals(17, s.warmupBars()); // 15 (RSI) + 2 (lookback)
        }

        @Test
        @DisplayName("CROSSOVER adds one bar (it peeks one bar back)")
        void crossoverAddsOne() {
            CompiledStrategy s = compile(strategyWith("",
                    "IF CROSSOVER(CLOSE, SMA(CLOSE, 50)) THEN BUY ALL"));
            assertEquals(51, s.warmupBars());
        }

        @Test
        @DisplayName("EMA uses the 3x convergence heuristic")
        void emaHeuristic() {
            CompiledStrategy s = compile(strategyWith("",
                    "IF CLOSE > EMA(CLOSE, 20) THEN BUY ALL"));
            assertEquals(60, s.warmupBars());
        }
    }

    // ── Name resolution ─────────────────────────────────────────────────

    @Nested
    @DisplayName("name resolution")
    class Names {

        @Test
        @DisplayName("unknown variable suggests the near-miss let")
        void unknownVarSuggestion() {
            Analyzer.AnalysisResult r = analyze(strategyWith(
                    "let rsi = RSI(14)",
                    "IF rsii < 30 THEN BUY ALL"));
            assertTrue(hasCode(r, "SEM001"));
            assertTrue(r.diagnostics().get(0).message().contains("'rsi'"),
                    "expected a did-you-mean: " + r.diagnostics());
        }

        @Test
        @DisplayName("unknown indicator suggests the registry near-miss")
        void unknownIndicatorSuggestion() {
            Analyzer.AnalysisResult r = analyze(strategyWith("",
                    "IF RSII(14) < 30 THEN BUY ALL"));
            assertTrue(hasCode(r, "SEM001"));
            assertTrue(r.diagnostics().get(0).message().contains("'RSI'"));
        }

        @Test
        @DisplayName("a let cannot read a let declared below it")
        void useBeforeDeclaration() {
            Analyzer.AnalysisResult r = analyze(strategyWith("""
                    let b = a + 1
                    let a = RSI(14)
                    """,
                    "IF b < 30 THEN BUY ALL"));
            assertTrue(hasCode(r, "SEM001"));
        }

        @Test
        @DisplayName("duplicate lets are rejected")
        void duplicateLet() {
            Analyzer.AnalysisResult r = analyze(strategyWith("""
                    let x = RSI(14)
                    let x = SMA(CLOSE, 50)
                    """,
                    "IF x < 30 THEN BUY ALL"));
            assertTrue(hasCode(r, "SEM005"));
        }
    }

    // ── Signature checking ──────────────────────────────────────────────

    @Nested
    @DisplayName("signatures")
    class Signatures {

        @Test
        @DisplayName("wrong arity names the expected signature")
        void arity() {
            Analyzer.AnalysisResult r = analyze(strategyWith("",
                    "IF SMA(CLOSE) > 5 THEN BUY ALL"));
            assertTrue(hasCode(r, "SEM002"));
            assertTrue(r.diagnostics().get(0).message()
                    .contains("SMA(source, period)"));
        }

        @Test
        @DisplayName("indicator periods must be constants (they're precomputed)")
        void nonConstantPeriod() {
            Analyzer.AnalysisResult r = analyze(strategyWith("",
                    "IF RSI(CLOSE) < 30 THEN BUY ALL"));
            assertTrue(hasCode(r, "SEM004"));
        }

        @Test
        @DisplayName("periods must be positive whole numbers")
        void badPeriods() {
            assertTrue(hasCode(analyze(strategyWith("",
                    "IF RSI(0) < 30 THEN BUY ALL")), "SEM012"));
            assertTrue(hasCode(analyze(strategyWith("",
                    "IF RSI(14.5) < 30 THEN BUY ALL")), "SEM012"));
        }

        @Test
        @DisplayName("indicator-of-indicator is a clear 'for now' error")
        void indicatorOfIndicator() {
            Analyzer.AnalysisResult r = analyze(strategyWith("",
                    "IF SMA(RSI(14), 5) > 50 THEN BUY ALL"));
            assertTrue(hasCode(r, "SEM013"));
        }
    }

    // ── Type checking ───────────────────────────────────────────────────

    @Nested
    @DisplayName("types")
    class Types {

        @Test
        @DisplayName("an IF condition must be boolean")
        void conditionMustBeBool() {
            Analyzer.AnalysisResult r = analyze(strategyWith("",
                    "IF CLOSE THEN BUY ALL"));
            assertTrue(hasCode(r, "SEM011"));
        }

        @Test
        @DisplayName("AND/OR take conditions, not numbers")
        void logicTakesBools() {
            Analyzer.AnalysisResult r = analyze(strategyWith("",
                    "IF CLOSE AND 5 THEN BUY ALL"));
            assertTrue(hasCode(r, "SEM003"));
        }

        @Test
        @DisplayName("percent in arithmetic gets the tailored message")
        void percentInArithmetic() {
            Analyzer.AnalysisResult r = analyze(strategyWith("",
                    "IF CLOSE > 25% + 1 THEN BUY ALL"));
            assertTrue(hasCode(r, "SEM010"));
            assertTrue(r.diagnostics().get(0).message().contains("0.25"));
        }

        @Test
        @DisplayName("one broken subtree yields ONE error, not a cascade")
        void invalidSuppressesCascades() {
            // The unknown name is the only real mistake; the comparison and
            // AND above it must not pile on their own type errors.
            Analyzer.AnalysisResult r = analyze(strategyWith("",
                    "IF nope < 30 AND CLOSE > 5 THEN BUY ALL"));
            long errors = r.diagnostics().stream()
                    .filter(Diagnostic::isError).count();
            assertEquals(1, errors, "cascade: " + r.diagnostics());
        }
    }

    // ── Config ──────────────────────────────────────────────────────────

    @Nested
    @DisplayName("config")
    class Config {

        @Test
        @DisplayName("unknown keys are flagged with a suggestion")
        void unknownKey() {
            Analyzer.AnalysisResult r = analyze("""
                    strategy "T" {
                        symbl = BTCUSDT
                        timeframe = 1h
                        capital = 10000
                        rule r { IF CLOSE > 5 THEN BUY ALL }
                    }
                    """);
            assertTrue(hasCode(r, "SEM007"));
            assertTrue(r.diagnostics().stream().anyMatch(
                    d -> d.message().contains("'symbol'")));
        }

        @Test
        @DisplayName("missing required keys are reported individually")
        void missingRequired() {
            Analyzer.AnalysisResult r = analyze("""
                    strategy "T" {
                        rule r { IF CLOSE > 5 THEN BUY ALL }
                    }
                    """);
            long missing = r.diagnostics().stream()
                    .filter(d -> d.code().equals("SEM009")).count();
            assertEquals(3, missing); // symbol, timeframe, capital
        }

        @Test
        @DisplayName("unsupported timeframe lists the valid set")
        void badTimeframe() {
            Analyzer.AnalysisResult r = analyze("""
                    strategy "T" {
                        symbol = BTCUSDT
                        timeframe = 2h
                        capital = 10000
                        rule r { IF CLOSE > 5 THEN BUY ALL }
                    }
                    """);
            assertTrue(hasCode(r, "SEM012"));
        }
    }

    // ── Actions & sizing ────────────────────────────────────────────────

    @Nested
    @DisplayName("actions and sizing")
    class Actions {

        @Test
        @DisplayName("position percent must be within (0, 100]")
        void sizingRange() {
            Analyzer.AnalysisResult r = analyze(strategyWith("",
                    "IF CLOSE > 5 THEN BUY qty = 150% OF EQUITY"));
            assertTrue(hasCode(r, "SEM012"));
        }

        @Test
        @DisplayName("fixed qty must be a positive constant")
        void qtyPositiveConstant() {
            Analyzer.AnalysisResult r = analyze(strategyWith("",
                    "IF CLOSE > 5 THEN BUY qty = 0 - 3"));
            assertTrue(hasCode(r, "SEM012"));
        }

        @Test
        @DisplayName("SET targets expect a positive percent")
        void setWantsPercent() {
            Analyzer.AnalysisResult r = analyze(strategyWith("",
                    "IF CLOSE > 5 THEN SET STOPLOSS = 5"));
            assertTrue(hasCode(r, "SEM012"));
        }
    }

    @Test
    @DisplayName("multiple independent mistakes are ALL reported in one pass")
    void accumulatesErrors() {
        Analyzer.AnalysisResult r = analyze(strategyWith(
                "let rsi = RSII(14)",
                """
                IF rsi < 30 THEN BUY qty = 150% OF EQUITY
                IF CLOSE THEN SELL ALL
                """));
        long errors = r.diagnostics().stream()
                .filter(Diagnostic::isError).count();
        assertTrue(errors >= 3, "expected >=3 errors, got: " + r.diagnostics());
        assertTrue(r.strategy().isEmpty());
    }
}