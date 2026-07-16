package com.tsb.marketdata;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * A tradeable instrument — the "yes, this IS a JPA entity" half of
 * ADR-0002: few rows, rich meaning, read-mostly. Contrast with candles,
 * which deliberately never get a class like this.
 *
 * <p>Lot rules ({@code tickSize}/{@code stepSize}/{@code minNotional}) are
 * {@link BigDecimal}: they parameterise order rounding in the fill model,
 * and "0.00001" must mean exactly that — this is the money-adjacent side
 * of the DOUBLE-vs-NUMERIC split in the schema.
 *
 * <p>Plain getters, no setters beyond what ingestion needs: symbols are
 * effectively immutable reference data after seeding.
 */
@Entity
@Table(name = "symbols")
public class Symbol {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 20)
    private String ticker;

    @Column(name = "base_asset", nullable = false, length = 10)
    private String baseAsset;

    @Column(name = "quote_asset", nullable = false, length = 10)
    private String quoteAsset;

    @Column(nullable = false, length = 20)
    private String exchange;

    @Column(name = "tick_size", nullable = false, precision = 20, scale = 10)
    private BigDecimal tickSize;

    @Column(name = "step_size", nullable = false, precision = 20, scale = 10)
    private BigDecimal stepSize;

    @Column(name = "min_notional", nullable = false, precision = 20, scale = 10)
    private BigDecimal minNotional;

    @Column(nullable = false)
    private boolean active;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** JPA requires a no-arg constructor; protected keeps it out of the API. */
    protected Symbol() {
    }

    public Long getId() {
        return id;
    }

    public String getTicker() {
        return ticker;
    }

    public String getBaseAsset() {
        return baseAsset;
    }

    public String getQuoteAsset() {
        return quoteAsset;
    }

    public String getExchange() {
        return exchange;
    }

    public BigDecimal getTickSize() {
        return tickSize;
    }

    public BigDecimal getStepSize() {
        return stepSize;
    }

    public BigDecimal getMinNotional() {
        return minNotional;
    }

    public boolean isActive() {
        return active;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}