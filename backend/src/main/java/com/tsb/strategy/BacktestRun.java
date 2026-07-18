package com.tsb.strategy;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * One persisted backtest: which immutable version ran, over what range,
 * with what results. Headline stats are real columns (leaderboards sort on
 * them); trades and the downsampled equity curve are JSONB payloads
 * written once and read whole.
 *
 * <p>Money results ({@code finalEquity}, {@code totalFees}) are
 * {@link BigDecimal}/NUMERIC — the engine computes in double (ADR-0002),
 * but what we REPORT and store as a financial record keeps exact decimal
 * representation. Nullable metric columns are genuinely nullable: an
 * undefined Sharpe is NULL, never a fabricated 0.
 */
@Entity
@Table(name = "backtest_runs")
public class BacktestRun {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long strategyVersionId;

    private Instant fromTime;

    private Instant toTime;

    private BigDecimal finalEquity;

    private double totalReturnPct;

    private double maxDrawdownPct;

    private double winRate;

    private int tradeCount;

    private BigDecimal totalFees;

    private Double sharpeRatio;

    private Double sortinoRatio;

    private Double profitFactor;

    @JdbcTypeCode(SqlTypes.JSON)
    private String trades;

    @JdbcTypeCode(SqlTypes.JSON)
    private String equityCurve;

    private Instant createdAt;

    protected BacktestRun() {
    }

    public BacktestRun(Long strategyVersionId, Instant fromTime, Instant toTime,
                       BigDecimal finalEquity, double totalReturnPct,
                       double maxDrawdownPct, double winRate, int tradeCount,
                       BigDecimal totalFees, Double sharpeRatio,
                       Double sortinoRatio, Double profitFactor,
                       String trades, String equityCurve) {
        this.strategyVersionId = strategyVersionId;
        this.fromTime = fromTime;
        this.toTime = toTime;
        this.finalEquity = finalEquity;
        this.totalReturnPct = totalReturnPct;
        this.maxDrawdownPct = maxDrawdownPct;
        this.winRate = winRate;
        this.tradeCount = tradeCount;
        this.totalFees = totalFees;
        this.sharpeRatio = sharpeRatio;
        this.sortinoRatio = sortinoRatio;
        this.profitFactor = profitFactor;
        this.trades = trades;
        this.equityCurve = equityCurve;
        this.createdAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public Long getStrategyVersionId() {
        return strategyVersionId;
    }

    public Instant getFromTime() {
        return fromTime;
    }

    public Instant getToTime() {
        return toTime;
    }

    public BigDecimal getFinalEquity() {
        return finalEquity;
    }

    public double getTotalReturnPct() {
        return totalReturnPct;
    }

    public double getMaxDrawdownPct() {
        return maxDrawdownPct;
    }

    public double getWinRate() {
        return winRate;
    }

    public int getTradeCount() {
        return tradeCount;
    }

    public BigDecimal getTotalFees() {
        return totalFees;
    }

    public Double getSharpeRatio() {
        return sharpeRatio;
    }

    public Double getSortinoRatio() {
        return sortinoRatio;
    }

    public Double getProfitFactor() {
        return profitFactor;
    }

    public String getTrades() {
        return trades;
    }

    public String getEquityCurve() {
        return equityCurve;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}