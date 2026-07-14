package com.tsb.compiler;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the AST node types — and, more importantly, a working proof of
 * the sealed-interface mechanism: {@link #print(Expr)} is an exhaustive
 * pattern-matching switch with NO default branch. If anyone adds a node
 * type to {@link Expr}, THIS FILE STOPS COMPILING until the printer handles
 * it. That is the guarantee every real pass (type checker, interpreter,
 * Pine exporter) inherits by following the same shape.
 */
@DisplayName("AST")
class AstTest {

    private static final Span S = Span.of(1, 1, 2);

    // ── The proof of mechanism: an exhaustive tree-walker ───────────────

    /**
     * Renders an expression back to readable text. Deliberately handles
     * every Expr variant with no default — javac enforces completeness.
     */
    private static String print(Expr e) {
        return switch (e) {
            case Expr.NumberLit n -> trimmed(n.value());
            case Expr.PercentLit p -> trimmed(p.value()) + "%";
            case Expr.TimeframeLit t -> t.value();
            case Expr.StringLit s -> "\"" + s.value() + "\"";
            case Expr.PriceRef p -> p.field().name();
            case Expr.VarRef v -> v.name();
            case Expr.Call c -> c.name() + "(" + String.join(", ",
                    c.args().stream().map(AstTest::print).toList()) + ")";
            case Expr.Lookback l -> print(l.target()) + "[" + l.offset() + "]";
            case Expr.Unary u -> (u.op() == Expr.UnaryOp.NOT ? "NOT " : "-")
                    + print(u.operand());
            case Expr.Binary b -> "(" + print(b.left()) + " " + symbol(b.op())
                    + " " + print(b.right()) + ")";
        };
    }

    private static String symbol(Expr.BinaryOp op) {
        return switch (op) {
            case ADD -> "+"; case SUB -> "-"; case MUL -> "*"; case DIV -> "/";
            case LT -> "<"; case GT -> ">"; case LE -> "<="; case GE -> ">=";
            case EQ -> "=="; case NEQ -> "!=";
            case AND -> "AND"; case OR -> "OR";
        };
    }

    private static String trimmed(double d) {
        return d == Math.floor(d) && !Double.isInfinite(d)
                ? String.valueOf((long) d)
                : String.valueOf(d);
    }

    // ── Expression construction ─────────────────────────────────────────

    @Nested
    @DisplayName("expressions")
    class Expressions {

        @Test
        @DisplayName("builds and prints the flagship condition")
        void flagshipCondition() {
            // RSI(14) < 30 AND CLOSE > SMA(CLOSE, 200)
            Expr rsi = new Expr.Call("RSI",
                    List.of(new Expr.NumberLit(14, S)), S);
            Expr left = new Expr.Binary(Expr.BinaryOp.LT,
                    rsi, new Expr.NumberLit(30, S), S);
            Expr sma = new Expr.Call("SMA", List.of(
                    new Expr.PriceRef(Expr.PriceField.CLOSE, S),
                    new Expr.NumberLit(200, S)), S);
            Expr right = new Expr.Binary(Expr.BinaryOp.GT,
                    new Expr.PriceRef(Expr.PriceField.CLOSE, S), sma, S);
            Expr cond = new Expr.Binary(Expr.BinaryOp.AND, left, right, S);

            assertEquals("((RSI(14) < 30) AND (CLOSE > SMA(CLOSE, 200)))",
                    print(cond));
        }

        @Test
        @DisplayName("lookback prints and validates")
        void lookback() {
            Expr prev = new Expr.Lookback(
                    new Expr.PriceRef(Expr.PriceField.CLOSE, S), 1, S);
            assertEquals("CLOSE[1]", print(prev));
        }

        @Test
        @DisplayName("negative lookback offsets are unconstructable (no reading the future)")
        void negativeLookbackRejected() {
            assertThrows(IllegalArgumentException.class,
                    () -> new Expr.Lookback(
                            new Expr.PriceRef(Expr.PriceField.CLOSE, S), -1, S));
        }

        @Test
        @DisplayName("unary NOT and negation print distinctly")
        void unary() {
            Expr not = new Expr.Unary(Expr.UnaryOp.NOT,
                    new Expr.VarRef("oversold", S), S);
            Expr neg = new Expr.Unary(Expr.UnaryOp.NEG,
                    new Expr.NumberLit(5, S), S);
            assertEquals("NOT oversold", print(not));
            assertEquals("-5", print(neg));
        }

        @Test
        @DisplayName("nodes are value objects: identical trees are equal")
        void valueSemantics() {
            Expr a = new Expr.Binary(Expr.BinaryOp.LT,
                    new Expr.NumberLit(1, S), new Expr.NumberLit(2, S), S);
            Expr b = new Expr.Binary(Expr.BinaryOp.LT,
                    new Expr.NumberLit(1, S), new Expr.NumberLit(2, S), S);
            assertEquals(a, b);
        }

        @Test
        @DisplayName("every node exposes its span")
        void spansEverywhere() {
            Expr e = new Expr.PercentLit(25, Span.of(3, 7, 10));
            assertEquals(Span.of(3, 7, 10), e.span());
        }
    }

    // ── Structural nodes ────────────────────────────────────────────────

    @Nested
    @DisplayName("strategy structure")
    class Structure {

        @Test
        @DisplayName("assembles a complete strategy tree")
        void wholeStrategy() {
            var config = List.of(
                    new StrategyAst.ConfigEntry("symbol",
                            new Expr.VarRef("BTCUSDT", S), S),
                    new StrategyAst.ConfigEntry("timeframe",
                            new Expr.TimeframeLit("1h", S), S),
                    new StrategyAst.ConfigEntry("fee",
                            new Expr.PercentLit(0.1, S), S));

            var lets = List.of(new StrategyAst.LetDecl("rsi",
                    new Expr.Call("RSI",
                            List.of(new Expr.NumberLit(14, S)), S), S));

            var entry = new StrategyAst.IfStmt(
                    new Expr.Binary(Expr.BinaryOp.LT,
                            new Expr.VarRef("rsi", S),
                            new Expr.NumberLit(30, S), S),
                    new StrategyAst.Action.Buy(
                            new StrategyAst.Sizing.PercentOf(
                                    new Expr.PercentLit(25, S),
                                    StrategyAst.Sizing.Base.EQUITY, S), S),
                    Optional.empty(), S);

            var rules = List.of(
                    new StrategyAst.RuleDecl("entry", List.of(entry), S));

            var strategy = new StrategyAst.StrategyDecl(
                    "RSI Mean Reversion", config, lets, rules, S);

            assertEquals("RSI Mean Reversion", strategy.name());
            assertEquals(3, strategy.config().size());
            assertEquals("rsi", strategy.lets().get(0).name());
            assertTrue(strategy.rules().get(0).body().get(0)
                    .elseAction().isEmpty());
        }

        @Test
        @DisplayName("actions and sizing switch exhaustively (the engine's shape)")
        void actionExhaustiveness() {
            StrategyAst.Action a = new StrategyAst.Action.Sell(
                    new StrategyAst.Sizing.All(S), S);

            // No default branch: adding an Action or Sizing variant breaks
            // this compile — the same protection the fill model relies on.
            String described = switch (a) {
                case StrategyAst.Action.Buy b -> "buy " + describe(b.sizing());
                case StrategyAst.Action.Sell s -> "sell " + describe(s.sizing());
                case StrategyAst.Action.Set s -> "set " + s.target();
            };
            assertEquals("sell everything", described);
        }

        private String describe(StrategyAst.Sizing sizing) {
            return switch (sizing) {
                case StrategyAst.Sizing.All ignored -> "everything";
                case StrategyAst.Sizing.Quantity q -> "fixed amount";
                case StrategyAst.Sizing.PercentOf p ->
                        "percent of " + p.base();
            };
        }

        @Test
        @DisplayName("SET actions carry target and value")
        void setAction() {
            var set = new StrategyAst.Action.Set(
                    StrategyAst.SetTarget.STOPLOSS,
                    new Expr.PercentLit(5, S), S);
            assertEquals(StrategyAst.SetTarget.STOPLOSS, set.target());
        }
    }
}