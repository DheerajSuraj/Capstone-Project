package com.tsb.strategy;

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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Persistence integration: save -> version -> run -> stored row, plus the
 * append-only trigger proven AT THE DATABASE LEVEL (raw SQL UPDATE, ORM
 * fully bypassed — if this passes, no code path can rewrite history).
 *
 * <p>Run deliberately: {@code mvnw test -Dtest=StrategyServiceIT}
 * (requires docker compose up -d)
 */
@Tag("integration")
@SpringBootTest
@DisplayName("StrategyService (integration)")
class StrategyServiceIT {

    private static final String SOURCE = """
            strategy "Persisted" {
                symbol = TESTUSDT
                timeframe = 1h
                capital = 1000
                rule r { IF CLOSE > 15 THEN BUY ALL }
            }
            """;

    @Autowired
    private StrategyService service;
    @Autowired
    private CandleJdbcWriter writer;
    @Autowired
    private JdbcTemplate jdbc;

    private long symbolId;

    @BeforeEach
    void seedSymbolAndBars() {
        jdbc.update("INSERT INTO symbols (ticker, base_asset, quote_asset, "
                + "tick_size, step_size, min_notional) "
                + "VALUES ('TESTUSDT','TEST','USDT',0.01,0.001,1.0) "
                + "ON CONFLICT (ticker) DO NOTHING");
        symbolId = jdbc.queryForObject(
                "SELECT id FROM symbols WHERE ticker='TESTUSDT'", Long.class);
        writer.ensurePartition(symbolId);
        Instant t0 = Instant.parse("2026-01-01T00:00:00Z");
        writer.upsertBatch(symbolId, "1h", List.of(
                new CandleBar(t0, 10, 10.5, 9.5, 10, 100),
                new CandleBar(t0.plusSeconds(3600), 10, 20.5, 9.5, 20, 100),
                new CandleBar(t0.plusSeconds(7200), 16, 25.5, 15.5, 25, 100),
                new CandleBar(t0.plusSeconds(10800), 25, 30.5, 24.5, 30, 100)));
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM backtest_runs WHERE strategy_version_id IN "
                + "(SELECT id FROM strategy_versions WHERE strategy_id IN "
                + "(SELECT id FROM strategies WHERE name IN ('Persisted','Other')))");
        // Versions are append-only: the trigger blocks DELETE too, so tests
        // must drop it around cleanup — which itself re-proves the trigger.
        jdbc.execute("ALTER TABLE strategy_versions DISABLE TRIGGER trg_versions_append_only");
        jdbc.update("DELETE FROM strategy_versions WHERE strategy_id IN "
                + "(SELECT id FROM strategies WHERE name IN ('Persisted','Other'))");
        jdbc.execute("ALTER TABLE strategy_versions ENABLE TRIGGER trg_versions_append_only");
        jdbc.update("DELETE FROM strategies WHERE name IN ('Persisted','Other')");
        jdbc.update("DELETE FROM candles WHERE symbol_id = ?", symbolId);
        jdbc.execute("DROP TABLE IF EXISTS candles_p" + symbolId);
        jdbc.update("DELETE FROM symbols WHERE id = ?", symbolId);
    }

    @Test
    @DisplayName("create -> addVersion -> run: the persisted lifecycle")
    void lifecycle() {
        StrategyService.SaveOutcome created = service.create("Persisted", SOURCE);
        assertTrue(created.ok());
        long strategyId = created.version().orElseThrow().getStrategyId();
        assertEquals(1, created.version().orElseThrow().getVersionNumber());
        assertEquals("TESTUSDT", created.version().orElseThrow().getSymbol());

        StrategyService.SaveOutcome v2 = service.addVersion(strategyId,
                SOURCE.replace("> 15", "> 12"));
        assertEquals(2, v2.version().orElseThrow().getVersionNumber());

        StrategyService.RunOutcome run =
                service.runVersion(strategyId, 1, null, null);
        assertTrue(run.outcome().ok());
        long runId = run.runId().orElseThrow();

        List<BacktestRun> stored = service.listRuns(strategyId);
        assertEquals(1, stored.size());
        assertEquals(runId, stored.get(0).getId());
        assertEquals(1, stored.get(0).getTradeCount());
        // JSONB payloads landed and are non-trivial.
        assertTrue(stored.get(0).getTrades().contains("\"entryPrice\""));
        assertTrue(stored.get(0).getEquityCurve().contains("\"equity\""));
    }

    @Test
    @DisplayName("a version that does not compile is never persisted")
    void brokenSourceRejected() {
        StrategyService.SaveOutcome outcome =
                service.create("Other", "strategy \"X\" { rule r { } }");
        assertTrue(outcome.diagnostics().size() > 0);
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM strategies WHERE name='Other'", Integer.class);
        assertEquals(0, count, "no strategy row without a valid version 1");
    }

    @Test
    @DisplayName("THE TRIGGER: raw SQL UPDATE on a version is rejected by Postgres itself")
    void appendOnlyEnforcedByDatabase() {
        StrategyService.SaveOutcome created = service.create("Persisted", SOURCE);
        long versionId = created.version().orElseThrow().getId();

        // ORM completely bypassed — this is the strongest possible probe.
        assertThrows(Exception.class, () -> jdbc.update(
                "UPDATE strategy_versions SET source = 'hacked' WHERE id = ?",
                versionId), "the trigger must reject UPDATE");
        assertThrows(Exception.class, () -> jdbc.update(
                        "DELETE FROM strategy_versions WHERE id = ?", versionId),
                "the trigger must reject DELETE");

        String source = jdbc.queryForObject(
                "SELECT source FROM strategy_versions WHERE id = ?",
                String.class, versionId);
        assertTrue(source.contains("Persisted"), "history untouched");
    }
}