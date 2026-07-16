package com.tsb.marketdata;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("CandleSeries")
class CandleSeriesTest {

    @Test
    @DisplayName("constructs a valid columnar series")
    void constructs() {
        CandleSeries s = new CandleSeries(
                new long[]{1000, 2000, 3000},
                new double[]{10, 11, 12},
                new double[]{15, 16, 17},
                new double[]{9, 10, 11},
                new double[]{14, 15, 16},
                new double[]{100, 200, 300});
        assertEquals(3, s.size());
        assertEquals(15, s.close()[1]);
    }

    @Test
    @DisplayName("mismatched column lengths are unconstructable")
    void lengthMismatch() {
        assertThrows(IllegalArgumentException.class, () -> new CandleSeries(
                new long[]{1000, 2000},
                new double[]{10, 11},
                new double[]{15, 16},
                new double[]{9, 10},
                new double[]{14},          // one short
                new double[]{100, 200}));
    }

    @Test
    @DisplayName("non-ascending or duplicate timestamps are unconstructable")
    void timeOrdering() {
        assertThrows(IllegalArgumentException.class, () -> new CandleSeries(
                new long[]{2000, 1000},
                new double[]{10, 11}, new double[]{15, 16},
                new double[]{9, 10}, new double[]{14, 15},
                new double[]{100, 200}));
        assertThrows(IllegalArgumentException.class, () -> new CandleSeries(
                new long[]{1000, 1000},
                new double[]{10, 11}, new double[]{15, 16},
                new double[]{9, 10}, new double[]{14, 15},
                new double[]{100, 200}));
    }

    @Test
    @DisplayName("the empty series is valid and reports itself empty")
    void emptySeries() {
        assertTrue(CandleSeries.empty().isEmpty());
        assertEquals(0, CandleSeries.empty().size());
    }
}