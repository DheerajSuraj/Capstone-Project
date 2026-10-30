package com.tsb.research;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tsb.marketdata.CandleSeries;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The walk-forward tool must be honest about what it tested. */
class Search15mTest {

    private static final long T0 = Instant.parse("2024-01-01T00:00:00Z").toEpochMilli();

    private static CandleSeries walk(int months, long seed) {
        return Search15m.synthetic(months, seed, 0, 0.003, 40_000, T0);
    }

    @Test
    void theShuffleKeepsEachMonthsPricesAndCandlesButNotTheirOrder() {
        CandleSeries real = walk(3, 1);
        CandleSeries fake = Search15m.shuffled(real, 99);
        List<Integer> months = Search15m.monthStarts(real);
        assertEquals(months, Search15m.monthStarts(fake));
        for (int m = 0; m < months.size(); m++) {
            int from = months.get(m);
            int to = m + 1 < months.size() ? months.get(m + 1) : real.size();
            // Same month-end price, so buy & hold over any whole months is unchanged.
            assertEquals(real.close()[to - 1], fake.close()[to - 1], real.close()[to - 1] * 1e-9);
            double[] a = bodies(real, from, to);
            double[] b = bodies(fake, from, to);
            Arrays.sort(a);
            Arrays.sort(b);
            for (int i = 0; i < a.length; i++) {
                assertEquals(a[i], b[i], 1e-12);
            }
        }
        int moved = 0;
        for (int i = 0; i < real.size(); i++) {
            if (Math.abs(real.close()[i] - fake.close()[i]) > 1e-6) {
                moved++;
            }
        }
        assertTrue(moved > real.size() / 2, "the order must change");
    }

    @Test
    void everyCandidateCompilesWithTheRealCompiler() {
        List<Search15m.Variant> v = Search15m.variants();
        assertEquals(84, v.size());
        v.forEach(x -> assertNotNull(x.compiled()));
    }

    @Test
    void eachTestWindowComesAfterTheMonthsItWasPickedOn() {
        CandleSeries s = walk(9, 3);
        Search15m.Summary sum = Search15m.walkForward(s, Search15m.variants(), 6, 2);
        assertNotNull(sum);
        // 270 days from 1 Jan = January to September. Test windows: July-August,
        // then September, each picked on the 6 months before it.
        assertEquals(2, sum.folds());
        assertEquals(2, sum.rows().size());
        assertTrue(sum.rows().get(0).startsWith("| 2024-07-01 to 2024-08-31 |"), sum.rows().get(0));
        assertTrue(sum.rows().get(1).startsWith("| 2024-09-01 to "), sum.rows().get(1));
    }

    @Test
    void aStrategyCannotTradeBeforeItsWindow() {
        CandleSeries s = walk(4, 5);
        int from = Search15m.monthStarts(s).get(2);
        Search15m.Variant always = new Search15m.Variant("t", "always", "",
                Search15m.compile("always", "rule r { IF CLOSE > 0 THEN BUY ALL }"));
        Search15m.Score sc = Search15m.score(always, s, from, s.size());
        // Bought at the window's first open, sold at its last close: buy & hold minus the fees.
        double hold = Search15m.buyHold(s, from + 1, s.size());
        assertEquals(1, sc.trades());
        assertTrue(Math.abs(sc.retPct() - hold) < 1.0, sc.retPct() + " vs " + hold);
    }

    private static double[] bodies(CandleSeries s, int from, int to) {
        double[] out = new double[to - from];
        for (int i = from; i < to; i++) {
            out[i - from] = Math.log(s.close()[i] / s.open()[i]);
        }
        return out;
    }
}
