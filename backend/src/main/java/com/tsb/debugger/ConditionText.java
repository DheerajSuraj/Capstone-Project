package com.tsb.debugger;

import com.tsb.compiler.AstPrinter;
import com.tsb.compiler.Expr;

import java.util.stream.Collectors;

/**
 * Prints an expression the way a person would write it: parentheses only
 * where TSL's precedence needs them.
 *
 * <p>{@link AstPrinter} wraps every binary operation in parentheses, which is
 * right for tests (the tree's shape is unmistakable) and hard to read in a
 * debugger ({@code ((rsi < 30) AND (CLOSE > SMA(CLOSE, 200)))}). This
 * printer mirrors the parser's binding powers — OR 1, AND 3, NOT's operand
 * 5, comparisons 5, + and - 7, * and / 9, unary minus 11, lookback 13 — so
 * parsing its output gives back the same tree. The tests check exactly that.
 */
final class ConditionText {

    private static final int ATOM = 100;

    private ConditionText() {
    }

    static String print(Expr e) {
        return switch (e) {
            case Expr.Binary b -> wrap(b.left(), power(b)) + " " + symbol(b.op()) + " "
                    // Left-associative: an equally strong operator on the
                    // right must be parenthesised, as in a - (b - c).
                    + wrap(b.right(), power(b) + 1);
            case Expr.Unary u when u.op() == Expr.UnaryOp.NOT ->
                    // The parser would accept NOT a < b, but a reader should
                    // not have to know NOT's binding power to read it.
                    u.operand() instanceof Expr.Binary
                            ? "NOT (" + print(u.operand()) + ")"
                            : "NOT " + print(u.operand());
            case Expr.Unary u -> "-" + wrap(u.operand(), 11);
            case Expr.Call c -> c.name() + "(" + c.args().stream()
                    .map(ConditionText::print).collect(Collectors.joining(", ")) + ")";
            case Expr.Lookback l -> wrap(l.target(), 13) + "[" + l.offset() + "]";
            default -> AstPrinter.print(e);
        };
    }

    private static String wrap(Expr child, int minPower) {
        return power(child) < minPower ? "(" + print(child) + ")" : print(child);
    }

    private static int power(Expr e) {
        return switch (e) {
            case Expr.Binary b -> switch (b.op()) {
                case OR -> 1;
                case AND -> 3;
                case LT, GT, LE, GE, EQ, NEQ -> 5;
                case ADD, SUB -> 7;
                case MUL, DIV -> 9;
            };
            case Expr.Unary u -> u.op() == Expr.UnaryOp.NOT ? 4 : 11;
            default -> ATOM;
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
}
