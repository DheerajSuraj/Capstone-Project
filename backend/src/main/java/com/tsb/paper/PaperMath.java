package com.tsb.paper;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;

/**
 * The arithmetic of paper trading — longs, shorts, partial closes and flips —
 * with no database and no clock, so every rule can be checked by hand.
 *
 * <p><b>One net position per symbol</b>, like TradingView: a BUY while short
 * first buys the short back; if it is bigger than the short, the rest opens a
 * long ("flip"). A SELL mirrors that.
 *
 * <p><b>Cash follows the coins.</b> Buying pays {@code value + fee}; selling —
 * including selling short — receives {@code value − fee}. So at any moment
 * <pre>  equity = cash + Σ (long qty × price) − Σ (short qty × price)</pre>
 * and a short's profit appears when the price falls, because buying the
 * coins back costs less than they were sold for.
 *
 * <p><b>Fees:</b> 0.1% of value on every fill. The fee paid to OPEN a
 * position is kept with it ({@code entryFees}) and charged against the profit
 * when it is closed, so realized PnL is net of the round trip's fees.
 *
 * <p><b>Leverage 1×:</b> {@link #buyingPower} — everything held, long plus
 * short, at today's price, may not exceed the account's equity. Without some
 * limit, paper shorts could be opened forever.
 */
public final class PaperMath {

    public static final BigDecimal FEE = new BigDecimal("0.001");
    public static final BigDecimal STARTING_CASH = new BigDecimal("10000");

    static final int MONEY = 8;
    static final int QTY = 12;
    private static final MathContext MC = MathContext.DECIMAL128;

    public enum Side { LONG, SHORT }

    private PaperMath() {
    }

    /** A position: flat when {@code side} is null. Quantities are positive. */
    public record Book(Side side, BigDecimal qty, BigDecimal avgPrice, BigDecimal entryFees) {
        public static final Book FLAT = new Book(null, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);

        public boolean flat() {
            return side == null || qty.signum() == 0;
        }
    }

    /**
     * What one fill did.
     *
     * @param cashDelta   cash in (+) or out (−), fee included
     * @param realizedPnl profit or loss locked in by the closing part, net of
     *                    its share of both fees (zero if nothing was closed)
     * @param closedQty   how much of an existing position this fill closed
     * @param openedQty   how much new (or added) position it opened
     */
    public record Fill(Book after, BigDecimal cashDelta, BigDecimal fee, BigDecimal realizedPnl,
                       BigDecimal closedQty, BigDecimal openedQty) {
    }

    public static Fill fill(Book before, boolean buy, BigDecimal qty, BigDecimal price) {
        BigDecimal value = notional(qty, price);
        BigDecimal fee = fee(value);
        BigDecimal cashDelta = buy ? value.add(fee).negate() : value.subtract(fee);
        Side orderSide = buy ? Side.LONG : Side.SHORT;

        // Same direction as the position (or flat): add to it.
        if (before.flat() || before.side() == orderSide) {
            BigDecimal q0 = before.flat() ? BigDecimal.ZERO : before.qty();
            BigDecimal q1 = q0.add(qty);
            BigDecimal avg = before.flat() ? price
                    : before.avgPrice().multiply(q0, MC).add(price.multiply(qty, MC))
                            .divide(q1, MONEY, RoundingMode.HALF_EVEN);
            BigDecimal fees = (before.flat() ? BigDecimal.ZERO : before.entryFees()).add(fee);
            return new Fill(new Book(orderSide, q1, avg, fees), cashDelta, fee, BigDecimal.ZERO,
                    BigDecimal.ZERO, qty);
        }

        // Opposite direction: close first, then open with whatever is left.
        BigDecimal closed = qty.min(before.qty());
        BigDecimal opened = qty.subtract(closed);
        BigDecimal perUnit = before.side() == Side.LONG
                ? price.subtract(before.avgPrice())   // sold above the buy price = profit
                : before.avgPrice().subtract(price);  // bought back below the sale price = profit
        BigDecimal entryShare = before.entryFees().multiply(closed, MC)
                .divide(before.qty(), MONEY, RoundingMode.HALF_EVEN);
        BigDecimal closeFee = fee.multiply(closed, MC).divide(qty, MONEY, RoundingMode.HALF_EVEN);
        BigDecimal realized = perUnit.multiply(closed, MC).subtract(entryShare).subtract(closeFee)
                .setScale(MONEY, RoundingMode.HALF_EVEN);

        Book after;
        if (closed.compareTo(before.qty()) < 0) {
            after = new Book(before.side(), before.qty().subtract(closed), before.avgPrice(),
                    before.entryFees().subtract(entryShare));
        } else if (opened.signum() > 0) {
            after = new Book(orderSide, opened, price, fee.subtract(closeFee)); // flipped
        } else {
            after = Book.FLAT;
        }
        return new Fill(after, cashDelta, fee, realized, closed, opened);
    }

    /** Profit or loss if closed at {@code price} now, net of the entry fees paid. */
    public static BigDecimal unrealized(Book b, BigDecimal price) {
        if (b.flat()) {
            return BigDecimal.ZERO;
        }
        BigDecimal perUnit = b.side() == Side.LONG ? price.subtract(b.avgPrice())
                : b.avgPrice().subtract(price);
        return perUnit.multiply(b.qty(), MC).subtract(b.entryFees()).setScale(MONEY, RoundingMode.HALF_EVEN);
    }

    /** Position value with its sign: long positive, short negative. */
    public static BigDecimal signedValue(Book b, BigDecimal price) {
        if (b.flat()) {
            return BigDecimal.ZERO;
        }
        BigDecimal v = notional(b.qty(), price);
        return b.side() == Side.LONG ? v : v.negate();
    }

    /** What more can be opened at 1×: equity − everything held − what open orders promise. */
    public static BigDecimal buyingPower(BigDecimal equity, BigDecimal grossExposure,
                                         BigDecimal reserved) {
        return equity.subtract(grossExposure).subtract(reserved).max(BigDecimal.ZERO);
    }

    // ── Units ───────────────────────────────────────────────────────────

    /** Round a quantity down to the exchange's step size. */
    public static BigDecimal roundQty(BigDecimal qty, BigDecimal step) {
        if (step.signum() <= 0) {
            return qty.setScale(QTY, RoundingMode.DOWN);
        }
        BigDecimal steps = qty.divide(step, 0, RoundingMode.DOWN);
        return steps.multiply(step).setScale(QTY, RoundingMode.DOWN);
    }

    /** Quantity a cash amount buys (or shorts) at a price, leaving room for the fee. */
    public static BigDecimal qtyForCash(BigDecimal cash, BigDecimal price, BigDecimal step) {
        BigDecimal perUnit = price.multiply(BigDecimal.ONE.add(FEE), MC);
        return roundQty(cash.divide(perUnit, MC), step);
    }

    public static BigDecimal notional(BigDecimal qty, BigDecimal price) {
        return qty.multiply(price, MC).setScale(MONEY, RoundingMode.HALF_EVEN);
    }

    public static BigDecimal fee(BigDecimal notional) {
        return notional.multiply(FEE, MC).setScale(MONEY, RoundingMode.HALF_EVEN);
    }
}
