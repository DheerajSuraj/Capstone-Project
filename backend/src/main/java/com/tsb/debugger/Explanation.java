package com.tsb.debugger;

import com.tsb.compiler.Span;

import java.util.List;

/** What the decision debugger returns: records only, no behaviour. */
public final class Explanation {

    private Explanation() {
    }

    /**
     * Why the strategy did what it did at one bar.
     *
     * @param inPosition whether a position was open at this close (after this
     *                   bar's fills and stops); null in warm-up, where the
     *                   engine did not look
     * @param summary    the whole answer in plain sentences
     */
    public record Bar(
            int bar,
            long openTimeMillis,
            double open,
            double high,
            double low,
            double close,
            int warmupBars,
            boolean warmup,
            boolean lastBar,
            Boolean inPosition,
            List<Statement> statements,
            String summary
    ) {
    }

    /**
     * One {@code IF ... THEN ... ELSE ...} at one bar.
     *
     * @param taken         which branch ran (NONE: condition false, no ELSE)
     * @param thenAction    the THEN action as TSL, e.g. {@code BUY ALL}
     * @param elseAction    the ELSE action as TSL, or null
     * @param outcome       what the engine actually did with it
     * @param fillPrice     when FILLED: the next bar's open it filled at
     * @param closestChange the single smallest change that would have flipped
     *                      this condition (null if nothing single would)
     * @param sentence      this statement's part of the summary
     */
    public record Statement(
            int ruleIndex,
            String ruleName,
            int statementIndex,
            Span span,
            ConditionNode condition,
            Branch taken,
            String thenAction,
            String elseAction,
            Outcome outcome,
            Double fillPrice,
            Long fillTimeMillis,
            ClosestChange closestChange,
            String sentence
    ) {
    }

    /**
     * "What would have had to be different?" — one leaf moved, everything
     * else held where it was (a single-feature counterfactual, in the sense
     * of Wachter et al.). It is a guide to where to look, not a promise: in
     * real data, moving one number usually moves others with it.
     */
    public record ClosestChange(
            String text,
            Span span,
            double distance,
            Double relativeDistance,
            String note
    ) {
    }

    public enum Branch { THEN, ELSE, NONE }

    public enum Outcome {
        /** Bar inside the warm-up: the engine did not evaluate rules. */
        WARMUP,
        /** Condition false and no ELSE — nothing was asked for. */
        NO_ACTION,
        /** The order filled at the next bar's open. */
        FILLED,
        /** BUY asked for while a position was already open. */
        IGNORED_ALREADY_LONG,
        /** SELL asked for while flat. */
        IGNORED_NOTHING_TO_SELL,
        /** Order smaller than the exchange's step size / minimum value. */
        REJECTED_TOO_SMALL,
        /** Signal on the final bar: there is no next open to fill at. */
        NOT_FILLED_LAST_BAR,
        /** A SET (stop-loss, take-profit, trailing) took effect immediately. */
        SETTING_APPLIED
    }
}
