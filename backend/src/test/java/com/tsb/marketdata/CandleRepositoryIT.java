package com.tsb.marketdata;

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
 * INTEGRATION test — needs the real Postgres (docker compose up -d) because
 * a repository's entire behaviour IS its SQL: partition routing, ordering,
 * range bounds, the LIMIT snapshot. Mocking JdbcTemplate here would test
 * nothing real.
 *
 * <p>Self-contained: seeds its own TESTUSDT symbol and bars, cleans up
 * after itself, and never touches the ingested market data — so it passes
 * identically on a fresh CI database and on your loaded dev database.
 *
 * <p>Run deliberately: {@code mvnw test -Dtest=CandleRepositoryIT}
 */
@Tag("integration")
@SpringBootTest
@DisplayName("CandleRepository (integration)")
class CandleRepositoryIT {

    @Autowired
    private CandleRepository repository;
    @Autowired
    private CandleJdbcWriter writer;
    @Autowired
    private JdbcTemplate jdbc;

    private long symbolId;

    @BeforeEach
    void seedTestSymbol() {
        jdbc.update("INSERT INTO symbols (ticker, base_asset, quote_asset, "
                + "tick_size, step_size, min_notional) "
                + "VALUES ('TESTUSDT','TEST','USDT',0.01,0.001,5.0) "
                + "ON CONFLICT (ticker) DO NOTHING");
        symbolId = jdbc.queryForObject(
                "SELECT id FROM symbols WHERE ticker = 'TESTUSDT'", Long.class);
        writer.ensurePartition(symbolId);

        Instant t0 = Instant.parse("2026-01-01T00:00:00Z");
        writer.upsertBatch(symbolId, "1h", List.of(
                new CandleBar(t0, 100, 110, 95, 105, 10),
                new CandleBar(t0.plusSeconds(3600), 105, 115, 100, 110, 20),
                new CandleBar(t0.plusSeconds(7200), 110, 120, 105, 115, 30),
                new CandleBar(t0.plusSeconds(10800), 115, 125, 110, 120, 40)));
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM candles WHERE symbol_id = ?", symbolId);
        jdbc.update("DELETE FROM ingestion_log WHERE symbol_id = ?", symbolId);
        jdbc.execute("DROP TABLE IF EXISTS candles_p" + symbolId);
        jdbc.update("DELETE FROM symbols WHERE id = ?", symbolId);
    }

    @Test
    @DisplayName("loads all bars as chronological columns")
    void loadsAll() {
        CandleSeries s = repository.loadAll(symbolId, "1h");
        assertEquals(4, s.size());
        assertEquals(105, s.close()[0]);
        assertEquals(120, s.close()[3]);
        assertTrue(s.openTimeMillis()[0] < s.openTimeMillis()[3]);
    }

    @Test
    @DisplayName("range bounds are inclusive-from, exclusive-to")
    void rangeBounds() {
        Instant t0 = Instant.parse("2026-01-01T00:00:00Z");
        CandleSeries s = repository.loadBetween(symbolId, "1h",
                t0.plusSeconds(3600), t0.plusSeconds(10800));
        assertEquals(2, s.size());          // bars at +1h and +2h; +3h excluded
        assertEquals(110, s.close()[0]);
        assertEquals(115, s.close()[1]);
    }

    @Test
    @DisplayName("an unknown timeframe yields the empty series, not an error")
    void emptyForNoRows() {
        assertTrue(repository.loadAll(symbolId, "4h").isEmpty());
    }

    @Test
    @DisplayName("upsert idempotency holds at the read level too")
    void rereadAfterDuplicateInsert() {
        Instant t0 = Instant.parse("2026-01-01T00:00:00Z");
        int inserted = writer.upsertBatch(symbolId, "1h", List.of(
                new CandleBar(t0, 100, 110, 95, 105, 10))); // exact duplicate
        assertEquals(0, inserted);
        assertEquals(4, repository.loadAll(symbolId, "1h").size());
    }
}