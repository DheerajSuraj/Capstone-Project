package com.tsb.competition;

/**
 * The rules an admin sets when creating a competition ("Setup" in the
 * sequence diagram). Saved once and locked by a database trigger: nobody —
 * not the admin, not a bug — can move the goalposts after traders join.
 *
 * <p>A limit of 0 means "no limit" for the optional rules (daily loss, max
 * trades per day, minimum trading days).
 *
 * <p><b>Prop-firm style.</b> Three choices decide how close the competition
 * is to a real funding challenge (FTMO, Topstep and the like):
 * <ul>
 *   <li>{@link DailyLoss}: hitting the daily loss limit either stops new
 *       trades until the next UTC day ({@code HALT}), or ends the entry
 *       ({@code FAIL}) — the way a prop firm closes the account;</li>
 *   <li>{@link Drawdown}: the max drawdown is measured from the best equity
 *       reached ({@code TRAILING}), or from the starting capital
 *       ({@code STATIC}, a fixed floor, like FTMO's 2-step "max loss");</li>
 *   <li>{@code passOnTarget}: pass at the first close where the target is
 *       met (with enough trading days), as a prop firm does, instead of
 *       being judged only at the end.</li>
 * </ul>
 *
 * @param startingCapital   every entry starts with exactly this much
 * @param feeFraction       per-fill fee as a fraction (0.001 = 0.1%), for every entry
 * @param profitTargetPct   return needed to pass, e.g. 10 for +10%
 * @param maxDrawdownPct    fall that eliminates the entry (from the best
 *                          equity, or from the starting capital — see {@code drawdown})
 * @param dailyLossLimitPct fall within one UTC day, from the equity the day
 *                          started with, that halts or fails the entry
 * @param maxTradesPerDay   fills per UTC day before new trades are halted
 * @param minTradingDays    UTC days with at least one fill needed to pass
 * @param dailyLoss         what hitting the daily loss limit does
 * @param drawdown          where the max drawdown is measured from
 * @param passOnTarget      pass as soon as the target and the trading days are met
 */
public record RuleSet(
        double startingCapital,
        double feeFraction,
        double profitTargetPct,
        double maxDrawdownPct,
        double dailyLossLimitPct,
        int maxTradesPerDay,
        int minTradingDays,
        DailyLoss dailyLoss,
        Drawdown drawdown,
        boolean passOnTarget
) {

    /** What hitting the daily loss limit does. */
    public enum DailyLoss {
        /** No new trades until the next UTC day; exits are always allowed. */
        HALT,
        /** The entry is out, as a prop firm closes the account. */
        FAIL
    }

    /** Where the max drawdown is measured from. */
    public enum Drawdown {
        /** From the best equity reached so far: the floor rises with every new high. */
        TRAILING,
        /** From the starting capital: a fixed floor that never moves. */
        STATIC
    }

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
        if (dailyLoss == null) {
            dailyLoss = DailyLoss.HALT;
        }
        if (drawdown == null) {
            drawdown = Drawdown.TRAILING;
        }
    }

    /**
     * The original TSB rules: the daily loss limit halts, the drawdown trails
     * the best equity, and the target is judged at the end.
     */
    public RuleSet(double startingCapital, double feeFraction, double profitTargetPct,
                   double maxDrawdownPct, double dailyLossLimitPct, int maxTradesPerDay,
                   int minTradingDays) {
        this(startingCapital, feeFraction, profitTargetPct, maxDrawdownPct,
                dailyLossLimitPct, maxTradesPerDay, minTradingDays,
                DailyLoss.HALT, Drawdown.TRAILING, false);
    }

    /** No limits at all — used to prove the engine matches the backtester. */
    static RuleSet unlimited(double capital, double fee) {
        return new RuleSet(capital, fee, 1e9, 100, 0, 0, 0);
    }

    /** The equity the max drawdown is measured from right now. */
    public double drawdownBase(double peakEquity) {
        return drawdown == Drawdown.STATIC ? startingCapital : peakEquity;
    }
}
