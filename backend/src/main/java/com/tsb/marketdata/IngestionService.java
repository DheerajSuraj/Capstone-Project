package com.tsb.marketdata;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;

/**
 * Orchestrates candle ingestion. One service, two entry points that share
 * every downstream step (validate -> partition -> upsert -> log):
 *
 * <ul>
 *   <li>{@link #backfill} — bulk-load N months of history from the monthly
 *       archives, then top up via REST to now. Run once per symbol/timeframe
 *       at setup.</li>
 *   <li>{@link #syncLatest} — fetch every bar closed since the newest one we
 *       hold. This is the method the 5-minute scheduled job will call; it is
 *       also how a machine that slept for two days catches up, because it
 *       asks the TABLE where to resume, not the clock.</li>
 * </ul>
 *
 * <p><b>The closed-bar cutoff</b> ({@link #lastClosedOpenTime}) is the one
 * line protecting the whole platform's data integrity: the currently
 * FORMING bar — whose close changes every second — must never be stored.
 * Forward competitions score against this table; it must contain only
 * finished facts.
 */
@Service
public class IngestionService {

    private static final Logger log = LoggerFactory.getLogger(IngestionService.class);

    private final BinanceClient binance;
    private final CandleJdbcWriter writer;
    private final SymbolRepository symbols;
    private final JdbcTemplate jdbc;

    public IngestionService(BinanceClient binance, CandleJdbcWriter writer,
                            SymbolRepository symbols, JdbcTemplate jdbc) {
        this.binance = binance;
        this.writer = writer;
        this.symbols = symbols;
        this.jdbc = jdbc;
    }

    /** Bulk historical load: monthly archives, then a REST top-up. */
    public void backfill(String ticker, String timeframe, int months) {
        Symbol symbol = requireSymbol(ticker);
        Timeframes.durationOf(timeframe); // validate early
        writer.ensurePartition(symbol.getId());

        YearMonth firstMonth = YearMonth.now(ZoneOffset.UTC).minusMonths(months);
        YearMonth lastArchiveMonth = YearMonth.now(ZoneOffset.UTC).minusMonths(1);

        for (YearMonth month = firstMonth; !month.isAfter(lastArchiveMonth);
             month = month.plusMonths(1)) {
            String file = BinanceClient.archiveFileName(ticker, timeframe, month);
            if (alreadyIngested(symbol.getId(), timeframe, file)) {
                log.info("skip {} (already ingested)", file);
                continue;
            }
            List<CandleBar> bars = binance.fetchMonthlyArchive(ticker, timeframe, month);
            if (bars.isEmpty()) {
                log.info("no archive for {} (symbol not listed yet?)", file);
                continue;
            }
            checkOrdering(bars, file);
            int inserted = writer.upsertBatch(symbol.getId(), timeframe, bars);
            logIngestion(symbol.getId(), timeframe, file, inserted);
            log.info("{}: {} bars parsed, {} new", file, bars.size(), inserted);
        }

        // Archives end at last month; REST covers the current month to now.
        syncLatest(ticker, timeframe);
        log.info("{} {} backfill complete: {} bars in table",
                ticker, timeframe, writer.count(symbol.getId(), timeframe));
    }

    /**
     * Incremental sync: everything closed since the newest bar we hold.
     * Idempotent and self-healing — resume point comes from the table.
     * Returns the number of new bars.
     */
    public int syncLatest(String ticker, String timeframe) {
        Symbol symbol = requireSymbol(ticker);
        Duration barLength = Timeframes.durationOf(timeframe);
        writer.ensurePartition(symbol.getId());

        Instant from = writer.latestOpenTime(symbol.getId(), timeframe)
                .map(latest -> latest.plus(barLength))
                .orElse(Instant.now().minus(Duration.ofDays(7))); // cold start
        Instant cutoff = lastClosedOpenTime(barLength);

        if (!from.isBefore(cutoff.plus(barLength))) {
            return 0; // already current
        }

        List<CandleBar> fetched = binance.fetchKlines(ticker, timeframe, from,
                cutoff.plus(barLength));
        // THE closed-bar filter: keep only bars whose full duration has
        // elapsed. The forming bar never enters the table.
        List<CandleBar> closed = fetched.stream()
                .filter(b -> !b.openTime().isAfter(cutoff))
                .toList();
        if (closed.isEmpty()) {
            return 0;
        }
        checkOrdering(closed, ticker + " " + timeframe + " sync");
        int inserted = writer.upsertBatch(symbol.getId(), timeframe, closed);
        if (inserted > 0) {
            log.info("{} {}: +{} bars (through {})", ticker, timeframe,
                    inserted, closed.get(closed.size() - 1).openTime());
        }
        return inserted;
    }

    /** Open time of the most recent FULLY CLOSED bar for this timeframe. */
    static Instant lastClosedOpenTime(Duration barLength) {
        long barMillis = barLength.toMillis();
        long now = Instant.now().toEpochMilli();
        long currentBarOpen = (now / barMillis) * barMillis; // forming bar
        return Instant.ofEpochMilli(currentBarOpen - barMillis);
    }

    /** Bars must be strictly chronological; gaps are legal (exchange
     *  maintenance) and logged, but disorder or duplicates in one payload
     *  mean corrupt source data and abort the load. */
    private static void checkOrdering(List<CandleBar> bars, String context) {
        for (int i = 1; i < bars.size(); i++) {
            if (!bars.get(i).openTime().isAfter(bars.get(i - 1).openTime())) {
                throw new IllegalStateException("non-chronological bars in "
                        + context + " at " + bars.get(i).openTime());
            }
        }
    }

    private boolean alreadyIngested(long symbolId, String timeframe, String file) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ingestion_log "
                        + "WHERE symbol_id = ? AND timeframe = ? AND source_file = ?",
                Integer.class, symbolId, timeframe, file);
        return n != null && n > 0;
    }

    private void logIngestion(long symbolId, String timeframe, String file,
                              int rows) {
        jdbc.update("INSERT INTO ingestion_log "
                        + "(symbol_id, timeframe, source_file, source_url, rows_inserted) "
                        + "VALUES (?, ?, ?, ?, ?) ON CONFLICT DO NOTHING",
                symbolId, timeframe, file,
                "https://data.binance.vision/data/spot/monthly/klines", rows);
    }

    private Symbol requireSymbol(String ticker) {
        return symbols.findByTicker(ticker).orElseThrow(() ->
                new IllegalArgumentException("unknown symbol '" + ticker
                        + "' — seed it in a migration first"));
    }
}