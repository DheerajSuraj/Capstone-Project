package com.tsb.strategy;

import com.tsb.execution.Trade;
import com.tsb.marketdata.CandleBar;
import com.tsb.marketdata.CandleJdbcWriter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * THE full-stack test: TSL source text -> lexer -> parser -> analyzer ->
 * symbol lookup -> candle load -> indicator precompute -> interpreter ->
 * fill model -> trades. Every subsystem built since Phase 0 fires in this
 * one assertion path, against a real Postgres.
 *
 * <p>Self-contained like CandleRepositoryIT: seeds TESTUSDT with
 * hand-chosen bars, cleans up after itself, never touches ingested data.
 *
 * <p>Run deliberately: {@code mvnw test -Dtest=BacktestServiceIT}
 * (requires docker compose up -d)
 */
@Tag("integration")
@SpringBootTest
@DisplayName("BacktestService (integration, full stack)")
class BacktestServiceIT {

    @Autowired
    private BacktestService backtestService;
    @Autowired
    private CandleJdbcWriter writer;
    @Autowired
    private JdbcTemplate jdbc;

    private long symbolId;

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    @BeforeEach
    void seed() {
        jdbc.update("INSERT INTO symbols (ticker, base_asset, quote_asset, "
                + "tick_size, step_size, min_notional) "
                + "VALUES ('TESTUSDT','TEST','USDT',0.01,0.001,1.0) "
                + "ON CONFLICT (ticker) DO NOTHING");
        symbolId = jdbc.queryForObject(
                "SELECT id FROM symbols WHERE ticker = 'TESTUSDT'", Long.class);
        writer.ensurePartition(symbolId);

        // opens ~ prev close; closes: 10, 20 (signal), then fill at open 16,
        // rises to 30 -> END_OF_DATA exit.
        writer.upsertBatch(symbolId, "1h", List.of(
                bar(0, 10, 10),
                bar(1, 10, 20),   // close 20 -> signal
                bar(2, 16, 25),   // fill at open 16
                bar(3, 25, 30))); // exit END_OF_DATA at close 30
    }

    private static CandleBar bar(int hour, double open, double close) {
        double high = Math.max(open, close) + 0.5;
        double low = Math.min(open, close) - 0.5;
        return new CandleBar(T0.plusSeconds(hour * 3600L),
                open, high, low, close, 100);
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM candles WHERE symbol_id = ?", symbolId);
        jdbc.execute("DROP TABLE IF EXISTS candles_p" + symbolId);
        jdbc.update("DELETE FROM symbols WHERE id = ?", symbolId);
    }

    @Test
    @DisplayName("source text to trades: the whole platform in one call")
    void endToEnd() {
        BacktestService.Outcome outcome = backtestService.run("""
                strategy "E2E" {
                    symbol = TESTUSDT
                    timeframe = 1h
                    capital = 1000
                    rule r { IF CLOSE > 15 THEN BUY ALL }
                }
                """, null, null);

        assertTrue(outcome.ok(), () -> "expected success, got diagnostics="
                + outcome.diagnostics() + " runError=" + outcome.runError());

        var result = outcome.result().orElseThrow();
        assertEquals(1, result.trades().size());
        Trade trade = result.trades().get(0);
        assertEquals(16.0, trade.entryPrice(), 1e-9, "fill at NEXT open");
        assertEquals(Trade.ExitReason.END_OF_DATA, trade.exitReason());
        // qty rounded to step 0.001: floor(62.5) stays 62.5; equity 62.5*30.
        assertEquals(62.5 * 30, result.finalEquity(), 1e-6);
    }

    @Test
    @DisplayName("unknown symbol is a run error, not a compile diagnostic")
    void unknownSymbolIsRunError() {
        BacktestService.Outcome outcome = backtestService.run("""
                strategy "E2E" {
                    symbol = NOPEUSDT
                    timeframe = 1h
                    capital = 1000
                    rule r { IF CLOSE > 15 THEN BUY ALL }
                }
                """, null, null);

        assertTrue(outcome.diagnostics().isEmpty(),
                "the language was fine; the platform lacks data");
        assertTrue(outcome.runError().orElse("").contains("NOPEUSDT"));
    }

    @Test
    @DisplayName("a range shorter than the warm-up is refused with guidance")
    void insufficientBarsForWarmup() {
        BacktestService.Outcome outcome = backtestService.run("""
                strategy "E2E" {
                    symbol = TESTUSDT
                    timeframe = 1h
                    capital = 1000
                    rule r { IF CLOSE > SMA(CLOSE, 200) THEN BUY ALL }
                }
                """, null, null);

        assertTrue(outcome.runError().orElse("").contains("warm-up"));
    }

    @Test
    @DisplayName("compile failures pass through as diagnostics with spans")
    void compileFailurePassesThrough() {
        BacktestService.Outcome outcome =
                backtestService.run("strategy \"X\" { rule r { } }", null, null);
        assertTrue(outcome.diagnostics().stream()
                .anyMatch(d -> d.code().startsWith("PAR")
                        || d.code().startsWith("SEM")));
    }
}