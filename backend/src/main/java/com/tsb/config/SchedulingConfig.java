package com.tsb.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * Turns on {@code @Scheduled} methods.
 *
 * <p>Spring Boot does not do this by itself. Before this class existed the
 * 5-minute candle sync in {@code IngestionScheduler} was declared but never
 * ran — the candle table only moved when the startup catch-up ran, which is
 * why charts showed gaps that grew until the next restart.
 *
 * <p>Four jobs depend on it now:
 * <ul>
 *   <li>{@code IngestionScheduler} — closed candles every 5 minutes;</li>
 *   <li>{@code LivePriceFeed} — the live price paper orders fill against,
 *       every 2 seconds, and {@code PaperOrderMatcher}, which fills them;</li>
 *   <li>{@code CompetitionScheduler} — opens, runs and ends competitions;</li>
 *   <li>the refresh-token cleanup, once a day.</li>
 * </ul>
 *
 * <p><b>Several threads, not one.</b> Spring's default scheduler has a
 * single thread shared by every job, so one slow or stuck call — a Binance
 * request hanging after the laptop slept, a long competition catch-up —
 * froze prices, paper fills, candle sync and competitions together.
 *
 * <p>Tests that must not touch the network set
 * {@code tsb.scheduling.enabled=false}.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "tsb.scheduling.enabled", havingValue = "true",
        matchIfMissing = true)
public class SchedulingConfig {

    @Bean
    public TaskScheduler taskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(4);
        scheduler.setThreadNamePrefix("tsb-scheduled-");
        scheduler.setErrorHandler(t -> org.slf4j.LoggerFactory.getLogger(SchedulingConfig.class)
                .warn("scheduled job failed: {}", t.toString()));
        return scheduler;
    }
}
