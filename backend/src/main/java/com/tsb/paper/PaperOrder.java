package com.tsb.paper;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;

/** One order: filled at once (market), or waiting for its price (limit). */
@Entity
@Table(name = "paper_orders")
public class PaperOrder {

    public enum Side { BUY, SELL }

    public enum Type { MARKET, LIMIT }

    public enum Status { OPEN, FILLED, CANCELLED, REJECTED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long userId;
    private Long symbolId;
    private String side;
    private String type;
    private BigDecimal qty;
    private BigDecimal limitPrice;
    private String status;
    private BigDecimal fillPrice;
    private BigDecimal fee;
    private BigDecimal realizedPnl;
    private String rejectReason;
    private Instant createdAt;
    private Instant filledAt;
    private Instant cancelledAt;

    protected PaperOrder() {
    }

    public PaperOrder(Long userId, Long symbolId, Side side, Type type, BigDecimal qty,
                      BigDecimal limitPrice) {
        this.userId = userId;
        this.symbolId = symbolId;
        this.side = side.name();
        this.type = type.name();
        this.qty = qty;
        this.limitPrice = limitPrice;
        this.status = Status.OPEN.name();
        this.createdAt = Instant.now();
    }

    void filled(BigDecimal price, BigDecimal fee, BigDecimal realizedPnl) {
        this.status = Status.FILLED.name();
        this.fillPrice = price;
        this.fee = fee;
        this.realizedPnl = realizedPnl;
        this.filledAt = Instant.now();
    }

    void cancelled() {
        this.status = Status.CANCELLED.name();
        this.cancelledAt = Instant.now();
    }

    void rejected(String reason) {
        this.status = Status.REJECTED.name();
        this.rejectReason = reason;
        this.cancelledAt = Instant.now();
    }

    public Long getId() { return id; }
    public Long getUserId() { return userId; }
    public Long getSymbolId() { return symbolId; }
    public Side side() { return Side.valueOf(side); }
    public Type type() { return Type.valueOf(type); }
    public Status status() { return Status.valueOf(status); }
    public BigDecimal getQty() { return qty; }
    public BigDecimal getLimitPrice() { return limitPrice; }
    public BigDecimal getFillPrice() { return fillPrice; }
    public BigDecimal getFee() { return fee; }
    public BigDecimal getRealizedPnl() { return realizedPnl; }
    public String getRejectReason() { return rejectReason; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getFilledAt() { return filledAt; }
    public Instant getCancelledAt() { return cancelledAt; }
}
