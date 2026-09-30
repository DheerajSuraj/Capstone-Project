package com.tsb.competition;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The "Scheduler" lifeline of the sequence diagram, on the competition
 * side: every 30 seconds, ask each open or running competition to do
 * whatever is due — start, process new candles, or finish.
 *
 * <p>The candle store itself is refreshed by {@code IngestionScheduler}
 * every 5 minutes ("fetch latest candles" → "store the candle"); a new
 * candle there is what this job then picks up ("new candle available").
 * Polling the table rather than being called by the ingester means a
 * competition catches up correctly after a restart, however long it was
 * down.
 */
@Component
public class CompetitionScheduler {

    private static final Logger log = LoggerFactory.getLogger(CompetitionScheduler.class);

    private final CompetitionRunner runner;

    public CompetitionScheduler(CompetitionRunner runner) {
        this.runner = runner;
    }

    @Scheduled(initialDelayString = "PT20S", fixedDelayString = "PT30S")
    public void tick() {
        for (Long id : runner.dueIds()) {
            try {
                runner.advance(id);
            } catch (Exception e) {
                // One broken competition must not stop the others.
                log.error("competition {} could not advance: {}", id, e.getMessage(), e);
            }
        }
    }
}
