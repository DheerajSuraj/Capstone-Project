package com.tsb.compiler;

import java.util.List;
import java.util.OptionalLong;

/**
 * Timeframes as the language sees them: {@code 15m}, {@code 4h}, {@code 1d},
 * {@code 1w}. Pure arithmetic, shared by the analyzer (to check that an
 * {@code ON} timeframe fits the strategy's) and the engine (to build the
 * bigger candles).
 */
public final class Timeframe {

    /**
     * The timeframes {@code ON} accepts. Each one divides a UTC day evenly
     * (or, for 1w, is a whole number of days), so bigger candles always line
     * up with the strategy's own candles the way the exchange's do.
     */
    public static final List<String> ON_TIMEFRAMES = List.of(
            "15m", "30m", "1h", "2h", "4h", "6h", "8h", "12h", "1d", "1w");

    private Timeframe() {
    }

    /** Length in milliseconds of {@code 15m}, {@code 4h}, {@code 1d}, {@code 1w}… */
    public static OptionalLong millis(String tf) {
        if (tf == null || tf.length() < 2) {
            return OptionalLong.empty();
        }
        String digits = tf.substring(0, tf.length() - 1);
        if (!digits.chars().allMatch(Character::isDigit)) {
            return OptionalLong.empty();
        }
        long n;
        try {
            n = Long.parseLong(digits);
        } catch (NumberFormatException e) {
            return OptionalLong.empty();
        }
        if (n <= 0) {
            return OptionalLong.empty();
        }
        long unit = switch (tf.charAt(tf.length() - 1)) {
            case 'm' -> 60_000L;
            case 'h' -> 3_600_000L;
            case 'd' -> 86_400_000L;
            case 'w' -> 7 * 86_400_000L;
            default -> -1;
        };
        return unit < 0 ? OptionalLong.empty() : OptionalLong.of(n * unit);
    }
}
