package com.tsb.marketdata;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.mockito.ArgumentMatchers.anyString;

@DisplayName("IngestionScheduler")
class IngestionSchedulerTest {

    @Test
    @DisplayName("one sweep syncs every symbol x timeframe pair exactly once")
    void sweepsAllPairs() {
        IngestionService ingestion = Mockito.mock(IngestionService.class);
        new IngestionScheduler(ingestion).syncAll();

        Mockito.verify(ingestion, Mockito.times(12))
                .syncLatest(anyString(), anyString());
        for (String ticker : IngestionScheduler.TICKERS) {
            for (String tf : IngestionScheduler.TIMEFRAMES) {
                Mockito.verify(ingestion).syncLatest(ticker, tf);
            }
        }
    }

    @Test
    @DisplayName("one pair failing does not starve the rest")
    void failureIsolation() {
        IngestionService ingestion = Mockito.mock(IngestionService.class);
        Mockito.when(ingestion.syncLatest("BTCUSDT", "5m"))
                .thenThrow(new RuntimeException("rate limited"));

        new IngestionScheduler(ingestion).syncAll(); // must not throw

        Mockito.verify(ingestion, Mockito.times(12))
                .syncLatest(anyString(), anyString());
    }
}