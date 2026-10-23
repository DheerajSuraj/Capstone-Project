package com.tsb.execution;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntToLongFunction;

/**
 * A run cut into calendar periods, to read it from the top down: the whole
 * run, then its years, months, weeks and days, each with the same numbers —
 * what it made, how far it fell, how it traded. The run, each year and each
 * month also say WHEN the money was made: by weekday, by hour of the day and
 * (for the whole run) by month of the year.
 *
 * <p><b>Calendar.</b> UTC, by candle open time — the same days as the daily
 * measures and the exchange's own daily candles. A week runs Monday to Sunday
 * and stops at the end of its month, so the weeks of a month add up to the
 * month exactly (a week that crosses into the next month shows up in both,
 * each part with its own days). The first period starts after the warm-up,
 * and the first and last periods only cover the candles there are.
 *
 * <p><b>Money.</b> A period starts at the equity of the close before it and
 * ends at the equity of its last close. Open positions count where their price
 * went — the same marked-to-market curve as everywhere else — so periods chain
 * exactly: the days of a week compound to the week, the months to the year.
 * A trade is counted on the day it CLOSES (with its win or loss and its P&amp;L);
 * fees on the day each fill paid them.
 *
 * <p>The records are the API's JSON as they are: a value that does not exist
 * (a return with nothing to start from, a win rate with no trades) is null,
 * never a made-up number.
 *
 * @param timeZone the calendar used for days, weeks and hours
 * @param all      the whole run; its children are the years
 */
public record PeriodReport(String timeZone, Node all) {

    static final long DAY_MS = 86_400_000L;
    static final long HOUR_MS = 3_600_000L;

    /** How deep a period sits: the run, then year, month, week, day. */
    public enum Level { ALL, YEAR, MONTH, WEEK, DAY }

    /**
     * One period.
     *
     * @param key       unique among its siblings: {@code all}, {@code 2025},
     *                  {@code 2025-03}, or the first day ({@code 2025-03-03})
     *                  for a week or a day
     * @param firstDay  first UTC day with a candle, ISO date
     * @param lastDay   last UTC day with a candle, ISO date
     * @param start     open time of the first candle, epoch ms
     * @param end       close time of the last candle, epoch ms
     * @param breakdown when the money was made; for the run, years and months
     *                  only (null for weeks and days)
     * @param children  the years of the run, months of a year, weeks of a
     *                  month, days of a week; empty for a day
     */
    public record Node(Level level, String key, String firstDay, String lastDay,
                       long start, long end, Stats stats, Breakdown breakdown,
                       List<Node> children) {
    }

    /**
     * The numbers of one period.
     *
     * @param days            UTC days with at least one candle
     * @param candles         candles in the period
     * @param startEquity     equity at the close before the period
     * @param endEquity       equity at its last close
     * @param returnPct       endEquity / startEquity - 1, percent (null if
     *                        there was nothing to start from)
     * @param pnl             endEquity - startEquity, closed trades or not
     * @param buyHoldPct      buy &amp; hold over the same candles, percent
     *                        (null if it never got to buy)
     * @param maxDrawdownPct  worst fall from a high inside the period,
     *                        counting from its start, percent
     * @param tradesOpened    trades whose entry filled in the period
     * @param tradesClosed    trades that closed in the period
     * @param wins            of those, the ones that made money after fees
     * @param winRatePct      wins / tradesClosed, percent (null if none closed)
     * @param realizedPnl     P&amp;L of the trades that closed, fees included
     * @param fees            fees paid by the fills in the period
     * @param timeInMarketPct share of candles with a position open, percent
     * @param greenDays       days that ended up
     * @param redDays         days that ended down (flat days are neither)
     */
    public record Stats(int days, int candles, double startEquity, double endEquity,
                        Double returnPct, double pnl, Double buyHoldPct,
                        double maxDrawdownPct, int tradesOpened, int tradesClosed,
                        int wins, Double winRatePct, double realizedPnl, double fees,
                        double timeInMarketPct, int greenDays, int redDays) {
    }

    /**
     * When the money was made inside one period.
     *
     * @param weekdays     Monday to Sunday, always seven rows
     * @param hours        hours 0-23 UTC, always 24 rows; null when a candle
     *                     is a day or longer (there is no hour to tell)
     * @param monthsOfYear January to December across the years; the whole run
     *                     only (null otherwise)
     */
    public record Breakdown(List<WeekdayRow> weekdays, List<HourRow> hours,
                            List<MonthRow> monthsOfYear) {
    }

