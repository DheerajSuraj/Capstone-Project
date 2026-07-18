package com.tsb.strategy;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;

import java.time.Instant;

/**
 * One immutable snapshot of a strategy's source, guaranteed to have
 * compiled cleanly at save time (the service refuses otherwise).
 *
 * <p>{@code @Immutable} tells Hibernate to never dirty-check or UPDATE
 * this entity — the Java-side half of the append-only guarantee whose
 * database-side half is the V3 trigger. Two independent layers; the
 * integration test proves the trigger fires even when the ORM is bypassed.
 */
@Entity
@Immutable
@Table(name = "strategy_versions")
public class StrategyVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long strategyId;

    private Integer versionNumber;

    private String source;

    private String symbol;

    private String timeframe;

    private Integer warmupBars;

    private Instant createdAt;

    protected StrategyVersion() {
    }

    public StrategyVersion(Long strategyId, Integer versionNumber,
                           String source, String symbol, String timeframe,
                           Integer warmupBars) {
        this.strategyId = strategyId;
        this.versionNumber = versionNumber;
        this.source = source;
        this.symbol = symbol;
        this.timeframe = timeframe;
        this.warmupBars = warmupBars;
        this.createdAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public Long getStrategyId() {
        return strategyId;
    }

    public Integer getVersionNumber() {
        return versionNumber;
    }

    public String getSource() {
        return source;
    }

    public String getSymbol() {
        return symbol;
    }

    public String getTimeframe() {
        return timeframe;
    }

    public Integer getWarmupBars() {
        return warmupBars;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}