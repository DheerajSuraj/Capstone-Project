package com.tsb.paper;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Every number here can be checked with a calculator. Fee = 0.1%. */
@DisplayName("PaperMath")
class PaperMathTest {

    private static BigDecimal d(String s) {
        return new BigDecimal(s);
    }

    private static void same(String expected, BigDecimal actual) {
        assertEquals(0, d(expected).compareTo(actual), expected + " vs " + actual);
    }

    private static final PaperMath.Book FLAT = PaperMath.Book.FLAT;

    @Nested
    @DisplayName("longs")
    class Longs {

        @Test
        @DisplayName("buying opens a long and pays value + fee")
        void open() {
            PaperMath.Fill f = PaperMath.fill(FLAT, true, d("2"), d("100"));
            assertEquals(PaperMath.Side.LONG, f.after().side());
            same("2", f.after().qty());
            same("100", f.after().avgPrice());
            same("0.2", f.after().entryFees());
            same("-200.2", f.cashDelta());
            same("0", f.realizedPnl());
        }

        @Test
        @DisplayName("buying more averages the price")
        void add() {
            PaperMath.Book b = PaperMath.fill(FLAT, true, d("1"), d("100")).after();
            PaperMath.Fill f = PaperMath.fill(b, true, d("1"), d("110"));
            same("2", f.after().qty());
            same("105", f.after().avgPrice());
        }

        @Test
        @DisplayName("selling half realizes profit net of both fees' share")
        void closeHalf() {
            PaperMath.Book b = PaperMath.fill(FLAT, true, d("2"), d("100")).after(); // fees 0.2
            PaperMath.Fill f = PaperMath.fill(b, false, d("1"), d("110"));
            // (110 − 100) × 1 − 0.1 (half the entry fee) − 0.11 (exit fee) = 9.79
            same("9.79", f.realizedPnl());
            same("109.89", f.cashDelta());
            same("1", f.after().qty());
            same("0.1", f.after().entryFees());
        }

        @Test
        @DisplayName("selling everything leaves flat")
        void closeAll() {
            PaperMath.Book b = PaperMath.fill(FLAT, true, d("2"), d("100")).after();
            PaperMath.Fill f = PaperMath.fill(b, false, d("2"), d("90"));
            assertTrue(f.after().flat());
            // (90 − 100) × 2 − 0.2 − 0.18 = −20.38
            same("-20.38", f.realizedPnl());
        }
    }

    @Nested
    @DisplayName("shorts")
    class Shorts {

        @Test
        @DisplayName("selling while flat opens a short and receives value − fee")
        void open() {
            PaperMath.Fill f = PaperMath.fill(FLAT, false, d("1"), d("100"));
            assertEquals(PaperMath.Side.SHORT, f.after().side());
            same("99.9", f.cashDelta());
            same("0.1", f.after().entryFees());
        }

        @Test
        @DisplayName("a short makes money when the price falls")
        void profitOnFall() {
            PaperMath.Book b = PaperMath.fill(FLAT, false, d("1"), d("100")).after();
            same("9.9", PaperMath.unrealized(b, d("90"))); // 10 − 0.1 entry fee
            PaperMath.Fill f = PaperMath.fill(b, true, d("1"), d("90"));
            // (100 − 90) − 0.1 − 0.09 = 9.81
            same("9.81", f.realizedPnl());
            assertTrue(f.after().flat());
        }

        @Test
        @DisplayName("and loses when it rises")
        void lossOnRise() {
            PaperMath.Book b = PaperMath.fill(FLAT, false, d("1"), d("100")).after();
            same("-10.1", PaperMath.unrealized(b, d("110")));
        }
    }

    @Test
    @DisplayName("an order bigger than the position closes it and flips the rest")
    void flip() {
        PaperMath.Book longOne = PaperMath.fill(FLAT, true, d("1"), d("100")).after();
        PaperMath.Fill f = PaperMath.fill(longOne, false, d("3"), d("110"));
        same("1", f.closedQty());
        same("2", f.openedQty());
        assertEquals(PaperMath.Side.SHORT, f.after().side());
        same("2", f.after().qty());
        same("110", f.after().avgPrice());
        // Close part: (110 − 100) − 0.1 − 0.11 (a third of the 0.33 fee) = 9.79
        same("9.79", f.realizedPnl());
        same("0.22", f.after().entryFees()); // the other two thirds
    }

    @Test
    @DisplayName("equity = cash + longs − shorts, and matches start + PnL")
    void equityIdentity() {
        BigDecimal cash = d("10000");
        PaperMath.Book b = FLAT;
        PaperMath.Fill f1 = PaperMath.fill(b, false, d("2"), d("100")); // short 2
        cash = cash.add(f1.cashDelta());
        b = f1.after();
        BigDecimal price = d("95");
        BigDecimal equity = cash.add(PaperMath.signedValue(b, price));
        same(d("10000").add(PaperMath.unrealized(b, price)).toPlainString(), equity);
        assertNull(PaperMath.Book.FLAT.side());
    }

    @Test
    @DisplayName("buying power: equity minus everything held minus open orders")
    void buyingPower() {
        same("2500", PaperMath.buyingPower(d("10000"), d("7000"), d("500")));
        same("0", PaperMath.buyingPower(d("10000"), d("12000"), d("0")));
    }

    @Test
    @DisplayName("quantities round DOWN to the step size")
    void rounding() {
        same("0.123", PaperMath.roundQty(d("0.123999"), d("0.001")));
        same("9.990", PaperMath.qtyForCash(d("1000"), d("100"), d("0.001")));
    }
}
