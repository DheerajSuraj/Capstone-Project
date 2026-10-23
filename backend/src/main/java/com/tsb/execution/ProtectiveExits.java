package com.tsb.execution;

/**
 * Where a long position's protective orders (stop-loss, trailing stop,
 * take-profit) fill on one candle. Shared by {@link Backtester} and the
 * competition engine, so a backtest and a live competition can never fill
 * the same candle differently.
 *
 * <p><b>The honest-fill rules</b>, in the order they are applied:
 * <ol>
 *   <li><b>A gap fills at the open.</b> If the candle OPENS beyond a level,
 *       the waiting order fills at the open: the first price that actually
 *       existed. A stop at 95 when the candle opens at 80 sells at 80, not at
 *       a 95 the market skipped. (A take-profit gapped over fills at the
 *       higher open, which a waiting sell order really would get.)</li>
 *   <li><b>Inside a candle, assume the worse order.</b> Candles do not say
 *       whether the high or the low came first, so the falling side (stop,
 *       trailing) is checked before the take-profit.</li>
 *   <li><b>The trailing level comes from earlier candles only.</b> It is set
 *       by the highest price BEFORE this candle (the entry price on the entry
 *       candle). This candle's own high raises it for the next candle. Using
 *       this candle's high straight away would assume the high came before
 *       the low — the optimistic guess rule 2 forbids.</li>
 *   <li><b>Two stops below the price:</b> on the way down the price meets
 *       the higher one first, so that one fills.</li>
 * </ol>
 *
 * <p>Inside a candle, prices are assumed to move continuously (no jumps
 * between the open and the close), the same assumption every bar-based
 * backtester makes. Only replaying smaller candles could do better.
 */
public final class ProtectiveExits {

    /** A protective fill: the price and which order it was. */
    public record Exit(double price, Trade.ExitReason reason) {
    }

    private ProtectiveExits() {
    }

    /**
     * Checks one candle for a long position.
     *
     * @param entryPrice    the position's entry price
     * @param peakBefore    highest price since entry, from candles BEFORE this
     *                      one (the entry price on the entry candle)
     * @param stopPct       stop-loss distance in percent (5.0 = 5%), 0 = off
     * @param trailingPct   trailing-stop distance in percent, 0 = off
     * @param takeProfitPct take-profit distance in percent, 0 = off
     * @return the fill, or null if nothing triggered on this candle
     */
    public static Exit check(double entryPrice, double peakBefore,
                             double stopPct, double trailingPct, double takeProfitPct,
                             double open, double high, double low) {
        double stop = stopPct > 0 ? entryPrice * (1 - stopPct / 100.0) : Double.NaN;
        double trail = trailingPct > 0 ? peakBefore * (1 - trailingPct / 100.0) : Double.NaN;

        // Rule 4: the stop the price reaches first on the way down.
        double down = Double.NaN;
        Trade.ExitReason downReason = null;
        if (!Double.isNaN(stop) && (Double.isNaN(trail) || stop >= trail)) {
            down = stop;
            downReason = Trade.ExitReason.STOPLOSS;
        } else if (!Double.isNaN(trail)) {
            down = trail;
            downReason = Trade.ExitReason.TRAILING;
        }
        double up = takeProfitPct > 0
                ? entryPrice * (1 + takeProfitPct / 100.0) : Double.NaN;

        // Rule 1: at the open. If the candle opened past a level, that order
        // was triggered by the very first price of the candle.
        if (!Double.isNaN(down) && open <= down) {
            return new Exit(open, downReason);
        }
        if (!Double.isNaN(up) && open >= up) {
            return new Exit(open, Trade.ExitReason.TAKEPROFIT);
        }

        // Rule 2: inside the candle, the falling side first.
        if (!Double.isNaN(down) && low <= down) {
            return new Exit(down, downReason);
        }
        if (!Double.isNaN(up) && high >= up) {
            return new Exit(up, Trade.ExitReason.TAKEPROFIT);
        }
        return null;
    }
}