    /**
     * Every day of one weekday in the period.
     *
     * @param weekday       1 = Monday … 7 = Sunday (ISO)
     * @param days          how many there were
     * @param avgReturnPct  their mean return, percent (null if none)
     * @param greenDays     how many ended up
     * @param pnl           what they added up to
     * @param tradesEntered trades whose entry filled on that weekday
     * @param entryWins     of those, the ones that ended up winning
     */
    public record WeekdayRow(int weekday, int days, Double avgReturnPct, int greenDays,
                             double pnl, int tradesEntered, int entryWins) {
    }

    /**
     * One hour of the day across the period. A candle counts at the hour it
     * opens; a "slot" is one hour of one day that had candles.
     *
     * @param hour          0-23, UTC
     * @param slots         hours of that kind with candles
     * @param avgReturnPct  their mean return, percent (null if none)
     * @param greenSlots    how many ended up
     * @param pnl           what they added up to
     * @param tradesEntered trades whose entry filled in that hour
     * @param entryWins     of those, the ones that ended up winning
     */
    public record HourRow(int hour, int slots, Double avgReturnPct, int greenSlots,
                          double pnl, int tradesEntered, int entryWins) {
    }

    /**
     * One calendar month across the years (every March, say).
     *
     * @param month        1 = January … 12 = December
     * @param months       how many of that month the run has (the first and
     *                     last may be partial)
     * @param avgReturnPct their mean return, percent (null if none)
     * @param greenMonths  how many ended up
     * @param pnl          what they added up to
     */
    public record MonthRow(int month, int months, Double avgReturnPct, int greenMonths,
                           double pnl) {
    }

    /**
     * Cuts one run into periods.
     *
     * @param times    candle open times, epoch ms, ascending
     * @param equity   equity at each close
     * @param held     coins held at each close
     * @param firstBar first candle the rules ran on; the warm-up before it is
     *                 flat capital and not part of the report
     * @param trades   the run's trades
     * @param feeRate  fee per fill as a fraction (0.001 = 0.1%), to split each
     *                 trade's fees between its entry and its exit
     * @param hold     buy &amp; hold's value at each close, or null
     * @param barMs    length of one candle, ms
     * @param capital  the starting capital
     * @return the report, or null when no candle is left after the warm-up
     */
    public static PeriodReport build(long[] times, double[] equity, double[] held,
                                     int firstBar, List<Trade> trades, double feeRate,
                                     double[] hold, long barMs, double capital) {
        int from = Math.max(0, firstBar);
        if (from >= equity.length) {
            return null;
        }
        return new Builder(times, equity, held, from, trades, feeRate, hold, barMs, capital)
                .build();
    }

    // ── Building ────────────────────────────────────────────────────────

    /** One UTC day of candles, and what the trades did on it. */
    private static final class Day {
        final long epochDay;
        final LocalDate date;
        final int i0;
        final int i1;
        Double returnPct;
        int opened;
        int closed;
        int wins;
        int entryWins;
        double realized;
        double fees;

        Day(long epochDay, int i0, int i1) {
            this.epochDay = epochDay;
            this.date = LocalDate.ofEpochDay(epochDay);
            this.i0 = i0;
            this.i1 = i1;
        }
    }

    private static final class Builder {
        private final long[] t;
        private final double[] eq;
        private final double[] held;
        private final int from;
        private final List<Trade> trades;
        private final double feeRate;
        private final double[] hold;
        private final long barMs;
        private final double capital;
        private final List<Day> days = new ArrayList<>();
        /** inMarket[i] = candles with a position open among the first i. */
        private final int[] inMarket;

        Builder(long[] t, double[] eq, double[] held, int from, List<Trade> trades,
                double feeRate, double[] hold, long barMs, double capital) {
            this.t = t;
            this.eq = eq;
            this.held = held;
            this.from = from;
            this.trades = trades;
            this.feeRate = feeRate;
            this.hold = hold;
            this.barMs = barMs;
            this.capital = capital;
            this.inMarket = new int[eq.length + 1];
            for (int i = 0; i < eq.length; i++) {
                inMarket[i + 1] = inMarket[i] + (held[i] > 0 ? 1 : 0);
            }
        }

        PeriodReport build() {
            splitDays();
            countTrades();
            List<Node> years = new ArrayList<>();
            for (int[] y : groups(0, days.size() - 1, d -> days.get(d).date.getYear())) {
                years.add(year(y[0], y[1]));
            }
            Node all = node(Level.ALL, "all", 0, days.size() - 1, years, true);
            return new PeriodReport("UTC", all);
        }

