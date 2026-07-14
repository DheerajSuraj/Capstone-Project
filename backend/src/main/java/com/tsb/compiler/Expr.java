package com.tsb.compiler;

import java.util.List;

/**
 * The expression AST. A {@code sealed} interface with {@code record}
 * implementations — the load-bearing design decision of the compiler:
 *
 * <ul>
 *   <li><b>Sealed</b> = the permitted node types are a closed, compiler-known
 *       set. Every tree-walking pass (type checker, interpreter, Pine
 *       exporter, trace recorder) switches over {@code Expr} with NO default
 *       branch, so javac enforces exhaustiveness: adding a node type is a
 *       compile error in every pass until it is handled. Passes cannot
 *       silently drift out of sync with the tree.</li>
 *   <li><b>Records</b> = immutable value objects. The same tree is safely
 *       shared by every phase, and tests compare trees with equals().</li>
 * </ul>
 *
 * <p>Every node carries a {@link Span} covering its full source extent (a
 * Binary's span runs from the start of its left operand to the end of its
 * right — built with {@link Span#merge}), so any later error can point at
 * the exact source range and, via blockId, the exact Blockly block.
 *
 * <p><b>Superset principle:</b> {@link PercentLit} and {@link TimeframeLit}
 * are expression nodes even though they are only legal in config entries and
 * position sizing. The parser deliberately accepts this superset so that
 * SEMANTIC analysis rejects misuse with a precise, friendly message
 * ("percent literals are only valid in config and sizing") instead of the
 * parser producing a generic "unexpected token". Same idea as {@link Call}:
 * the parser does not know or care whether {@code RSI} is a real indicator —
 * name resolution is a semantic concern.
 */
public sealed interface Expr
        permits Expr.NumberLit, Expr.PercentLit, Expr.TimeframeLit,
        Expr.StringLit, Expr.PriceRef, Expr.VarRef, Expr.Call,
        Expr.Lookback, Expr.Unary, Expr.Binary {

    /** Every expression knows where it came from. */
    Span span();

    // ── Literals ────────────────────────────────────────────────────────

    /** {@code 14}, {@code 0.5} */
    record NumberLit(double value, Span span) implements Expr {}

    /** {@code 25%} — value as written (25.0). Config/sizing only (semantic rule). */
    record PercentLit(double value, Span span) implements Expr {}

    /** {@code 1h} — config only (semantic rule). */
    record TimeframeLit(String value, Span span) implements Expr {}

    /** {@code "RSI Mean Reversion"} — config only (semantic rule). */
    record StringLit(String value, Span span) implements Expr {}

    // ── References ──────────────────────────────────────────────────────

    /** {@code CLOSE}, {@code VOLUME} — the closed set of price series. */
    record PriceRef(PriceField field, Span span) implements Expr {}

    /** The five price series. Typed as Series in the type system. */
    enum PriceField { OPEN, HIGH, LOW, CLOSE, VOLUME }

    /** A user name from a {@code let}: {@code rsi} in {@code IF rsi < 30}. */
    record VarRef(String name, Span span) implements Expr {}

    // ── Composite ───────────────────────────────────────────────────────

    /**
     * Any name applied to arguments: {@code RSI(14)}, {@code SMA(CLOSE, 200)},
     * {@code CROSSOVER(a, b)}, {@code MAX(x, y)} — and equally a typo like
     * {@code RSII(14)}. Whether the name is an indicator, a built-in
     * function, or unknown is resolved in semantic analysis against the
     * registry, which is what makes "did you mean 'RSI'?" errors possible.
     */
    record Call(String name, List<Expr> args, Span span) implements Expr {}

    /**
     * Historical access: {@code CLOSE[1]} is the previous bar's close,
     * {@code RSI(14)[2]} the RSI two bars ago. Offset 0 is the current bar.
     * The lexer/parser can only produce non-negative offsets (there is no
     * syntax for a negative lookback), which is one of the structural
     * guarantees behind "strategies cannot read the future".
     */
    record Lookback(Expr target, int offset, Span span) implements Expr {
        public Lookback {
            if (offset < 0) {
                throw new IllegalArgumentException(
                        "lookback offset must be >= 0, got " + offset);
            }
        }
    }

    /** {@code -x} or {@code NOT cond}. */
    record Unary(UnaryOp op, Expr operand, Span span) implements Expr {}

    enum UnaryOp { NEG, NOT }

    /**
     * All infix operations — arithmetic, comparison, and logic — share one
     * node shape. The TYPE CHECKER distinguishes them: ADD..DIV take numbers
     * to numbers, LT..NEQ take numbers to booleans, AND/OR take booleans to
     * booleans. One node, one parsing path (the Pratt loop), precise typing
     * later.
     */
    record Binary(BinaryOp op, Expr left, Expr right, Span span) implements Expr {}

    enum BinaryOp {
        // Arithmetic
        ADD, SUB, MUL, DIV,
        // Comparison
        LT, GT, LE, GE, EQ, NEQ,
        // Logic
        AND, OR
    }
}