package com.tsb.marketdata;

import java.time.Instant;

/**
 * One OHLCV bar in transit — the ingestion pipeline's currency between
 * parser, validator, and writer. Deliberately NOT a JPA entity (ADR-0002);
 * it exists only during ingestion and is never read back in this shape.
 *
 * <p>Validation lives in the compact constructor, same "illegal states are
 * unconstructable" pattern as Span and Lookback in the compiler: a bar with
 * {@code low > high} or a non-positive price cannot exist in the program,
 * so it can never reach the database. Garbage candles poison every backtest
 * silently — a single impossible bar makes stoplosses fire on prices that
 * never happened — so this is a correctness gate, not pedantry.
 */
public record CandleBar(
        Instant openTime,
        double open,
        double high,
        double low,
        double close,
        double volume
) {

    public CandleBar {
        if (openTime == null) {
            throw new IllegalArgumentException("openTime must not be null");
        }
        if (open <= 0 || high <= 0 || low <= 0 || close <= 0) {
            throw new IllegalArgumentException(
                    "prices must be positive at " + openTime + ": o=" + open
                            + " h=" + high + " l=" + low + " c=" + close);
        }
        if (volume < 0) {
            throw new IllegalArgumentException(
                    "volume must be >= 0 at " + openTime + ": " + volume);
        }
        if (low > high
                || low > Math.min(open, close)
                || high < Math.max(open, close)) {
            throw new IllegalArgumentException(
                    "impossible OHLC at " + openTime + ": o=" + open
                            + " h=" + high + " l=" + low + " c=" + close
                            + " (need low <= open,close <= high)");
        }
    }
}