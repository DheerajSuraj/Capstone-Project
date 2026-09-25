package com.tsb.signals;

/**
 * Fisher's exact test on a 2×2 table, two-sided.
 *
 * <pre>
 *                 won   lost
 *   feature on     a     b
 *   feature off    c     d
 * </pre>
 *
 * <p>Why this test and not a chi-square: our tables are small (tens of
 * trades, sometimes a cell of 2 or 3), and the chi-square approximation is
 * unreliable exactly there. Fisher's test computes the probability of every
 * table with the same row and column totals directly from the
 * hypergeometric distribution, so it is exact at any size.
 *
 * <p>Two-sided p = the total probability of all tables at least as unlikely
 * as the one observed. Factorials are handled as sums of logarithms, so
 * nothing overflows.
 */
public final class FisherExact {

    private FisherExact() {
    }

    public static double twoSided(int a, int b, int c, int d) {
        if (a < 0 || b < 0 || c < 0 || d < 0) {
            throw new IllegalArgumentException("counts must be non-negative");
        }
        int n = a + b + c + d;
        int row1 = a + b;
        int col1 = a + c;
        double[] logFact = logFactorials(n);

        double observed = logP(a, row1, col1, n, logFact);
        int lo = Math.max(0, row1 + col1 - n);
        int hi = Math.min(row1, col1);
        double p = 0;
        for (int x = lo; x <= hi; x++) {
            double lp = logP(x, row1, col1, n, logFact);
            // Relative tolerance: tables tied with the observed one must be
            // counted, and floating point makes "equal" fuzzy.
            if (lp <= observed + 1e-7) {
                p += Math.exp(lp);
            }
        }
        return Math.min(1.0, p);
    }

    /** log P(top-left cell = x) with the margins fixed. */
    private static double logP(int x, int row1, int col1, int n, double[] lf) {
        int row2 = n - row1;
        int col2 = n - col1;
        return lf[row1] + lf[row2] + lf[col1] + lf[col2] - lf[n]
                - lf[x] - lf[row1 - x] - lf[col1 - x] - lf[row2 - col1 + x];
    }

    private static double[] logFactorials(int n) {
        double[] lf = new double[n + 1];
        for (int i = 2; i <= n; i++) {
            lf[i] = lf[i - 1] + Math.log(i);
        }
        return lf;
    }
}
