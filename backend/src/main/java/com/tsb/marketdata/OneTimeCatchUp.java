package com.tsb.marketdata;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * At startup: fetch every candle closed while the server was off, then
 * repair any holes in what we hold.
 *
 * <p>It must never stop the app from starting. Offline, during a Binance
 * rate-limit ban, or from a region Binance blocks, each pair just logs a
 * warning and the 5-minute sync tries again later. (Before, one failed call
 * threw out of the runner and Spring Boot exited.) Tests switch it off with
 * {@code tsb.ingest.scheduled=false}, like the scheduler.
 */
@Configuration
@ConditionalOnProperty(name = "tsb.ingest.scheduled", havingValue = "true",
        matchIfMissing = true)
public class OneTimeCatchUp {

    private static final Logger log = LoggerFactory.getLogger(OneTimeCatchUp.class);

    @Bean
    CommandLineRunner catchUp(IngestionService ingestion) {
        return args -> {
            int total = 0;
            for (String s : IngestionScheduler.TICKERS) {
                for (String tf : IngestionScheduler.TIMEFRAMES) {
                    try {
                        int added;
                        int pair = 0;
                        // keep syncing until no more bars come back — covers the
                        // whole gap even if fetchKlines returns only 1000 at a time
                        do {
                            added = ingestion.syncLatest(s, tf);
                            pair += added;
                        } while (added > 0);
                        pair += ingestion.repairGaps(s, tf);
                        total += pair;
                        if (pair > 0) {
                            log.info("catch-up {} {}: +{} bars", s, tf, pair);
                        }
                    } catch (RuntimeException e) {
                        log.warn("catch-up {} {} failed (the 5-minute sync will retry): {}",
                                s, tf, e.toString());
                    }
                }
            }
            log.info("catch-up complete: +{} bars", total);
        };
    }
}
