package com.tsb.marketdata;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Pins the validation gate: impossible bars are unconstructable, so they
 * can never reach the database and therefore never reach a backtest.
 */
@DisplayName("CandleBar validation")
class CandleBarTest {

    private static final Instant T = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    @DisplayName("a sane bar constructs")
    void saneBar() {
        CandleBar b = new CandleBar(T, 100, 110, 95, 105, 1234.5);
        assertEquals(105, b.close());
    }

    @Test
    @DisplayName("low > high is impossible")
    void lowAboveHigh() {
        assertThrows(IllegalArgumentException.class,
                () -> new CandleBar(T, 100, 95, 110, 105, 1));
    }

    @Test
    @DisplayName("close outside [low, high] is impossible")
    void closeOutsideRange() {
        assertThrows(IllegalArgumentException.class,
                () -> new CandleBar(T, 100, 110, 95, 120, 1));
    }

    @Test
    @DisplayName("open outside [low, high] is impossible")
    void openOutsideRange() {
        assertThrows(IllegalArgumentException.class,
                () -> new CandleBar(T, 90, 110, 95, 105, 1));
    }

    @Test
    @DisplayName("non-positive prices and negative volume are impossible")
    void nonPositiveValues() {
        assertThrows(IllegalArgumentException.class,
                () -> new CandleBar(T, 0, 110, 95, 105, 1));
        assertThrows(IllegalArgumentException.class,
                () -> new CandleBar(T, 100, 110, 95, 105, -1));
    }

    @Test
    @DisplayName("a flat bar (o=h=l=c) is legal — thin markets produce them")
    void flatBar() {
        CandleBar b = new CandleBar(T, 100, 100, 100, 100, 0);
        assertEquals(100, b.high());
    }
}