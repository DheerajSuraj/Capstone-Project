package com.tsb.debugger;

import com.tsb.compiler.Span;

import java.util.List;

/**
 * One piece of a condition, evaluated at one bar, with its numbers.
 *
 * <p>{@code passed} is never worked out here: it is always the Interpreter's
 * answer for this sub-expression at this bar. The debugger only adds the
 * numbers around that answer — the two sides of a comparison and how far
 * apart they were. So the debugger cannot disagree with the backtest about
 * whether something was true.
 *
 * <p>Numbers are {@code null} when the value was undefined (NaN) — during
 * indicator warm-up, or a lookback past the first bar. JSON has no NaN.
 *
 * @param kind             what sort of node this is
 * @param text             the sub-expression as TSL, e.g. {@code CLOSE > SMA(CLOSE, 200)}
 * @param span             where it is in the source (and which block, if any)
 * @param passed           the Interpreter's verdict at this bar
 * @param unknown          a value it depends on was undefined, so it could not be true
 * @param left             comparisons and crossings: left side now
 * @param right            comparisons and crossings: right side now
 * @param op               comparisons: {@code >}, {@code <=}, ...
 * @param previousLeft     crossings: left side one bar earlier
 * @param previousRight    crossings: right side one bar earlier
 * @param distance         leaves: how far the left side would have to move to flip
 *                         the result (when failed), or the margin it passed by
 *                         (when passed); null when no single move flips it
 * @param relativeDistance {@code distance} as a fraction of the larger side's size
 * @param note             one plain sentence about this node
 * @param children         operands of AND / OR / NOT, or the body of a named condition
 */
public record ConditionNode(
        Kind kind,
        String text,
        Span span,
        boolean passed,
        boolean unknown,
        Double left,
        Double right,
        String op,
        Double previousLeft,
        Double previousRight,
        Double distance,
        Double relativeDistance,
        String note,
        List<ConditionNode> children
) {

    public enum Kind {
        /** {@code a > b} and friends. A leaf. */
        COMPARE,
        /** {@code CROSSOVER(a, b)}. A leaf. */
        CROSSOVER,
        /** {@code CROSSUNDER(a, b)}. A leaf. */
        CROSSUNDER,
        /** All children must pass. Chains like a AND b AND c are one node. */
        AND,
        /** Any child may pass. */
        OR,
        NOT,
        /** A named condition ({@code let trending = ...}); one child, its body. */
        LET
    }

    public boolean isLeaf() {
        return kind == Kind.COMPARE || kind == Kind.CROSSOVER
                || kind == Kind.CROSSUNDER;
    }
}
