package com.tsb.competition;

import java.util.ArrayList;
import java.util.List;

/**
 * Everything the engine needs to carry one entry from one candle to the
 * next — the "position, cash, equity, counters, status" the sequence
 * diagram loads before each candle and saves, all at once, after it.
 *
 * <p>Plain mutable fields on purpose: the engine updates it in memory for
 * the candle, and the service writes it back in one save. Money is double,
 * like the backtester, so the two engines agree to the last digit.
 */
public final class EntryState {

    public enum Status {
        /** Still in the competition. */
        ACTIVE,
        /** Broke the max drawdown rule; stopped for good. */
        ELIMINATED,
        /** Finished: hit the profit target and traded on enough days. */
        PASSED,
        /** Finished without meeting the target or the minimum days. */
        FAILED
    }

    /** An order a rule placed at a close, waiting for the next open. */
    public record Pending(boolean buy, int rule, int stmt, boolean elseBranch) {
    }

    public Status status = Status.ACTIVE;
    public String statusReason;
    /** Open time of the candle where the status changed, or -1. */
    public long statusCandle = -1;

    // ── Money and position ───────────────────────────────────────────
    public double cash;
    public double qty;
    public double entryPrice;
    public long entryTime = -1;
    public double entryFees;
    public double peakSinceEntry;
    public double stopPct;
    public double takeProfitPct;
    public double trailingPct;
    public List<Pending> pending = new ArrayList<>();

    public double equity;
    /** Best equity reached so far — drawdown is measured from here. */
    public double peakEquity;
    /** Largest drawdown seen, in percent, for the dashboard. */
    public double maxDrawdownSeen;

    // ── Daily counters (UTC days) ───────────────────────────────────
    /** Epoch day of the candle last processed, or Long.MIN_VALUE before the first. */
    public long day = Long.MIN_VALUE;
    public double dayStartEquity;
    public int tradesToday;
    public boolean tradedToday;
    public boolean haltedToday;
    public String haltReason;

    // ── Totals ──────────────────────────────────────────────────────
    public int tradingDays;
    public int tradeCount;
    /** Open time of the last candle processed, or -1. */
    public long lastCandle = -1;

    public static EntryState start(RuleSet rules) {
        EntryState s = new EntryState();
        s.cash = rules.startingCapital();
        s.equity = rules.startingCapital();
        s.peakEquity = rules.startingCapital();
        s.dayStartEquity = rules.startingCapital();
        return s;
    }

    public boolean inTrade() {
        return qty > 0;
    }

    public boolean finished() {
        return status != Status.ACTIVE;
    }

    /** Return so far against the starting capital, in percent. */
    public double returnPct(RuleSet rules) {
        return (equity / rules.startingCapital() - 1) * 100;
    }

    /** Fall from the best equity reached, in percent, right now. */
    public double drawdownPct() {
        return peakEquity > 0 ? (peakEquity - equity) / peakEquity * 100 : 0;
    }

    /** Fall since today's first candle, in percent (0 if up on the day). */
    public double dailyLossPct() {
        return dayStartEquity > 0
                ? Math.max(0, (dayStartEquity - equity) / dayStartEquity * 100) : 0;
    }

    /** Pending orders as text: side, rule, statement, branch — {@code B:0:1:T;S:1:0:E}. */
    public String pendingText() {
        StringBuilder sb = new StringBuilder();
        for (Pending p : pending) {
            if (!sb.isEmpty()) {
                sb.append(';');
            }
            sb.append(p.buy() ? 'B' : 'S').append(':').append(p.rule())
                    .append(':').append(p.stmt()).append(':')
                    .append(p.elseBranch() ? 'E' : 'T');
        }
        return sb.toString();
    }

    public static List<Pending> parsePending(String text) {
        List<Pending> out = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return out;
        }
        for (String part : text.split(";")) {
            String[] f = part.split(":");
            out.add(new Pending(f[0].equals("B"), Integer.parseInt(f[1]),
                    Integer.parseInt(f[2]), f.length > 3 && f[3].equals("E")));
        }
        return out;
    }
}
