package com.tsb.paper;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;

/** A user's net position in one symbol: long or short, never both. */
@Entity
@Table(name = "paper_positions")
public class PaperPosition {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long userId;
    private Long symbolId;
    private String side;
    private BigDecimal qty;
    private BigDecimal avgPrice;
    private BigDecimal entryFees;
    private Instant openedAt;

    protected PaperPosition() {
    }

    public PaperPosition(Long userId, Long symbolId, PaperMath.Book book) {
        this.userId = userId;
        this.symbolId = symbolId;
        this.openedAt = Instant.now();
        set(book);
    }

    public PaperMath.Book book() {
        return new PaperMath.Book(PaperMath.Side.valueOf(side), qty, avgPrice, entryFees);
    }

    void set(PaperMath.Book b) {
        this.side = b.side().name();
        this.qty = b.qty();
        this.avgPrice = b.avgPrice();
        this.entryFees = b.entryFees();
    }

    public Long getId() { return id; }
    public Long getUserId() { return userId; }
    public Long getSymbolId() { return symbolId; }
    public Instant getOpenedAt() { return openedAt; }
}
