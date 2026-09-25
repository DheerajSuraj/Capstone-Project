package com.tsb.signals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@DisplayName("FisherExact")
class FisherExactTest {

    @Test
    @DisplayName("matches known two-sided values")
    void knownValues() {
        // Fisher's tea-tasting table.
        assertEquals(0.485714, FisherExact.twoSided(3, 1, 1, 3), 1e-6);
        // Classic textbook example (R: fisher.test(matrix(c(1,11,9,3),2))).
        assertEquals(0.002759, FisherExact.twoSided(1, 9, 11, 3), 1e-6);
        // Perfect separation of 5 vs 5: 2 of C(10,5) = 252 tables.
        assertEquals(2.0 / 252, FisherExact.twoSided(0, 5, 5, 0), 1e-12);
    }

    @Test
    @DisplayName("no difference at all gives p = 1")
    void noDifference() {
        assertEquals(1.0, FisherExact.twoSided(5, 5, 5, 5), 1e-12);
    }

    @Test
    @DisplayName("swapping the rows does not change a two-sided p")
    void symmetric() {
        assertEquals(FisherExact.twoSided(2, 8, 7, 3),
                FisherExact.twoSided(7, 3, 2, 8), 1e-12);
    }

    @Test
    @DisplayName("large tables do not overflow")
    void large() {
        double p = FisherExact.twoSided(600, 400, 400, 600);
        assertEquals(true, p >= 0 && p < 1e-10, "p = " + p);
    }

    @Test
    @DisplayName("negative counts are rejected")
    void negative() {
        assertThrows(IllegalArgumentException.class,
                () -> FisherExact.twoSided(-1, 1, 1, 1));
    }
}
