package com.tsb.marketdata;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Keeps the candle store fresh: every 5 minutes, top up every
 * symbol/timeframe pair from Binance via the same {@code syncLatest} the
 * manual backfill used — closed-bar cutoff and idempotent ON CONFLICT
 * writes included, so running it forever is safe by construction.
 *
 * <p>This 5-minute heartbeat is deliberately the SAME cadence the
 * competition engine will re-run strategies on: freshness for backtests
 * today, the league's pulse in a few weeks.
 *
 * <p>Disable with {@code tsb.ingest.scheduled=false} (tests do; the
 * property defaults to on for the running server).
 */
@Component
@ConditionalOnProperty(name = "tsb.ingest.scheduled", havingValue = "true",
        matchIfMissing = true)
public class IngestionScheduler {

    private static final Logger log =
            LoggerFactory.getLogger(IngestionScheduler.class);

    static final List<String> TICKERS =
            List.of("BTCUSDT", "ETHUSDT", "SOLUSDT");
    static final List<String> TIMEFRAMES =
            List.of("5m", "15m", "1h", "4h");

    private final IngestionService ingestion;

    public IngestionScheduler(IngestionService ingestion) {
        this.ingestion = ingestion;
    }

    /** fixedDelay (not fixedRate): the next run starts 5 minutes after the
     *  previous one FINISHES, so a slow Binance day can never stack
     *  overlapping syncs. */
    @Scheduled(initialDelayString = "PT15S", fixedDelayString = "PT5M")
    public void syncAll() {
        int totalBars = 0;
        for (String ticker : TICKERS) {
            for (String timeframe : TIMEFRAMES) {
                try {
                    int added = ingestion.syncLatest(ticker, timeframe);
                    totalBars += added;
                    if (added > 0) {
                        log.info("sync {} {}: +{} bars", ticker, timeframe, added);
                    }
                } catch (Exception e) {
                    // One pair failing (rate limit, network blip) must not
                    // starve the other eleven.
                    log.warn("sync {} {} failed: {}", ticker, timeframe,
                            e.getMessage());
                }
            }
        }
        log.info("scheduled sync complete: {} new bars across {} pairs",
                totalBars, TICKERS.size() * TIMEFRAMES.size());
    }
}