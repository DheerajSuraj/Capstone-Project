package com.tsb.marketdata;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;

/**
 * The candles table's ONLY reader (its only writer is
 * {@link CandleJdbcWriter}; together they are the complete SQL surface of
 * the hot table, per ADR-0002). Loads a symbol/timeframe run straight into
 * the primitive columns of a {@link CandleSeries} — no row objects exist at
 * any point.
 *
 * <p><b>The count-then-LIMIT snapshot:</b> we count first to preallocate
 * exact-size arrays, then select. Between those two statements the 5-minute
 * sync job may APPEND a bar — which would overflow the fill loop. The
 * {@code LIMIT :count} clause pins the select to the allocated size, making
 * the two-query read behave like a snapshot regardless of concurrent
 * appends. (Appends are the only concurrent write that exists: candles are
 * never updated or deleted.)
 */
@Repository
public class CandleRepository {

    private final JdbcTemplate jdbc;

    public CandleRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Every bar we hold for a symbol/timeframe, chronological.
     *
     * <p>Deliberately its own unbounded query rather than
     * {@code loadBetween(EPOCH, someHugeSentinel)}: a sentinel far-future
     * Instant overflows Postgres's timestamptz range (max 294276 AD) and
     * the driver rejects it. "No upper bound" is expressed by having no
     * predicate, not by inventing an impossible date.
     */
    public CandleSeries loadAll(long symbolId, String timeframe) {
        return load(
                "SELECT COUNT(*) FROM candles WHERE symbol_id = ? AND timeframe = ?",
                "SELECT open_time, open, high, low, close, volume FROM candles "
                        + "WHERE symbol_id = ? AND timeframe = ? ORDER BY open_time",
                new Object[]{symbolId, timeframe});
    }

    /** Bars with {@code from <= open_time < to}, chronological. */
    public CandleSeries loadBetween(long symbolId, String timeframe,
                                    Instant from, Instant to) {
        return load(
                "SELECT COUNT(*) FROM candles WHERE symbol_id = ? AND timeframe = ? "
                        + "AND open_time >= ? AND open_time < ?",
                "SELECT open_time, open, high, low, close, volume FROM candles "
                        + "WHERE symbol_id = ? AND timeframe = ? "
                        + "AND open_time >= ? AND open_time < ? ORDER BY open_time",
                new Object[]{symbolId, timeframe,
                        Timestamp.from(from), Timestamp.from(to)});
    }

    /** Shared count-then-fill machinery for both load shapes. */
    private CandleSeries load(String countSql, String selectSql, Object[] args) {
        Integer count = jdbc.queryForObject(countSql, Integer.class, args);
        int n = count == null ? 0 : count;
        if (n == 0) {
            return CandleSeries.empty();
        }

        long[] time = new long[n];
        double[] open = new double[n];
        double[] high = new double[n];
        double[] low = new double[n];
        double[] close = new double[n];
        double[] volume = new double[n];

        int[] idx = {0};
        // LIMIT n pins the result to the size we allocated: the 5-minute sync
        // job may APPEND a bar between the count and the select, and without
        // this the fill loop would overflow. (Appends are the only concurrent
        // write: candles are never updated or deleted.)
        jdbc.query(selectSql + " LIMIT " + n,
                rs -> {
                    int i = idx[0]++;
                    time[i] = readRow(rs, open, high, low, close, volume, i);
                },
                args);

        // If a concurrent append landed between COUNT and SELECT the LIMIT
        // protects us; if rows somehow came up SHORT (cannot happen today —
        // candles are append-only — but cheap to guard), trim to what we got.
        if (idx[0] < n) {
            return new CandleSeries(
                    Arrays.copyOf(time, idx[0]), Arrays.copyOf(open, idx[0]),
                    Arrays.copyOf(high, idx[0]), Arrays.copyOf(low, idx[0]),
                    Arrays.copyOf(close, idx[0]), Arrays.copyOf(volume, idx[0]));
        }
        return new CandleSeries(time, open, high, low, close, volume);
    }

    private static long readRow(ResultSet rs, double[] open, double[] high,
                                double[] low, double[] close, double[] volume,
                                int i) throws SQLException {
        open[i] = rs.getDouble("open");
        high[i] = rs.getDouble("high");
        low[i] = rs.getDouble("low");
        close[i] = rs.getDouble("close");
        volume[i] = rs.getDouble("volume");
        return rs.getTimestamp("open_time").toInstant().toEpochMilli();
    }
}