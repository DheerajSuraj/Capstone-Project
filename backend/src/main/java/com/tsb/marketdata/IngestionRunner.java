package com.tsb.marketdata;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * One-shot backfill trigger. Dormant by default — the bean only exists when
 * {@code tsb.ingest.backfill=true}, so normal startups do nothing and the
 * request path stays network-free. Run it deliberately:
 *
 * <pre>
 * .\mvnw.cmd spring-boot:run "-Dspring-boot.run.arguments=--tsb.ingest.backfill=true --tsb.ingest.months=24"
 * </pre>
 *
 * A property-gated CommandLineRunner instead of an admin endpoint because
 * backfill is an operator action, not an application feature: it should
 * require shell access, be impossible to trigger over HTTP, and exit
 * visibly in the console where progress is being watched.
 */
@Component
@ConditionalOnProperty(name = "tsb.ingest.backfill", havingValue = "true")
public class IngestionRunner implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(IngestionRunner.class);

    private static final List<String> TICKERS =
            List.of("BTCUSDT", "ETHUSDT", "SOLUSDT");
    private static final List<String> TIMEFRAMES =
            List.of("5m", "15m", "1h", "4h");

    private final IngestionService ingestion;
    private final int months;

    public IngestionRunner(IngestionService ingestion,
                           @Value("${tsb.ingest.months:24}") int months) {
        this.ingestion = ingestion;
        this.months = months;
    }

    @Override
    public void run(String... args) {
        long start = System.currentTimeMillis();
        log.info("backfill starting: {} x {} for {} months",
                TICKERS, TIMEFRAMES, months);
        for (String ticker : TICKERS) {
            for (String timeframe : TIMEFRAMES) {
                ingestion.backfill(ticker, timeframe, months);
            }
        }
        log.info("backfill finished in {}s",
                (System.currentTimeMillis() - start) / 1000);
    }
}