        /** Consecutive candles of the same UTC day; the candles are in time order. */
        private void splitDays() {
            int n = eq.length;
            int start = from;
            for (int i = from; i < n; i++) {
                long day = Math.floorDiv(t[i], DAY_MS);
                if (i == n - 1 || Math.floorDiv(t[i + 1], DAY_MS) != day) {
                    Day d = new Day(day, start, i);
                    d.returnPct = pct(eq[i], before(start));
                    days.add(d);
                    start = i + 1;
                }
            }
        }

        /**
         * Each trade on the day it closed, its entry on the day it opened,
         * and each fill's fee on its own day: the exit fee is exactly what
         * {@link Sizing#trade} charged on the way out, the rest was the entry.
         */
        private void countTrades() {
            Map<Long, Day> byDay = new HashMap<>();
            for (Day d : days) {
                byDay.put(d.epochDay, d);
            }
            for (Trade tr : trades) {
                double exitFee = tr.qty() * tr.exitPrice() * feeRate;
                Day in = byDay.get(Math.floorDiv(tr.entryTime().toEpochMilli(), DAY_MS));
                if (in != null) {
                    in.opened++;
                    in.fees += tr.fees() - exitFee;
                    if (tr.isWin()) {
                        in.entryWins++;
                    }
                }
                Day out = byDay.get(Math.floorDiv(tr.exitTime().toEpochMilli(), DAY_MS));
                if (out != null) {
                    out.closed++;
                    out.realized += tr.pnl();
                    out.fees += exitFee;
                    if (tr.isWin()) {
                        out.wins++;
                    }
                }
            }
        }

        private Node year(int a, int b) {
            List<Node> months = new ArrayList<>();
            for (int[] m : groups(a, b, d -> days.get(d).date.getMonthValue())) {
                months.add(month(m[0], m[1]));
            }
            return node(Level.YEAR, String.valueOf(days.get(a).date.getYear()), a, b, months,
                    true);
        }

        private Node month(int a, int b) {
            List<Node> weeks = new ArrayList<>();
            // A week is named by its Monday; days of one month only, so a
            // week that crosses into the next month is cut there.
            for (int[] w : groups(a, b, d -> days.get(d).epochDay
                    - (days.get(d).date.getDayOfWeek().getValue() - 1))) {
                weeks.add(week(w[0], w[1]));
            }
            LocalDate first = days.get(a).date;
            String key = first.getYear() + "-" + two(first.getMonthValue());
            return node(Level.MONTH, key, a, b, weeks, true);
        }

        private Node week(int a, int b) {
            List<Node> dayNodes = new ArrayList<>();
            for (int d = a; d <= b; d++) {
                dayNodes.add(node(Level.DAY, days.get(d).date.toString(), d, d, List.of(), false));
            }
            return node(Level.WEEK, days.get(a).date.toString(), a, b, dayNodes, false);
        }

        /** Index ranges [first, last] of consecutive days with the same key. */
        private static List<int[]> groups(int a, int b, IntToLongFunction key) {
            List<int[]> out = new ArrayList<>();
            int start = a;
            for (int d = a; d <= b; d++) {
                if (d == b || key.applyAsLong(d + 1) != key.applyAsLong(d)) {
                    out.add(new int[]{start, d});
                    start = d + 1;
                }
            }
            return out;
        }

        /** The period made of days a..b (indices into {@link #days}). */
        private Node node(Level level, String key, int a, int b, List<Node> children,
                          boolean breakdown) {
            Day first = days.get(a);
            Day last = days.get(b);
            int i0 = first.i0;
            int i1 = last.i1;
            double start = before(i0);
            double end = eq[i1];

            double peak = start;
            double maxDd = 0;
            for (int i = i0; i <= i1; i++) {
                peak = Math.max(peak, eq[i]);
                if (peak > 0) {
                    maxDd = Math.max(maxDd, (peak - eq[i]) / peak * 100);
                }
            }

            int opened = 0;
            int closed = 0;
            int wins = 0;
            int green = 0;
            int red = 0;
            double realized = 0;
            double fees = 0;
            for (int d = a; d <= b; d++) {
                Day day = days.get(d);
                opened += day.opened;
                closed += day.closed;
                wins += day.wins;
                realized += day.realized;
                fees += day.fees;
                if (day.returnPct != null && day.returnPct > 0) {
                    green++;
                } else if (day.returnPct != null && day.returnPct < 0) {
                    red++;
                }
            }

            Double buyHold = hold == null ? null : pct(hold[i1], i0 > 0 ? hold[i0 - 1] : capital);
            int candles = i1 - i0 + 1;
            Stats stats = new Stats(b - a + 1, candles, start, end, pct(end, start), end - start,
                    buyHold, maxDd, opened, closed, wins,
                    closed > 0 ? wins * 100.0 / closed : null, realized, fees,
                    (inMarket[i1 + 1] - inMarket[i0]) * 100.0 / candles, green, red);

            Breakdown bd = null;
            if (breakdown) {
                bd = new Breakdown(weekdays(a, b), barMs < DAY_MS ? hours(a, b) : null,
                        level == Level.ALL ? monthsOfYear(children) : null);
            }
            return new Node(level, key, first.date.toString(), last.date.toString(),
                    t[i0], t[i1] + barMs, stats, bd, children);
        }

