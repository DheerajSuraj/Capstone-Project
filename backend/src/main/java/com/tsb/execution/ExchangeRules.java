package com.tsb.execution;

/**
 * The exchange's lot constraints, extracted from the symbols table by the
 * caller (the engine stays free of marketdata imports for its money math).
 * These make backtested orders PLACEABLE: a fill the real exchange would
 * reject is a fill that never happened.
 *
 * @param stepSize    minimum quantity increment; orders round DOWN to it
 * @param minNotional minimum order value in quote currency; smaller orders
 *                    are skipped entirely, never rounded up (rounding up
 *                    would trade money the strategy didn't ask to commit)
 */
public record ExchangeRules(double stepSize, double minNotional) {

    /** Lenient rules for tests and synthetic runs. */
    public static ExchangeRules none() {
        return new ExchangeRules(1e-9, 0);
    }

    /** Rounds a desired quantity down to the step grid. A count of steps
     *  that is a whole number but for floating-point noise snaps to it —
     *  the classic trap where 0.3/0.1 = 2.9999... would floor to 2 steps
     *  instead of 3. The tolerance grows with the count: with a tiny step,
     *  2 / 1e-9 comes out a hair under 2,000,000,000 steps, and a fixed
     *  tolerance floored {@code BUY qty = 2} to 1.999999999. */
    public double roundQty(double qty) {
        double steps = qty / stepSize;
        double nearest = Math.rint(steps);
        if (Math.abs(steps - nearest) <= Math.max(1e-9, 1e-12 * Math.abs(nearest))) {
            steps = nearest;
        }
        double whole = Math.floor(steps);
        // Steps like 0.00001 are 1/100000: dividing by the whole number
        // gives 0.09583 exactly-rounded, where multiplying by the inexact
        // 0.00001 gave 0.09583000000000001 in every trade list.
        double perUnit = Math.rint(1 / stepSize);
        return perUnit >= 1 && Math.abs(perUnit * stepSize - 1) < 1e-9
                ? whole / perUnit
                : whole * stepSize;
    }

    public boolean meetsMinNotional(double qty, double price) {
        return qty * price >= minNotional;
    }
}