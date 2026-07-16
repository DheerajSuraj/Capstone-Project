package com.tsb.marketdata;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the pure time arithmetic of the sync path. The orchestration itself
 * (fetch -> validate -> upsert -> log) is exercised end-to-end by the real
 * backfill run — its correctness is visible in the row counts and the
 * ingestion_log, which the runbook checks.
 */
@DisplayName("IngestionService time arithmetic")
class IngestionServiceTest {

    @Test
    @DisplayName("the closed-bar cutoff is always at least one full bar in the past")
    void cutoffIsClosed() {
        for (Duration tf : Timeframes.SUPPORTED.values()) {
            Instant cutoff = IngestionService.lastClosedOpenTime(tf);
            Instant barEnd = cutoff.plus(tf);
            assertTrue(!barEnd.isAfter(Instant.now()),
                    "bar opening at cutoff must have fully closed for " + tf);
        }
    }

    @Test
    @DisplayName("cutoff is aligned to the timeframe grid")
    void cutoffAligned() {
        Duration oneHour = Duration.ofHours(1);
        Instant cutoff = IngestionService.lastClosedOpenTime(oneHour);
        assertEquals(0, cutoff.toEpochMilli() % oneHour.toMillis(),
                "1h bars open exactly on the hour");
    }

    @Test
    @DisplayName("archive file names match Binance's layout exactly")
    void archiveNames() {
        assertEquals("BTCUSDT-1h-2024-03.zip",
                BinanceClient.archiveFileName("BTCUSDT", "1h",
                        java.time.YearMonth.of(2024, 3)));
    }
}