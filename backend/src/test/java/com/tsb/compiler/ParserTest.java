package com.tsb.compiler;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Parser")
class ParserTest {

    // ── Helpers ─────────────────────────────────────────────────────────

    /** Lex + parse a full strategy source. */
    private static Parser.ParseResult parse(String source) {
        Lexer.LexResult lexed = new Lexer(source).scan();
        assertEquals(List.of(), lexed.diagnostics(),
                "test sources must lex cleanly: " + source);
        return new Parser(lexed.tokens()).parse();
    }

    /** Parse a full strategy that must produce no diagnostics. */
    private static StrategyAst.StrategyDecl parseClean(String source) {
        Parser.ParseResult r = parse(source);
        assertEquals(List.of(), r.diagnostics(),
                "expected a clean parse for: " + source);
        return r.strategy().orElseThrow();
    }

    /** Parse just an expression and render it canonically. */
    private static String expr(String source) {
        Lexer.LexResult lexed = new Lexer(source).scan();
        assertEquals(List.of(), lexed.diagnostics());
        // expression() is package-private by design so tests can drive the
        // Pratt loop directly without wrapping every case in a strategy.
        return AstPrinter.print(new Parser(lexed.tokens()).expression(0));
    }

    /** A minimal valid strategy wrapping one rule body line. */
    private static String withRule(String ifLine) {
        return """
                strategy "T" {
                    rule r {
                        %s
                    }
                }
                """.formatted(ifLine);
    }

    // ── Expression precedence (the Pratt table, pinned) ─────────────────

    @Nested
    @DisplayName("expression precedence")
    class Precedence {

        @Test
        @DisplayName("AND binds tighter than OR")
        void andOverOr() {
            assertEquals("(a OR (b AND c))", expr("a OR b AND c"));
        }

        @Test
        @DisplayName("comparison binds tighter than AND")
        void comparisonOverAnd() {
            assertEquals("((a < b) AND (c > d))", expr("a < b AND c > d"));
        }

        @Test
        @DisplayName("multiplication binds tighter than addition")
        void mulOverAdd() {
            assertEquals("(1 + (2 * 3))", expr("1 + 2 * 3"));
        }

        @Test
        @DisplayName("arithmetic binds tighter than comparison")
        void arithmeticOverComparison() {
            assertEquals("((CLOSE + 5) > (OPEN * 2))", expr("CLOSE + 5 > OPEN * 2"));
        }

        @Test
        @DisplayName("same-power operators associate left")
        void leftAssociativity() {
            assertEquals("((1 - 2) - 3)", expr("1 - 2 - 3"));
            assertEquals("((10 / 2) / 5)", expr("10 / 2 / 5"));
        }

        @Test
        @DisplayName("parentheses override precedence")
        void parentheses() {
            assertEquals("((1 + 2) * 3)", expr("(1 + 2) * 3"));
        }

        @Test
        @DisplayName("NOT grabs a whole comparison but not an AND")
        void notPrecedence() {
            assertEquals("NOT (a < b)", expr("NOT a < b"));
            assertEquals("(NOT a AND b)", expr("NOT a AND b"));
        }

        @Test
        @DisplayName("unary minus binds tighter than multiplication")
        void unaryMinus() {
            assertEquals("(-2 * 3)", expr("-2 * 3"));
            assertEquals("(-2 + 3)", expr("-2 + 3"));
        }

        @Test
        @DisplayName("the flagship condition parses to the expected shape")
        void flagship() {
            assertEquals(
                    "((RSI(14) < 30) AND (CLOSE > SMA(CLOSE, 200)))",
                    expr("RSI(14) < 30 AND CLOSE > SMA(CLOSE, 200)"));
        }
    }

    // ── Calls & lookback ────────────────────────────────────────────────

    @Nested
    @DisplayName("calls and lookback")
    class CallsAndLookback {

        @Test
        @DisplayName("calls take zero, one, or many comma-separated args")
        void callArities() {
            assertEquals("VWAP()", expr("VWAP()"));
            assertEquals("RSI(14)", expr("RSI(14)"));
            assertEquals("SMA(CLOSE, 200)", expr("SMA(CLOSE, 200)"));
        }

        @Test
        @DisplayName("calls nest")
        void nestedCalls() {
            assertEquals("MAX(RSI(14), SMA(CLOSE, 50))",
                    expr("MAX(RSI(14), SMA(CLOSE, 50))"));
        }

