package com.tsb.marketdata;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.Optional;

/**
 * The candles table's ONLY reader (its only writer is
 * {@link CandleJdbcWriter}; together they are the complete SQL surface of
 * the hot table, per ADR-0002). Loads a symbol/timeframe run straight into
 * the primitive columns of a {@link CandleSeries} — no row objects exist at
 * any point.
 *
 * <p><b>One query, one pass.</b> Rows stream into arrays that grow as they
 * fill. (This used to count first and then select with {@code LIMIT count};
 * a backfill committing OLDER bars between the two statements made the
 * limit cut off the newest ones. A single statement sees one consistent
 * snapshot, whatever else is writing.)
 */
@Repository
public class CandleRepository {

    private static final String COLUMNS =
            "SELECT open_time, open, high, low, close, volume FROM candles ";

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
        return load(COLUMNS + "WHERE symbol_id = ? AND timeframe = ? ORDER BY open_time",
                false, symbolId, timeframe);
    }

    /** Bars with {@code from <= open_time < to}, chronological. */
    public CandleSeries loadBetween(long symbolId, String timeframe,
                                    Instant from, Instant to) {
        return load(COLUMNS + "WHERE symbol_id = ? AND timeframe = ? "
                        + "AND open_time >= ? AND open_time < ? ORDER BY open_time",
                false, symbolId, timeframe, Timestamp.from(from), Timestamp.from(to));
    }

    /**
     * The NEWEST {@code limit} bars with {@code from <= open_time < to},
     * returned chronological. The limit is applied by the database, so a
     * chart asking for the last 20,000 five-minute candles reads 20,000 rows,
     * not all 200,000 and then throws most away.
     */
    public CandleSeries loadLatest(long symbolId, String timeframe,
                                   Instant from, Instant to, int limit) {
        return load(COLUMNS + "WHERE symbol_id = ? AND timeframe = ? "
                        + "AND open_time >= ? AND open_time < ? "
                        + "ORDER BY open_time DESC LIMIT ?",
                true, symbolId, timeframe, Timestamp.from(from), Timestamp.from(to), limit);
    }

    /** Open time of the newest bar held for this pair, if any. */
    public Optional<Instant> latestOpenTime(long symbolId, String timeframe) {
        Timestamp t = jdbc.queryForObject(
                "SELECT MAX(open_time) FROM candles WHERE symbol_id = ? AND timeframe = ?",
                Timestamp.class, symbolId, timeframe);
        return Optional.ofNullable(t).map(Timestamp::toInstant);
    }

    /** Streams rows into growing columns; reverses them if read newest-first. */
    private CandleSeries load(String sql, boolean newestFirst, Object... args) {
        Columns c = new Columns();
        jdbc.query(sql, rs -> {
            c.add(rs);
        }, args);
        if (c.n == 0) {
            return CandleSeries.empty();
        }
        if (newestFirst) {
            c.reverse();
        }
        return c.series();
    }

    /** Primitive column buffers that double when full. */
    private static final class Columns {
        long[] time = new long[1024];
        double[] open = new double[1024];
        double[] high = new double[1024];
        double[] low = new double[1024];
        double[] close = new double[1024];
        double[] volume = new double[1024];
        int n;

        void add(ResultSet rs) throws SQLException {
            if (n == time.length) {
                int cap = time.length * 2;
                time = Arrays.copyOf(time, cap);
                open = Arrays.copyOf(open, cap);
                high = Arrays.copyOf(high, cap);
                low = Arrays.copyOf(low, cap);
                close = Arrays.copyOf(close, cap);
                volume = Arrays.copyOf(volume, cap);
            }
            open[n] = rs.getDouble("open");
            high[n] = rs.getDouble("high");
            low[n] = rs.getDouble("low");
            close[n] = rs.getDouble("close");
            volume[n] = rs.getDouble("volume");
            time[n] = rs.getTimestamp("open_time").toInstant().toEpochMilli();
            n++;
        }

        void reverse() {
            for (int i = 0, j = n - 1; i < j; i++, j--) {
                long t = time[i];
                time[i] = time[j];
                time[j] = t;
                swap(open, i, j);
                swap(high, i, j);
                swap(low, i, j);
                swap(close, i, j);
                swap(volume, i, j);
            }
        }

        private static void swap(double[] a, int i, int j) {
            double t = a[i];
            a[i] = a[j];
            a[j] = t;
        }

        CandleSeries series() {
            return new CandleSeries(
                    Arrays.copyOf(time, n), Arrays.copyOf(open, n),
                    Arrays.copyOf(high, n), Arrays.copyOf(low, n),
                    Arrays.copyOf(close, n), Arrays.copyOf(volume, n));
        }
    }
}
