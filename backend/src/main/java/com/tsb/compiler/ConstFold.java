package com.tsb.compiler;

import java.util.Optional;

/**
 * Constant folding: the value of a compile-time-constant numeric
 * expression, if it is one. {@code RSI(7 * 2)} folds to 14;
 * {@code RSI(CLOSE)} does not fold.
 *
 * <p>Promoted out of the Analyzer because the execution engine needs the
 * SAME folding to map an AST call like {@code RSI(14)} onto its
 * precomputed array key — and two implementations of the same maths is the
 * bug factory this codebase forbids. One fold, two callers (Analyzer for
 * validation and manifest building, IndicatorBank for key derivation),
 * guaranteed to agree because they are the same code.
 */
public final class ConstFold {

    public static Optional<Double> fold(Expr e) {
        return switch (e) {
            case Expr.NumberLit n -> Optional.of(n.value());
            case Expr.Unary u when u.op() == Expr.UnaryOp.NEG ->
                    fold(u.operand()).map(v -> -v);
            case Expr.Binary b -> fold(b.left()).flatMap(l ->
                    fold(b.right()).flatMap(r -> switch (b.op()) {
                        case ADD -> Optional.of(l + r);
                        case SUB -> Optional.of(l - r);
                        case MUL -> Optional.of(l * r);
                        case DIV -> r == 0 ? Optional.empty()
                                : Optional.of(l / r);
                        default -> Optional.empty();
                    }));
            default -> Optional.empty();
        };
    }

    private ConstFold() {
    }
}