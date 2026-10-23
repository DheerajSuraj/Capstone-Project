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
     * @param volume     the candle's volume, in the coin
     * @param changePct  close against the previous candle's close, percent
     *                   (null on the first candle)
     * @param book       the account at this close
     * @param fills      every buy and sell that happened during this candle
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
            String summary,
            double volume,
            Double changePct,
            Book book,
            List<Fill> fills
    ) {
    }

    /**
     * The account at one candle's close — what TradAO's cash-usage view
     * tracks (Tsang et al., IEEE VIS 2020): the net asset value, the cash
     * still free, and the coins held (the inventory).
     *
     * @param equity          net asset value: cash + coins x close
     * @param cash            cash not in the coin ("available cash")
     * @param qty             coins held
     * @param positionValue   qty x close
     * @param cashUsagePct    share of the net asset value in the coin, percent
     * @param entryPrice      what the open position was bought at; null when flat
     * @param unrealizedPnl   qty x (close - entry), before the selling fee; null when flat
     * @param unrealizedPct   close against the entry price, percent; null when flat
     * @param peakEquity      highest net asset value so far
     * @param drawdownPct     how far below that peak, percent
     * @param returnPct       net asset value against the starting capital, percent
     * @param buyHoldPct      buy &amp; hold over the same candles, percent; null if
     *                        it never gets to buy
     * @param realizedPnl     profit of the trades closed so far, fees included
     * @param closedTrades    trades closed so far
     * @param stopPrice       stop-loss level from the next candle on; null if none
     * @param takeProfitPrice take-profit level from the next candle on; null if none
     * @param trailingPrice   trailing-stop level from the next candle on; null if none
     */
    public record Book(
            double initialCapital,
            double equity,
            double cash,
            double qty,
            double positionValue,
            double cashUsagePct,
            Double entryPrice,
            Long entryTimeMillis,
            Double unrealizedPnl,
            Double unrealizedPct,
            double peakEquity,
            double drawdownPct,
            double returnPct,
            Double buyHoldPct,
            double realizedPnl,
            int closedTrades,
            Double stopPrice,
            Double takeProfitPrice,
            Double trailingPrice
    ) {
    }

    /**
     * One buy or sell during the candle.
     *
     * @param side   BUY or SELL
     * @param when   OPEN (an order from the last close, or a stop the price
     *               gapped through), INSIDE (a stop or target hit during the
     *               candle) or CLOSE (the data ended: the position is valued
     *               out at the last close)
     * @param reason SIGNAL, STOPLOSS, TAKEPROFIT, TRAILING or END_OF_DATA
     * @param pnl    for a SELL: profit of the coins sold, fees included
     */
    public record Fill(
            String side,
            String when,
            double price,
            double qty,
            double fee,
            String reason,
            Double pnl
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
        /** Condition false and no ELSE, or no answer at all (missing data:
         *  neither THEN nor ELSE runs) — nothing was asked for. */
        NO_ACTION,
        /** The order filled at the next bar's open. */
        FILLED,
        /** BUY asked for while a position was already open. */
        IGNORED_ALREADY_LONG,
        /** SELL asked for while flat. */
        IGNORED_NOTHING_TO_SELL,
        /** Order smaller than the exchange's step size / minimum value. */
        REJECTED_TOO_SMALL,
        /** A fixed-size BUY that cost more than the cash available. */
        REJECTED_NOT_ENOUGH_CASH,
        /** Signal on the final bar: there is no next open to fill at. */
        NOT_FILLED_LAST_BAR,
        /** A SET (stop-loss, take-profit, trailing) took effect immediately. */
        SETTING_APPLIED
    }
}
