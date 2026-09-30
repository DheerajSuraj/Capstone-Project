package com.tsb.competition;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * "Rank all entries" and "add up each user's entries into their cumulative
 * score" from the sequence diagram. Pure: rows in, ranks out.
 *
 * <p><b>Entry ranking:</b> entries still in the competition (or passed) come
 * first, then those that failed at the end, then eliminated ones; within a
 * group, higher return first; ties go to whoever submitted first.
 *
 * <p><b>Trader ranking:</b> a user's cumulative score is the total profit
 * or loss across all of their entries (equity minus starting capital,
 * added up). Higher is better; ties go to more passed entries.
 */
public final class Standings {

    private Standings() {
    }

    public record Row(long entryId, long userId, EntryState.Status status,
                      double equity, long submittedAt) {
    }

    public record TraderScore(long userId, int entries, int passed, int eliminated,
                              double cumulativePnl, int rank) {
    }

    private static int group(EntryState.Status s) {
        return switch (s) {
            case ACTIVE, PASSED -> 0;
            case FAILED -> 1;
            case ELIMINATED -> 2;
        };
    }

    /** Entry id → rank (1 = best). */
    public static Map<Long, Integer> rankEntries(List<Row> rows) {
        List<Row> sorted = new ArrayList<>(rows);
        sorted.sort(Comparator
                .comparingInt((Row r) -> group(r.status()))
                .thenComparing(Row::equity, Comparator.reverseOrder())
                .thenComparingLong(Row::submittedAt)
                .thenComparingLong(Row::entryId));
        Map<Long, Integer> ranks = new LinkedHashMap<>();
        for (int i = 0; i < sorted.size(); i++) {
            ranks.put(sorted.get(i).entryId(), i + 1);
        }
        return ranks;
    }

    public static List<TraderScore> rankTraders(List<Row> rows, double startingCapital) {
        Map<Long, double[]> acc = new LinkedHashMap<>(); // pnl, entries, passed, eliminated
        for (Row r : rows) {
            double[] a = acc.computeIfAbsent(r.userId(), k -> new double[4]);
            a[0] += r.equity() - startingCapital;
            a[1]++;
            if (r.status() == EntryState.Status.PASSED) {
                a[2]++;
            }
            if (r.status() == EntryState.Status.ELIMINATED) {
                a[3]++;
            }
        }
        List<Map.Entry<Long, double[]>> list = new ArrayList<>(acc.entrySet());
        list.sort(Comparator
                .comparing((Map.Entry<Long, double[]> e) -> e.getValue()[0],
                        Comparator.reverseOrder())
                .thenComparing(e -> e.getValue()[2], Comparator.reverseOrder())
                .thenComparing(Map.Entry::getKey));
        List<TraderScore> out = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            double[] a = list.get(i).getValue();
            out.add(new TraderScore(list.get(i).getKey(), (int) a[1], (int) a[2],
                    (int) a[3], a[0], i + 1));
        }
        return out;
    }
}
