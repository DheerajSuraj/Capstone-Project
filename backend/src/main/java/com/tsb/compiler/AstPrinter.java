package com.tsb.compiler;

/**
 * Renders an {@link Expr} back to canonical, fully-parenthesized text —
 * {@code "((RSI(14) < 30) AND (CLOSE > SMA(CLOSE, 200)))"}. Every Binary is
 * parenthesized, so the printed form makes precedence and associativity
 * decisions VISIBLE: parser tests assert on these strings, which is far more
 * readable than comparing nested record constructors.
 *
 * <p>Also the simplest example of the pattern every pass follows: an
 * exhaustive pattern-matching switch over the sealed {@link Expr} with no
 * default branch. Add a node type and this file stops compiling until it is
 * handled.
 */
public final class AstPrinter {

    private AstPrinter() {
        // static utility
    }

    public static String print(Expr e) {
        return switch (e) {
            case Expr.NumberLit n -> trimmed(n.value());
            case Expr.PercentLit p -> trimmed(p.value()) + "%";
            case Expr.TimeframeLit t -> t.value();
            case Expr.StringLit s -> "\"" + s.value() + "\"";
            case Expr.PriceRef p -> p.field().name();
            case Expr.VarRef v -> v.name();
            case Expr.Call c -> c.name() + "(" + String.join(", ",
                    c.args().stream().map(AstPrinter::print).toList()) + ")";
            case Expr.Lookback l -> print(l.target()) + "[" + l.offset() + "]";
            case Expr.Unary u -> (u.op() == Expr.UnaryOp.NOT ? "NOT " : "-")
                    + print(u.operand());
            case Expr.Binary b -> "(" + print(b.left()) + " " + symbol(b.op())
                    + " " + print(b.right()) + ")";
        };
    }

    private static String symbol(Expr.BinaryOp op) {
        return switch (op) {
            case ADD -> "+";
            case SUB -> "-";
            case MUL -> "*";
            case DIV -> "/";
            case LT -> "<";
            case GT -> ">";
            case LE -> "<=";
            case GE -> ">=";
            case EQ -> "==";
            case NEQ -> "!=";
            case AND -> "AND";
            case OR -> "OR";
        };
    }

    /** 14.0 prints as "14"; 0.5 stays "0.5". */
    private static String trimmed(double d) {
        return d == Math.floor(d) && !Double.isInfinite(d)
                ? String.valueOf((long) d)
                : String.valueOf(d);
    }
}