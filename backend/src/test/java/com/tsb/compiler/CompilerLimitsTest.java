package com.tsb.compiler;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sources that used to crash or hang the server — answered now with a
 * clear error instead: deep nesting, endless chains, self-reference,
 * exponential lets, out-of-range numbers.
 */
@DisplayName("Compiler limits")
class CompilerLimitsTest {

    /** Everything a compile reports, from all three passes. */
    static List<Diagnostic> compile(String body) {
        return compileFull("""
                strategy "T" {
                    symbol = BTCUSDT
                    timeframe = 1h
                    capital = 1000
                    %s
                }
                """.formatted(body));
    }

    static List<Diagnostic> compileFull(String source) {
        List<Diagnostic> all = new ArrayList<>();
        Lexer.LexResult lexed = new Lexer(source).scan();
        all.addAll(lexed.diagnostics());
        Parser.ParseResult parsed = new Parser(lexed.tokens()).parse();
        all.addAll(parsed.diagnostics());
        if (parsed.strategy().isPresent() && all.stream().noneMatch(Diagnostic::isError)) {
            all.addAll(new Analyzer(parsed.strategy().get()).analyze().diagnostics());
        }
        return all;
    }

    static boolean has(List<Diagnostic> ds, String code) {
        return ds.stream().anyMatch(d -> d.code().equals(code));
    }

    static List<Diagnostic> errors(List<Diagnostic> ds) {
        return ds.stream().filter(Diagnostic::isError).toList();
    }

    @Nested
    @DisplayName("size limits")
    class Size {

        @Test
        @DisplayName("5,000 nested brackets: a parse error, not a stack overflow")
        void deepBrackets() {
            String cond = "(".repeat(5_000) + "CLOSE > 1" + ")".repeat(5_000);
            List<Diagnostic> ds = compile("rule r { IF " + cond + " THEN BUY ALL }");
            assertTrue(has(ds, "PAR011"), ds::toString);
        }

        @Test
        @DisplayName("10,000 chained operators: a parse error, not a stack overflow")
        void longChain() {
            String sum = "CLOSE" + " + 1".repeat(10_000);
            List<Diagnostic> ds = compile("rule r { IF " + sum + " > 0 THEN BUY ALL }");
            assertTrue(has(ds, "PAR011"), ds::toString);
        }

        @Test
        @DisplayName("a long but ordinary expression still compiles")
        void longButFine() {
            String sum = "CLOSE" + " + 1".repeat(300);
            assertEquals(List.of(), errors(compile("rule r { IF " + sum + " > 0 THEN BUY ALL }")));
        }

        @Test
        @DisplayName("thousands of bad characters: errors are capped")
        void lexerErrorsCapped() {
            List<Diagnostic> ds = new Lexer("@".repeat(5_000)).scan().diagnostics();
            assertTrue(ds.size() <= Parser.MAX_ERRORS + 1, "got " + ds.size());
        }

        @Test
        @DisplayName("a chain of 1,000 lets: a clear error, not a stack overflow at run time")
        void letChainTooDeep() {
            StringBuilder sb = new StringBuilder("let a0 = CLOSE\n");
            for (int k = 1; k <= 1_000; k++) {
                sb.append("let a").append(k).append(" = a").append(k - 1).append(" + 1\n");
            }
            sb.append("rule r { IF a1000 > 0 THEN BUY ALL }");
            List<Diagnostic> ds = compile(sb.toString());
            assertTrue(has(ds, "SEM017"), ds::toString);
            assertEquals(1, errors(ds).size(), "one mistake, one message: " + errors(ds));
        }

        @Test
        @DisplayName("lets looking back too far in total are refused before they use the memory")
        void letHistoryBudget() {
            StringBuilder sb = new StringBuilder();
            StringBuilder cond = new StringBuilder();
            for (int k = 0; k < 11; k++) {
                sb.append("let x").append(k).append(" = CLOSE + ").append(k).append('\n');
                cond.append(k == 0 ? "" : " AND ").append("x").append(k).append("[10000] > 0");
            }
            sb.append("rule r { IF ").append(cond).append(" THEN BUY ALL }");
            List<Diagnostic> ds = compile(sb.toString());
            assertTrue(has(ds, "SEM018"), ds::toString);
        }
    }

    @Nested
    @DisplayName("lets")
    class Lets {

        @Test
        @DisplayName("a let that names itself is an unknown name, not endless recursion")
        void selfReference() {
            List<Diagnostic> ds = compile("""
                    let a = a + 1
                    rule r { IF a > 0 THEN BUY ALL }
                    """);
            assertTrue(has(ds, "SEM001"), ds::toString);
        }

        @Test
        @DisplayName("lets that double each other compile in linear time")
        void doublingLets() {
            StringBuilder sb = new StringBuilder("let a0 = CLOSE > 0\n");
            for (int k = 1; k <= 60; k++) {
                sb.append("let a").append(k).append(" = a").append(k - 1)
                        .append(" AND a").append(k - 1).append('\n');
            }
            sb.append("rule r { IF a60 THEN BUY ALL }");
            long t0 = System.nanoTime();
            List<Diagnostic> ds = compile(sb.toString());
            long ms = (System.nanoTime() - t0) / 1_000_000;
            assertEquals(List.of(), errors(ds));
            assertTrue(ms < 5_000, "took " + ms + " ms");
        }
    }

    @Nested
    @DisplayName("values")
    class Values {

        @Test
        @DisplayName("MACD with the fast period not the smaller one: a warning, still runs")
        void macdWarning() {
            List<Diagnostic> swapped = compile("rule r { IF MACD_LINE(26, 12) > 0 THEN BUY ALL }");
            assertTrue(has(swapped, "SEM016"), swapped::toString);
            assertEquals(List.of(), errors(swapped));
            List<Diagnostic> equal = compile("rule r { IF MACD_SIGNAL(12, 12, 9) > 0 THEN BUY ALL }");
            assertTrue(has(equal, "SEM016"), equal::toString);
            assertFalse(has(compile("rule r { IF MACD_LINE(12, 26) > 0 THEN BUY ALL }"), "SEM016"));
        }

        @Test
        @DisplayName("BUY a share of the position is refused: there is none yet when it buys")
        void buyOfPosition() {
            List<Diagnostic> ds = compile("rule r { IF CLOSE > 0 THEN BUY qty = 50% OF POSITION }");
            assertTrue(has(ds, "SEM012"), ds::toString);
        }

        @Test
        @DisplayName("absurd capital and a 100% fee are refused")
        void configBounds() {
            List<Diagnostic> rich = compileFull("""
                    strategy "T" {
                        symbol = BTCUSDT
                        timeframe = 1h
                        capital = 10000000000000
                        rule r { IF CLOSE > 0 THEN BUY ALL }
                    }
                    """);
            assertFalse(errors(rich).isEmpty(), "capital above 1e12");
            List<Diagnostic> fee = compileFull("""
                    strategy "T" {
                        symbol = BTCUSDT
                        timeframe = 1h
                        capital = 1000
                        fee = 100%
                        rule r { IF CLOSE > 0 THEN BUY ALL }
                    }
                    """);
            assertFalse(errors(fee).isEmpty(), "a fee of 100% leaves nothing to trade");
        }

        @Test
        @DisplayName("indicator periods above the cap are refused")
        void periodCap() {
            List<Diagnostic> ds = compile("rule r { IF CLOSE > SMA(CLOSE, 100000) THEN BUY ALL }");
            assertTrue(has(ds, "SEM012"), ds::toString);
        }
    }
}
