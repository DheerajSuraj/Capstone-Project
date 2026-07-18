package com.tsb.execution;

import java.util.ArrayList;
import java.util.List;

/**
 * Equity-curve transport helpers, shared by the API layer (response
 * payloads) and the persistence layer (stored run curves) — promoted out
 * of the controller once a second caller appeared, same rule as ConstFold.
 */
public final class EquityCurves {

    public record CurvePoint(long t, double equity) {
    }

    /** Stride-samples to at most {@code maxPoints}, always keeping the last
     *  point so the plotted final equity is exact. Short curves pass
     *  through untouched. */
    public static List<CurvePoint> downsample(long[] times, double[] equity,
                                              int maxPoints) {
        int n = equity.length;
        int stride = Math.max(1, (int) Math.ceil((double) n / maxPoints));
        List<CurvePoint> points = new ArrayList<>();
        for (int i = 0; i < n; i += stride) {
            points.add(new CurvePoint(times[i], equity[i]));
        }
        if (n > 0 && (n - 1) % stride != 0) {
            points.add(new CurvePoint(times[n - 1], equity[n - 1]));
        }
        return points;
    }

    private EquityCurves() {
    }
}