package com.tsb.competition;

/**
 * The rules an admin sets when creating a competition ("Setup" in the
 * sequence diagram). Saved once and locked by a database trigger: nobody —
 * not the admin, not a bug — can move the goalposts after traders join.
 *
 * <p>A limit of 0 means "no limit" for the optional rules (daily loss, max
 * trades per day, minimum trading days).
 *
 * @param startingCapital   every entry starts with exactly this much
 * @param feeFraction       per-fill fee as a fraction (0.001 = 0.1%), for every entry
 * @param profitTargetPct   return needed to pass, e.g. 10 for +10%
 * @param maxDrawdownPct    fall from the best equity reached that eliminates the entry
 * @param dailyLossLimitPct fall within one UTC day that halts new trades for the day
 * @param maxTradesPerDay   fills per UTC day before new trades are halted
 * @param minTradingDays    UTC days with at least one fill needed to pass
 */
public record RuleSet(
        double startingCapital,
        double feeFraction,
        double profitTargetPct,
        double maxDrawdownPct,
        double dailyLossLimitPct,
        int maxTradesPerDay,
        int minTradingDays
) {

    public RuleSet {
        if (!(startingCapital > 0)) {
            throw new IllegalArgumentException("starting capital must be positive");
        }
        if (feeFraction < 0 || feeFraction > 0.05) {
            throw new IllegalArgumentException("fee must be between 0% and 5%");
        }
        if (!(profitTargetPct > 0)) {
            throw new IllegalArgumentException("profit target must be positive");
        }
        if (!(maxDrawdownPct > 0) || maxDrawdownPct > 100) {
            throw new IllegalArgumentException("max drawdown must be between 0% and 100%");
        }
        if (dailyLossLimitPct < 0 || dailyLossLimitPct > 100) {
            throw new IllegalArgumentException("daily loss limit must be between 0% and 100%");
        }
        if (maxTradesPerDay < 0 || minTradingDays < 0) {
            throw new IllegalArgumentException("limits cannot be negative");
        }
    }

    /** No limits at all — used to prove the engine matches the backtester. */
    static RuleSet unlimited(double capital, double fee) {
        return new RuleSet(capital, fee, 1e9, 100, 0, 0, 0);
    }
}
