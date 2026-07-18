package com.tsb.execution;

import java.time.Instant;

/**
 * One completed round trip: entry fill to exit fill, with the money story.
 * Single-position semantics mean trades never overlap and never nest —
 * every trade is a clean pair, which is what the trade table, the chart
 * markers, and the debugger all rely on.
 *
 * @param entryBar   bar index of the ENTRY FILL (the open it filled at)
 * @param exitBar    bar index of the exit fill
 * @param qty        base-asset quantity (already step-size rounded)
 * @param entryPrice fill price in (next open after the signal)
 * @param exitPrice  fill price out (next open, stop level, tp level, or
 *                   last close for END_OF_DATA)
 * @param fees       total fees both sides, in quote currency
 * @param pnl        net profit in quote currency, fees included
 * @param exitReason why the position closed
 */
public record Trade(
        int entryBar,
        int exitBar,
        Instant entryTime,
        Instant exitTime,
        double qty,
        double entryPrice,
        double exitPrice,
        double fees,
        double pnl,
        ExitReason exitReason
) {

    public enum ExitReason { SIGNAL, STOPLOSS, TAKEPROFIT, TRAILING, END_OF_DATA }

    /** Return on the capital deployed, percent. */
    public double pnlPercent() {
        double deployed = qty * entryPrice;
        return deployed == 0 ? 0 : pnl / deployed * 100.0;
    }

    public boolean isWin() {
        return pnl > 0;
    }
}