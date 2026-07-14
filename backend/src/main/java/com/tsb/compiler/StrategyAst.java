package com.tsb.compiler;

import java.util.List;
import java.util.Optional;

/**
 * The structural half of the AST: everything above the expression level.
 * A parsed {@code .tsl} file becomes exactly one {@link StrategyDecl}.
 *
 * <p>Same design rules as {@link Expr}: sealed hierarchies where there are
 * alternatives (actions, sizing), records everywhere, a {@link Span} on
 * every node, immutable throughout.
 *
 * <p>This class is a pure namespace — it only exists to group the
 * structural node types under one import, mirroring how {@link Expr} groups
 * the expression nodes.
 */
public final class StrategyAst {

    private StrategyAst() {
        // namespace only — never instantiated
    }

    // ── Top level ───────────────────────────────────────────────────────

    /**
     * One whole strategy:
     * {@code strategy "Name" { config... lets... rules... }}.
     * The parser preserves source order within each list; semantic analysis
     * enforces ordering rules (e.g. a let may only reference earlier lets).
     */
    public record StrategyDecl(
            String name,
            List<ConfigEntry> config,
            List<LetDecl> lets,
            List<RuleDecl> rules,
            Span span
    ) {}

    /**
     * {@code symbol = BTCUSDT}, {@code fee = 0.1%}, {@code timeframe = 1h}.
     * The value is a full {@link Expr} by the superset principle — the
     * parser accepts any expression here, and semantic analysis validates
     * per key (symbol wants an identifier-like value, fee wants percent or
     * number, capital wants a number...). Uniform shape now; precise,
     * key-specific error messages later.
     */
    public record ConfigEntry(String key, Expr value, Span span) {}

    /** {@code let rsi = RSI(14)} — a named expression, usable in rules. */
    public record LetDecl(String name, Expr value, Span span) {}

    /**
     * {@code rule entry { IF ... THEN ... }} — a named container of one or
     * more IF statements, evaluated in source order each bar.
     */
    public record RuleDecl(String name, List<IfStmt> body, Span span) {}

    /**
     * {@code IF condition THEN action [ELSE action]}.
     * ELSE is optional — {@link Optional} in the record makes "no else"
     * explicit at the type level rather than a null to forget to check.
     */
    public record IfStmt(
            Expr condition,
            Action thenAction,
            Optional<Action> elseAction,
            Span span
    ) {}

    // ── Actions ─────────────────────────────────────────────────────────

    /**
     * What a rule does when it fires. Sealed for the same exhaustiveness
     * guarantee as {@link Expr}: the interpreter, the Pine exporter, and the
     * trace recorder each switch over Action with no default, so a new
     * action type added later cannot be silently ignored by any of them.
     */
    public sealed interface Action
            permits Action.Buy, Action.Sell, Action.Set {

        Span span();

        /** {@code BUY qty = 25% OF EQUITY} / {@code BUY ALL}. */
        record Buy(Sizing sizing, Span span) implements Action {}

        /** {@code SELL ALL} / {@code SELL qty = 50% OF POSITION}. */
        record Sell(Sizing sizing, Span span) implements Action {}

        /**
         * {@code SET STOPLOSS = 5%} — configures protective exits. The value
         * is an Expr (usually a PercentLit); semantic analysis validates
         * kind and range per target.
         */
        record Set(SetTarget target, Expr value, Span span) implements Action {}
    }

    /** What a SET action configures. */
    public enum SetTarget { STOPLOSS, TAKEPROFIT, TRAILING }

    // ── Position sizing ─────────────────────────────────────────────────

    /**
     * How much to trade. Three shapes in the grammar, so three sealed
     * variants — the fill model in the execution engine switches over these
     * exhaustively to compute an order quantity.
     */
    public sealed interface Sizing
            permits Sizing.All, Sizing.Quantity, Sizing.PercentOf {

        Span span();

        /** {@code ALL} — the entire position (SELL) or all equity (BUY). */
        record All(Span span) implements Sizing {}

        /** {@code qty = 1.5} — an absolute amount; Expr per the superset
         *  principle, so {@code qty = capital / 2} parses and semantics
         *  decides whether to allow it. */
        record Quantity(Expr amount, Span span) implements Sizing {}

        /** {@code qty = 25% OF EQUITY} / {@code 50% OF POSITION}. */
        record PercentOf(Expr percent, Base base, Span span) implements Sizing {}

        /** What the percent is a percent OF. */
        enum Base { EQUITY, POSITION }
    }
}