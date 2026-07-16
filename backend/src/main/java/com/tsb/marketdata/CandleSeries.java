package com.tsb.marketdata;

/**
 * A contiguous run of candles as COLUMNS — the shape the execution engine
 * eats, and the whole point of ADR-0002. Six parallel primitive arrays:
 * index i across all six is one bar.
 *
 * <p>Why columns and not a List of bar objects: indicator maths reads long
 * runs of one field (200 consecutive closes for an SMA window, thousands of
 * times). In a double[] those values are contiguous memory the CPU
 * prefetches; as objects, every read is a pointer chase and a potential
 * cache miss. Same algorithm, order-of-magnitude difference in throughput.
 *
 * <p>The compact constructor validates what the engine will blindly trust:
 * all columns the same length, timestamps strictly ascending. An engine
 * consuming a malformed series produces WRONG BACKTESTS, not crashes — so
 * malformed series must be unconstructable (the Span / CandleBar pattern).
 *
 * <p>Arrays are intentionally exposed raw (records return them directly)
 * rather than defensively copied: the engine reads millions of values, and
 * a copy per access would defeat the design. The contract is read-only by
 * convention, enforced by the fact that only {@link CandleRepository}
 * constructs these and nothing else in the codebase writes to them.
 *
 * @param openTimeMillis bar open times, UTC epoch millis, strictly ascending
 */
public record CandleSeries(
        long[] openTimeMillis,
        double[] open,
        double[] high,
        double[] low,
        double[] close,
        double[] volume
) {

    public CandleSeries {
        int n = openTimeMillis.length;
        if (open.length != n || high.length != n || low.length != n
                || close.length != n || volume.length != n) {
            throw new IllegalArgumentException(
                    "all columns must have equal length; got time=" + n
                            + " o=" + open.length + " h=" + high.length
                            + " l=" + low.length + " c=" + close.length
                            + " v=" + volume.length);
        }
        for (int i = 1; i < n; i++) {
            if (openTimeMillis[i] <= openTimeMillis[i - 1]) {
                throw new IllegalArgumentException(
                        "bar times must be strictly ascending; violation at index "
                                + i + " (" + openTimeMillis[i - 1] + " -> "
                                + openTimeMillis[i] + ")");
            }
        }
    }

    /** Number of bars. */
    public int size() {
        return openTimeMillis.length;
    }

    public boolean isEmpty() {
        return openTimeMillis.length == 0;
    }

    /** An empty series — what the repository returns for no matching rows.
     *  Callers (the engine) decide whether empty is an error for them. */
    public static CandleSeries empty() {
        return new CandleSeries(new long[0], new double[0], new double[0],
                new double[0], new double[0], new double[0]);
    }
}