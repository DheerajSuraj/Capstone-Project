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

    /** Rounds a desired quantity down to the step grid. The epsilon guards
     *  the classic floating-point trap where 0.3/0.1 = 2.9999... would
     *  floor to 2 steps instead of 3. */
    public double roundQty(double qty) {
        return Math.floor(qty / stepSize + 1e-9) * stepSize;
    }

    public boolean meetsMinNotional(double qty, double price) {
        return qty * price >= minNotional;
    }
}