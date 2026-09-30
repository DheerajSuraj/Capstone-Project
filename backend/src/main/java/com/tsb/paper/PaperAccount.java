package com.tsb.paper;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;

/** A user's fake-money wallet. One per user, created on first use. */
@Entity
@Table(name = "paper_accounts")
public class PaperAccount {

    @Id
    private Long userId;
    private BigDecimal startingCash;
    private BigDecimal cash;
    private BigDecimal realizedPnl;
    private BigDecimal feesPaid;
    private int resets;
    private Instant resetAt;
    private Instant createdAt;
    private Instant updatedAt;

    protected PaperAccount() {
    }

    public PaperAccount(Long userId, BigDecimal startingCash) {
        this.userId = userId;
        this.startingCash = startingCash;
        this.cash = startingCash;
        this.realizedPnl = BigDecimal.ZERO;
        this.feesPaid = BigDecimal.ZERO;
        this.createdAt = Instant.now();
        this.resetAt = this.createdAt;
        this.updatedAt = this.createdAt;
    }

    /** One fill's effect on the wallet: cash in or out, the fee, any profit locked in. */
    void apply(BigDecimal cashDelta, BigDecimal fee, BigDecimal realized) {
        cash = cash.add(cashDelta);
        feesPaid = feesPaid.add(fee);
        realizedPnl = realizedPnl.add(realized);
        updatedAt = Instant.now();
    }

    void reset() {
        cash = startingCash;
        realizedPnl = BigDecimal.ZERO;
        feesPaid = BigDecimal.ZERO;
        resets++;
        resetAt = Instant.now();
        updatedAt = resetAt;
    }

    public Long getUserId() { return userId; }
    public BigDecimal getStartingCash() { return startingCash; }
    public BigDecimal getCash() { return cash; }
    public BigDecimal getRealizedPnl() { return realizedPnl; }
    public BigDecimal getFeesPaid() { return feesPaid; }
    public int getResets() { return resets; }
    public Instant getResetAt() { return resetAt; }
}
