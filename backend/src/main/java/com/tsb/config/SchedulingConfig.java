package com.tsb.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Turns on {@code @Scheduled} methods.
 *
 * <p>Spring Boot does not do this by itself. Before this class existed the
 * 5-minute candle sync in {@code IngestionScheduler} was declared but never
 * ran — the candle table only moved when the startup catch-up ran, which is
 * why charts showed gaps that grew until the next restart.
 *
 * <p>Three jobs depend on it now:
 * <ul>
 *   <li>{@code IngestionScheduler} — closed candles every 5 minutes;</li>
 *   <li>{@code LivePriceFeed} + {@code PaperOrderMatcher} — the live price
 *       paper orders fill against, every 2 seconds;</li>
 *   <li>{@code CompetitionScheduler} — opens, locks and publishes
 *       competitions on time.</li>
 * </ul>
 *
 * <p>Tests that must not touch the network set
 * {@code tsb.scheduling.enabled=false}.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "tsb.scheduling.enabled", havingValue = "true",
        matchIfMissing = true)
public class SchedulingConfig {
}
