package com.tsb.execution;

/**
 * A read-only window into the engine loop, for the decision debugger and
 * the signal statistics.
 *
 * <p>Why it exists: "why didn't it trade here?" has four different answers
 * and only the engine knows which one applies — the condition was false,
 * the bar was still in warm-up, the condition was true but the BUY was
 * ignored because a position was already open (or SELL while flat), or the
 * order was rejected as smaller than the exchange allows. Evaluating the
 * condition alone cannot tell the last two apart from a trade, so the
 * engine reports what it actually did instead of us re-deriving it.
 *
 * <p>An observer is told things; it cannot change them. The default,
 * {@link #NONE}, does nothing, and {@code Backtester.run(s, c, r)} uses it —
 * so a normal backtest is exactly the code path it always was.
 */
public interface BarObserver {

    BarObserver NONE = new BarObserver() {
    };

    /** What happened to an order when the next bar opened. */
    enum FillOutcome {
        FILLED,
        /** BUY while a position was already open — no pyramiding. */
        IGNORED_ALREADY_LONG,
        /** SELL while flat. */
        IGNORED_NOTHING_TO_SELL,
        /** Rounded to zero, or below the exchange's minimum order value. */
        REJECTED_TOO_SMALL
    }

    /**
     * Called at each bar's close, just before the rules are evaluated.
     *
     * @param inPosition whether a position is open at that moment — after
     *                   this bar's fills and protective exits
     */
    default void onClose(int bar, boolean inPosition) {
    }

    /**
     * Called when an order a rule placed at {@code signalBar}'s close meets
     * the next open. Orders from the final bar are never reported: there is
     * no next open for them to meet.
     *
     * @param ruleIndex index into {@code strategy.rules()}
     * @param stmtIndex index into that rule's {@code body()}
     */
    default void onOrder(int signalBar, int fillBar, int ruleIndex,
                         int stmtIndex, FillOutcome outcome) {
    }
}
