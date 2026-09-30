package com.tsb.competition;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/** A trade an entry made: saved when it opens, completed when it closes. */
@Entity
@Table(name = "competition_trades")
public class CompetitionTrade {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long entryId;
    private Instant entryTime;
    private double entryPrice;
    private double qty;
    private Instant exitTime;
    private Double exitPrice;
    private Double fees;
    private Double pnl;
    private String exitReason;

    protected CompetitionTrade() {
    }

    /** "save the open trade". */
    public static CompetitionTrade opened(Long entryId, Instant time, double price, double qty) {
        CompetitionTrade t = new CompetitionTrade();
        t.entryId = entryId;
        t.entryTime = time;
        t.entryPrice = price;
        t.qty = qty;
        return t;
    }

    /** A closed part of a position, recorded as its own finished trade. */
    public static CompetitionTrade closedPart(Long entryId, CompetitionEngine.Closed c) {
        CompetitionTrade t = opened(entryId, Instant.ofEpochMilli(c.entryTime()),
                c.entryPrice(), c.qty());
        t.close(c);
        return t;
    }

    /** "save the closed trade". */
    public void close(CompetitionEngine.Closed c) {
        this.exitTime = Instant.ofEpochMilli(c.exitTime());
        this.exitPrice = c.exitPrice();
        this.fees = c.fees();
        this.pnl = c.pnl();
        this.exitReason = c.reason();
    }

    public void reduceBy(double closedQty) {
        this.qty -= closedQty;
    }

    public Long getId() { return id; }
    public Long getEntryId() { return entryId; }
    public Instant getEntryTime() { return entryTime; }
    public double getEntryPrice() { return entryPrice; }
    public double getQty() { return qty; }
    public Instant getExitTime() { return exitTime; }
    public Double getExitPrice() { return exitPrice; }
    public Double getFees() { return fees; }
    public Double getPnl() { return pnl; }
    public String getExitReason() { return exitReason; }
    public boolean isOpen() { return exitTime == null; }
}
