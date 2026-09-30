package com.tsb.competition;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * One competition: its locked rule set and where it is in its life.
 * The rule-set columns have no setters, and the database trigger
 * {@code trg_competition_rules_locked} refuses any change to them anyway.
 */
@Entity
@Table(name = "competitions")
public class Competition {

    public enum Status { OPEN, RUNNING, FINISHED, CANCELLED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String name;
    private String description;
    private Long createdBy;
    private Long symbolId;
    private String timeframe;

    private double startingCapital;
    private double feeFraction;
    private double profitTargetPct;
    private double maxDrawdownPct;
    private double dailyLossLimitPct;
    private int maxTradesPerDay;
    private int minTradingDays;
    private int maxEntriesPerUser;
    private Instant startsAt;
    private Instant endsAt;

    private String status;
    private Instant dataStart;
    private Instant lastCandle;
    private Instant finishedAt;
    private Instant createdAt;

    protected Competition() {
    }

    public Competition(String name, String description, Long createdBy, Long symbolId,
                       String timeframe, RuleSet rules, int maxEntriesPerUser,
                       Instant startsAt, Instant endsAt) {
        this.name = name;
        this.description = description == null ? "" : description;
        this.createdBy = createdBy;
        this.symbolId = symbolId;
        this.timeframe = timeframe;
        this.startingCapital = rules.startingCapital();
        this.feeFraction = rules.feeFraction();
        this.profitTargetPct = rules.profitTargetPct();
        this.maxDrawdownPct = rules.maxDrawdownPct();
        this.dailyLossLimitPct = rules.dailyLossLimitPct();
        this.maxTradesPerDay = rules.maxTradesPerDay();
        this.minTradingDays = rules.minTradingDays();
        this.maxEntriesPerUser = maxEntriesPerUser;
        this.startsAt = startsAt;
        this.endsAt = endsAt;
        this.status = Status.OPEN.name();
        this.createdAt = Instant.now();
    }

    public RuleSet rules() {
        return new RuleSet(startingCapital, feeFraction, profitTargetPct, maxDrawdownPct,
                dailyLossLimitPct, maxTradesPerDay, minTradingDays);
    }

    public Status status() {
        return Status.valueOf(status);
    }

    // ── Lifecycle ───────────────────────────────────────────────────────

    public void start(Instant dataStart) {
        this.status = Status.RUNNING.name();
        this.dataStart = dataStart;
    }

    public void advancedTo(Instant candle) {
        this.lastCandle = candle;
    }

    public void finish() {
        this.status = Status.FINISHED.name();
        this.finishedAt = Instant.now();
    }

    public void cancel() {
        this.status = Status.CANCELLED.name();
        this.finishedAt = Instant.now();
    }

    // ── Getters ─────────────────────────────────────────────────────────

    public Long getId() { return id; }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public Long getCreatedBy() { return createdBy; }
    public Long getSymbolId() { return symbolId; }
    public String getTimeframe() { return timeframe; }
    public int getMaxEntriesPerUser() { return maxEntriesPerUser; }
    public Instant getStartsAt() { return startsAt; }
    public Instant getEndsAt() { return endsAt; }
    public Instant getDataStart() { return dataStart; }
    public Instant getLastCandle() { return lastCandle; }
    public Instant getFinishedAt() { return finishedAt; }
    public Instant getCreatedAt() { return createdAt; }
}