        private List<WeekdayRow> weekdays(int a, int b) {
            int[] count = new int[8];
            int[] green = new int[8];
            int[] entered = new int[8];
            int[] entryWins = new int[8];
            double[] sum = new double[8];
            double[] pnl = new double[8];
            for (int d = a; d <= b; d++) {
                Day day = days.get(d);
                int w = day.date.getDayOfWeek().getValue();
                if (day.returnPct != null) {
                    count[w]++;
                    sum[w] += day.returnPct;
                    if (day.returnPct > 0) {
                        green[w]++;
                    }
                }
                pnl[w] += eq[day.i1] - before(day.i0);
                entered[w] += day.opened;
                entryWins[w] += day.entryWins;
            }
            List<WeekdayRow> out = new ArrayList<>(7);
            for (int w = 1; w <= 7; w++) {
                out.add(new WeekdayRow(w, count[w], count[w] > 0 ? sum[w] / count[w] : null,
                        green[w], pnl[w], entered[w], entryWins[w]));
            }
            return out;
        }

        /** Hours of the day: each hour of each day is one slot, from the close
         *  before its first candle to the close of its last. */
        private List<HourRow> hours(int a, int b) {
            int[] slots = new int[24];
            int[] green = new int[24];
            int[] entered = new int[24];
            int[] entryWins = new int[24];
            double[] sum = new double[24];
            double[] pnl = new double[24];
            int i0 = days.get(a).i0;
            int i1 = days.get(b).i1;
            int start = i0;
            for (int i = i0; i <= i1; i++) {
                long slot = Math.floorDiv(t[i], HOUR_MS);
                if (i == i1 || Math.floorDiv(t[i + 1], HOUR_MS) != slot) {
                    int h = hourOf(t[start]);
                    double open = before(start);
                    Double r = pct(eq[i], open);
                    if (r != null) {
                        slots[h]++;
                        sum[h] += r;
                        if (r > 0) {
                            green[h]++;
                        }
                    }
                    pnl[h] += eq[i] - open;
                    start = i + 1;
                }
            }
            long fromMs = days.get(a).epochDay * DAY_MS;
            long toMs = (days.get(b).epochDay + 1) * DAY_MS;
            for (Trade tr : trades) {
                long in = tr.entryTime().toEpochMilli();
                if (in >= fromMs && in < toMs) {
                    int h = hourOf(in);
                    entered[h]++;
                    if (tr.isWin()) {
                        entryWins[h]++;
                    }
                }
            }
            List<HourRow> out = new ArrayList<>(24);
            for (int h = 0; h < 24; h++) {
                out.add(new HourRow(h, slots[h], slots[h] > 0 ? sum[h] / slots[h] : null,
                        green[h], pnl[h], entered[h], entryWins[h]));
            }
            return out;
        }

        /** Every January, every February, … across the years of the run. */
        private static List<MonthRow> monthsOfYear(List<Node> years) {
            int[] count = new int[13];
            int[] green = new int[13];
            double[] sum = new double[13];
            double[] pnl = new double[13];
            for (Node y : years) {
                for (Node m : y.children()) {
                    int month = Integer.parseInt(m.key().substring(5));
                    Double r = m.stats().returnPct();
                    if (r != null) {
                        count[month]++;
                        sum[month] += r;
                        if (r > 0) {
                            green[month]++;
                        }
                    }
                    pnl[month] += m.stats().pnl();
                }
            }
            List<MonthRow> out = new ArrayList<>(12);
            for (int m = 1; m <= 12; m++) {
                out.add(new MonthRow(m, count[m], count[m] > 0 ? sum[m] / count[m] : null,
                        green[m], pnl[m]));
            }
            return out;
        }

        /** Equity at the close before candle i: the capital before the first. */
        private double before(int i) {
            return i > 0 ? eq[i - 1] : capital;
        }

        private static int hourOf(long ms) {
            return (int) (Math.floorMod(ms, DAY_MS) / HOUR_MS);
        }

        private static Double pct(double end, double start) {
            return start > 0 ? (end / start - 1) * 100 : null;
        }

        private static String two(int v) {
            return v < 10 ? "0" + v : String.valueOf(v);
        }
    }
}