        @Test
        @DisplayName("lookback applies to price refs, calls, and groups")
        void lookbackTargets() {
            assertEquals("CLOSE[1]", expr("CLOSE[1]"));
            assertEquals("RSI(14)[2]", expr("RSI(14)[2]"));
            assertEquals("(CLOSE - OPEN)[1]", expr("(CLOSE - OPEN)[1]"));
        }

        @Test
        @DisplayName("lookback binds tighter than arithmetic")
        void lookbackPrecedence() {
            assertEquals("(CLOSE[1] - CLOSE[2])", expr("CLOSE[1] - CLOSE[2]"));
        }

        @Test
        @DisplayName("negative lookback is rejected: no reading the future")
        void negativeLookback() {
            Lexer.LexResult lexed = new Lexer(
                    withRule("IF CLOSE[-1] > 5 THEN BUY ALL")).scan();
            Parser.ParseResult r = new Parser(lexed.tokens()).parse();
            assertTrue(r.hasErrors());
            assertTrue(r.diagnostics().stream()
                    .anyMatch(d -> d.code().equals("PAR003")));
        }
    }

    // ── Strategy structure ──────────────────────────────────────────────

    @Nested
    @DisplayName("strategy structure")
    class Structure {

        @Test
        @DisplayName("parses the full roadmap example strategy")
        void wholeStrategy() {
            StrategyAst.StrategyDecl s = parseClean("""
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

            assertEquals("RSI Mean Reversion", s.name());
            assertEquals(4, s.config().size());
            assertEquals("timeframe", s.config().get(1).key());
            assertEquals(1, s.lets().size());
            assertEquals("rsi", s.lets().get(0).name());
            assertEquals(2, s.rules().size());

            // The BUY sizing came through as 25% OF EQUITY.
            var entryIf = s.rules().get(0).body().get(0);
            var buy = (StrategyAst.Action.Buy) entryIf.thenAction();
            var sizing = (StrategyAst.Sizing.PercentOf) buy.sizing();
            assertEquals(StrategyAst.Sizing.Base.EQUITY, sizing.base());
            assertEquals(25.0, ((Expr.PercentLit) sizing.percent()).value());

            // SELL ALL came through as the All sizing.
            var exitIf = s.rules().get(1).body().get(0);
            var sell = (StrategyAst.Action.Sell) exitIf.thenAction();
            assertTrue(sell.sizing() instanceof StrategyAst.Sizing.All);
        }

        @Test
        @DisplayName("ELSE branch is captured when present, empty when not")
        void elseBranch() {
            var withElse = parseClean(withRule(
                    "IF CLOSE > 5 THEN BUY ALL ELSE SELL ALL"));
            assertTrue(withElse.rules().get(0).body().get(0)
                    .elseAction().isPresent());

            var withoutElse = parseClean(withRule("IF CLOSE > 5 THEN BUY ALL"));
            assertTrue(withoutElse.rules().get(0).body().get(0)
                    .elseAction().isEmpty());
        }

        @Test
        @DisplayName("SET actions parse target and value")
        void setAction() {
            var s = parseClean(withRule("IF CLOSE > 5 THEN SET STOPLOSS = 5%"));
            var set = (StrategyAst.Action.Set) s.rules().get(0)
                    .body().get(0).thenAction();
            assertEquals(StrategyAst.SetTarget.STOPLOSS, set.target());
            assertEquals(5.0, ((Expr.PercentLit) set.value()).value());
        }

        @Test
        @DisplayName("fixed-quantity sizing parses without OF")
        void quantitySizing() {
            var s = parseClean(withRule("IF CLOSE > 5 THEN BUY qty = 1.5"));
            var buy = (StrategyAst.Action.Buy) s.rules().get(0)
                    .body().get(0).thenAction();
            assertTrue(buy.sizing() instanceof StrategyAst.Sizing.Quantity);
        }

        @Test
        @DisplayName("a rule may contain several IF statements")
        void multipleIfsPerRule() {
            var s = parseClean("""
                    strategy "T" {
                        rule r {
                            IF CLOSE > 5 THEN BUY ALL
                            IF CLOSE < 2 THEN SELL ALL
                        }
                    }
                    """);
            assertEquals(2, s.rules().get(0).body().size());
        }
    }

    // ── Error reporting & recovery ──────────────────────────────────────

    @Nested
    @DisplayName("errors and panic-mode recovery")
    class Errors {

        @Test
        @DisplayName("missing THEN is a PAR001 with a helpful message")
        void missingThen() {
            Parser.ParseResult r = parse(withRule("IF CLOSE > 5 BUY ALL"));
            assertTrue(r.hasErrors());
            Diagnostic d = r.diagnostics().get(0);
            assertEquals("PAR001", d.code());
            assertTrue(d.message().contains("THEN"));
        }

        @Test
        @DisplayName("BUY without a size names both accepted forms")
        void buyWithoutSize() {
            Parser.ParseResult r = parse(withRule("IF CLOSE > 5 THEN BUY"));
            assertTrue(r.diagnostics().stream().anyMatch(d ->
                    d.code().equals("PAR004")
                            && d.message().contains("BUY ALL")
                            && d.message().contains("qty")));
        }

        @Test
        @DisplayName("bad OF base is a PAR006")
        void badOfBase() {
            Parser.ParseResult r = parse(withRule(
                    "IF CLOSE > 5 THEN BUY qty = 25% OF banana"));
            assertTrue(r.diagnostics().stream()
                    .anyMatch(d -> d.code().equals("PAR006")));
        }

        @Test
        @DisplayName("an empty rule is a PAR005")
        void emptyRule() {
            Parser.ParseResult r = parse("""
                    strategy "T" { rule r { } }
                    """);
            assertTrue(r.diagnostics().stream()
                    .anyMatch(d -> d.code().equals("PAR005")));
        }

        @Test
        @DisplayName("a failed IF does NOT also report the rule as empty (no cascades)")
        void noEmptyRuleCascade() {
            // The IF has a real error (missing THEN); after recovery the
            // body is empty, but that emptiness is fallout, not a second
            // user mistake — exactly one error must be reported.
            Parser.ParseResult r = parse(withRule("IF CLOSE > 5 BUY ALL"));
            long errors = r.diagnostics().stream()
                    .filter(Diagnostic::isError).count();
            assertEquals(1, errors, "cascade detected: " + r.diagnostics());
            assertFalse(r.diagnostics().stream()
                    .anyMatch(d -> d.code().equals("PAR005")));
        }

        @Test
        @DisplayName("two broken rules yield two diagnostics — recovery works")
        void multiErrorRecovery() {
            Parser.ParseResult r = parse("""
                    strategy "T" {
                        rule a { IF CLOSE > 5 BUY ALL }
                        rule b { IF CLOSE < 2 SELL ALL }
                    }
                    """);
            long errors = r.diagnostics().stream()
                    .filter(Diagnostic::isError).count();
            assertTrue(errors >= 2,
                    "expected at least two independent errors, got: "
                            + r.diagnostics());
        }

        @Test
        @DisplayName("a broken member does not destroy its healthy siblings")
        void recoveryPreservesSiblings() {
            Parser.ParseResult r = parse("""
                    strategy "T" {
                        rule broken { IF > THEN BUY ALL }
                        rule fine { IF CLOSE > 5 THEN BUY ALL }
                    }
                    """);
            assertTrue(r.hasErrors());
            // The healthy rule still made it into the AST.
            StrategyAst.StrategyDecl s = r.strategy().orElseThrow();
            assertTrue(s.rules().stream()
                    .anyMatch(rule -> rule.name().equals("fine")));
        }

        @Test
        @DisplayName("a file that is not a strategy yields no AST but a clear error")
        void notAStrategy() {
            Parser.ParseResult r = parse("BUY ALL");
            assertTrue(r.strategy().isEmpty());
            assertTrue(r.diagnostics().get(0).message().contains("strategy"));
        }

        @Test
        @DisplayName("content after the closing brace is flagged")
        void trailingContent() {
            Parser.ParseResult r = parse("""
                    strategy "T" { rule r { IF CLOSE > 5 THEN BUY ALL } } SELL
                    """);
            assertTrue(r.diagnostics().stream()
                    .anyMatch(d -> d.code().equals("PAR008")));
        }

        @Test
        @DisplayName("diagnostics carry spans pointing into the source")
        void diagnosticSpans() {
            Parser.ParseResult r = parse(withRule("IF CLOSE > 5 BUY ALL"));
            Span span = r.diagnostics().get(0).span();
            assertEquals(3, span.startLine()); // the BUY on line 3 of the template
        }

        @Test
        @DisplayName("clean parses produce zero diagnostics")
        void cleanIsClean() {
            Parser.ParseResult r = parse(withRule("IF CLOSE > 5 THEN BUY ALL"));
            assertFalse(r.hasErrors());
            assertEquals(List.of(), r.diagnostics());
        }
    }
}