package com.tsb.competition;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * One submitted strategy in one competition: its locked identity, plus the
 * engine's state between candles. {@link #toState()} and {@link #save}
 * are the "load entry state" and "save all state at once" of the diagram.
 */
@Entity
@Table(name = "competition_entries")
public class CompetitionEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // ── Locked identity ─────────────────────────────────────────────────
    private Long competitionId;
    private Long userId;
    private Long strategyVersionId;
    private String sourceHash;
    private Instant submittedAt;

    // ── State ───────────────────────────────────────────────────────────
    private String status;
    private String statusReason;
    private Instant statusCandle;
    private double cash;
    private double qty;
    private double entryPrice;
    private Instant entryTime;
    private double entryFees;
    private double peakSinceEntry;
    private double stopPct;
    private double takeProfitPct;
    private double trailingPct;
    private String pendingOrders;
    private double equity;
    private double peakEquity;
    private double maxDrawdownSeen;
    private Long currentDay;
    private double dayStartEquity;
    private int tradesToday;
    private boolean tradedToday;
    private boolean haltedToday;
    private String haltReason;
    private int tradingDays;
    private int tradeCount;
    private Instant lastCandle;
    private Integer rankPosition;
    private Instant updatedAt;

    protected CompetitionEntry() {
    }

    public CompetitionEntry(Long competitionId, Long userId, Long strategyVersionId,
                            String sourceHash, RuleSet rules) {
        this.competitionId = competitionId;
        this.userId = userId;
        this.strategyVersionId = strategyVersionId;
        this.sourceHash = sourceHash;
        this.submittedAt = Instant.now();
        this.updatedAt = this.submittedAt;
        save(EntryState.start(rules));
    }

    /** Load: the row as the engine's in-memory state. */
    public EntryState toState() {
        EntryState s = new EntryState();
        s.status = EntryState.Status.valueOf(status);
        s.statusReason = statusReason;
        s.statusCandle = millis(statusCandle);
        s.cash = cash;
        s.qty = qty;
        s.entryPrice = entryPrice;
        s.entryTime = millis(entryTime);
        s.entryFees = entryFees;
        s.peakSinceEntry = peakSinceEntry;
        s.stopPct = stopPct;
        s.takeProfitPct = takeProfitPct;
        s.trailingPct = trailingPct;
        s.pending = EntryState.parsePending(pendingOrders);
        s.equity = equity;
        s.peakEquity = peakEquity;
        s.maxDrawdownSeen = maxDrawdownSeen;
        s.day = currentDay == null ? Long.MIN_VALUE : currentDay;
        s.dayStartEquity = dayStartEquity;
        s.tradesToday = tradesToday;
        s.tradedToday = tradedToday;
        s.haltedToday = haltedToday;
        s.haltReason = haltReason;
        s.tradingDays = tradingDays;
        s.tradeCount = tradeCount;
        s.lastCandle = millis(lastCandle);
        return s;
    }

    /** Save: every field at once, so a candle is either fully applied or not. */
    public final void save(EntryState s) {
        this.status = s.status.name();
        this.statusReason = s.statusReason;
        this.statusCandle = instant(s.statusCandle);
        this.cash = s.cash;
        this.qty = s.qty;
        this.entryPrice = s.entryPrice;
        this.entryTime = instant(s.entryTime);
        this.entryFees = s.entryFees;
        this.peakSinceEntry = s.peakSinceEntry;
        this.stopPct = s.stopPct;
        this.takeProfitPct = s.takeProfitPct;
        this.trailingPct = s.trailingPct;
        this.pendingOrders = s.pendingText();
        this.equity = s.equity;
        this.peakEquity = s.peakEquity;
        this.maxDrawdownSeen = s.maxDrawdownSeen;
        this.currentDay = s.day == Long.MIN_VALUE ? null : s.day;
        this.dayStartEquity = s.dayStartEquity;
        this.tradesToday = s.tradesToday;
        this.tradedToday = s.tradedToday;
        this.haltedToday = s.haltedToday;
        this.haltReason = s.haltReason;
        this.tradingDays = s.tradingDays;
        this.tradeCount = s.tradeCount;
        this.lastCandle = instant(s.lastCandle);
        this.updatedAt = Instant.now();
    }

    public void rankAs(Integer rank) {
        this.rankPosition = rank;
    }

    private static long millis(Instant i) {
        return i == null ? -1 : i.toEpochMilli();
    }

    private static Instant instant(long ms) {
        return ms < 0 ? null : Instant.ofEpochMilli(ms);
    }

    public Long getId() { return id; }
    public Long getCompetitionId() { return competitionId; }
    public Long getUserId() { return userId; }
    public Long getStrategyVersionId() { return strategyVersionId; }
    public String getSourceHash() { return sourceHash; }
    public Instant getSubmittedAt() { return submittedAt; }
    public Integer getRankPosition() { return rankPosition; }
}
