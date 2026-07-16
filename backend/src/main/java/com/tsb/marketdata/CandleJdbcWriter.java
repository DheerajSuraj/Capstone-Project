package com.tsb.marketdata;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * The candles table's ONLY writer — and, with the reader coming in the next
 * step, one of only two classes that ever touch candle SQL (ADR-0002: no
 * ORM on the hot table, and no candle SQL scattered around the codebase).
 *
 * <p><b>Why batch upsert instead of COPY</b> (deviation from roadmap §6,
 * documented): COPY cannot skip duplicates, so every re-run — and the
 * 5-minute sync re-runs forever — would need a staging-table dance. Batched
 * {@code INSERT ... ON CONFLICT DO NOTHING} makes idempotency a property of
 * the primary key: any path can insert anything, twice, after a crash,
 * overlapping a previous load, and the table stays correct. ~900k rows load
 * in a couple of minutes; COPY is the noted optimisation if volume ever
 * grows 10x.
 */
@Component
public class CandleJdbcWriter {

    private static final int BATCH_SIZE = 2_000;

    private static final String UPSERT = """
            INSERT INTO candles (symbol_id, timeframe, open_time,
                                 open, high, low, close, volume)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT DO NOTHING
            """;

    private final JdbcTemplate jdbc;

    public CandleJdbcWriter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Creates the symbol's partition if it does not exist. Called before
     * every load; IF NOT EXISTS makes it free after the first time. This is
     * why the default partition can stay empty — every symbol gets a real
     * partition before its first row.
     */
    public void ensurePartition(long symbolId) {
        jdbc.execute(("CREATE TABLE IF NOT EXISTS candles_p%d "
                + "PARTITION OF candles FOR VALUES IN (%d)")
                .formatted(symbolId, symbolId));
    }

    /**
     * Inserts bars in batches; duplicates bounce off the primary key.
     * Returns how many rows were genuinely new.
     *
     * <p>The count comes from a before/after COUNT(*) rather than from
     * batchUpdate's return codes: with ON CONFLICT DO NOTHING, JDBC drivers
     * are permitted to return {@link java.sql.Statement#SUCCESS_NO_INFO}
     * (-2) instead of a row count, so summing the codes silently
     * under-reports. Two cheap counts beat an unreliable sum.
     */
    public int upsertBatch(long symbolId, String timeframe, List<CandleBar> bars) {
        if (bars.isEmpty()) {
            return 0;
        }
        long before = count(symbolId, timeframe);
        for (int from = 0; from < bars.size(); from += BATCH_SIZE) {
            List<CandleBar> chunk =
                    bars.subList(from, Math.min(from + BATCH_SIZE, bars.size()));
            // Note: batchUpdate(..., ParameterizedPreparedStatementSetter)
            // returns int[][] — one int[] per chunk. We ignore it (see above).
            jdbc.batchUpdate(UPSERT, chunk, chunk.size(),
                    (ps, bar) -> {
                        ps.setLong(1, symbolId);
                        ps.setString(2, timeframe);
                        ps.setTimestamp(3, Timestamp.from(bar.openTime()));
                        ps.setDouble(4, bar.open());
                        ps.setDouble(5, bar.high());
                        ps.setDouble(6, bar.low());
                        ps.setDouble(7, bar.close());
                        ps.setDouble(8, bar.volume());
                    });
        }
        return (int) (count(symbolId, timeframe) - before);
    }

    /** Newest bar we hold — where the incremental sync resumes from. */
    public Optional<Instant> latestOpenTime(long symbolId, String timeframe) {
        Timestamp ts = jdbc.queryForObject(
                "SELECT MAX(open_time) FROM candles WHERE symbol_id = ? AND timeframe = ?",
                Timestamp.class, symbolId, timeframe);
        return Optional.ofNullable(ts).map(Timestamp::toInstant);
    }

    /** Bar count for progress reporting and sanity checks. */
    public long count(long symbolId, String timeframe) {
        Long n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM candles WHERE symbol_id = ? AND timeframe = ?",
                Long.class, symbolId, timeframe);
        return n == null ? 0 : n;
    }
}