package com.tsb.signals;

import com.tsb.compiler.Analyzer;
import com.tsb.compiler.CompiledStrategy;
import com.tsb.compiler.Diagnostic;
import com.tsb.compiler.Expr;
import com.tsb.compiler.Lexer;
import com.tsb.compiler.Parser;
import com.tsb.compiler.Span;
import com.tsb.compiler.StrategyAst;
import com.tsb.compiler.Timeframe;
import com.tsb.compiler.Token;
import com.tsb.compiler.TokenType;
import com.tsb.execution.Backtester;
import com.tsb.execution.BacktestResult;
import com.tsb.execution.ExchangeRules;
import com.tsb.execution.IndicatorBank;
import com.tsb.execution.Interpreter;
import com.tsb.execution.Trade;
import com.tsb.marketdata.CandleSeries;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.function.IntUnaryOperator;

/**
 * Confluence discovery: which market conditions did the winning trades
 * share, and would requiring one of them as an extra entry filter have made
 * the strategy better? For example "62% of winners had RSI below 25, against
 * 21% of losers; taking only those trades lifts the win rate from 50% to 78%".
 *
 * <p>Searching for such a filter in the same trades it is meant to improve is
 * the textbook way to overfit: try enough conditions and one always looks
 * great by chance. So every idea goes through four guards before it is
 * called real:
 * <ol>
 *   <li><b>A fixed, coarse search.</b> A pre-declared list of conditions
 *       with round thresholds (RSI below 20/25/30/35/40, price above the
 *       10/20/30/50/100/200-bar EMA, ...), each tried both ways. Nothing is
 *       fitted to the data: no thresholds are tuned, no conditions combined.
 *       Every condition is TSL run through our own compiler and engine, so
 *       the suggested snippet is exactly what was measured.</li>
 *   <li><b>Enough trades.</b> Nothing below {@value #MIN_TRADES} trades; a
 *       filter must keep at least {@value #MIN_KEPT} trades (and a fifth of
 *       them), so it cannot "win" by keeping a lucky handful.</li>
 *   <li><b>The luck bar.</b> The search sees only the earlier two thirds of
 *       the trades. On those, the win/loss labels are shuffled
 *       {@value #SHUFFLES} times and the whole search re-run each time. Each
 *       filter's lift is measured in standard errors (a filter that keeps few
 *       trades swings more by chance than one that keeps many), and the 95th
 *       percentile of the best score in shuffled data is the bar. An idea must
 *       beat it: a family-wise error rate of 5%, which also accounts for the
 *       ideas being correlated, unlike a Bonferroni correction. In win-rate
 *       points, the bar is higher for small filters than for large ones.</li>
 *   <li><b>Trades the search never saw.</b> Each idea is then checked on the
 *       later third of the trades, and the backtest is re-run with the filter
 *       actually added to the entry rules, so the later period shows what the
 *       filter really does to profit, including trades it frees the strategy
 *       to take. Only an idea that improves win rate, average return and the
 *       re-run's profit on that later period is reported as having held up.</li>
 * </ol>
 * Even then it is a lead to test further, not a guarantee: the later
 * period is one more slice of the same market.
 */
public final class ConfluenceDiscovery {

    public static final int MIN_TRADES = 30;
    /** Earlier share of the trades that the search may look at. */
    public static final double FOUND_SHARE = 2.0 / 3.0;
    public static final int MIN_KEPT = 10;
    public static final double MIN_KEPT_SHARE = 0.20;
    /** A filter that keeps almost every trade changes nothing worth reporting. */
    public static final double MAX_KEPT_SHARE = 0.90;
    public static final int MIN_LATER_KEPT = 5;
    public static final int SHUFFLES = 500;
    public static final double LUCK_LEVEL = 0.95;
    public static final int MAX_IDEAS = 3;
    private static final long SEED = 20261025L; // the same answer every time

    public enum Status { OK, TOO_FEW_TRADES }

    public enum Verdict {
        /** Beat the luck bar, and better win rate, average return and re-run profit on the later trades. */
        HELD_UP,
        /** Beat the luck bar; on the later trades some measures improved and some did not. */
        MIXED,
        /** Beat the luck bar on the earlier trades, but not better on the later ones: overfit. */
        DID_NOT_HOLD,
        /** Beat the luck bar, but kept too few of the later trades to check. */
        TOO_FEW_LATER_TRADES,
        /** Did not beat the luck bar: a difference this size is what luck produces. */
        WITHIN_LUCK
    }

    /**
     * One candidate filter. {@code tsl} is true when the condition holds;
     * {@code tslNot} is its exact opposite (used when the losers, not the
     * winners, had the condition). Both null for time-of-day conditions,
     * which TSL cannot express yet.
     */
    record Condition(String id, String family, String label, String labelNot,
                     String tsl, String tslNot) {
    }

    /** Win rate and average return of a set of trades, before and after a filter. */
    public record Side(int trades, int kept, double winRateAll, double winRateKept,
                       double avgReturnAll, double avgReturnKept) {
    }

    /**
     * The backtest re-run with the filter added. The first six fields cover the
     * later period only (the trades the search never saw); the {@code full…}
     * fields cover the whole backtest, so they match the report's return, but
     * they include the trades the filter was picked on and so flatter it.
     */
    public record Rerun(double returnBefore, double returnAfter,
                        int tradesBefore, int tradesAfter,
                        double winRateBefore, double winRateAfter,
                        double fullReturnBefore, double fullReturnAfter,
                        int fullTradesBefore, int fullTradesAfter) {
    }

    /**
     * One idea.
     *
     * @param snippet         TSL to add to the entry condition (null for time of day)
     * @param winnersWithPct  share of the earlier winners that had the condition
     * @param losersWithPct   share of the earlier losers that had it
     * @param lift            earlier win rate with the filter minus without, in points
     * @param luckLift        the lift luck reaches (the luck bar) for a filter keeping this many trades
     * @param rerun           null when the backtest could not be re-run with it
     * @param appliedSource   the strategy's code with the filter added to every buying IF,
     *                        everything else exactly as written; the re-run ran this code.
     *                        Null when it could not be added as code.
     */
    public record Idea(String id, String label, String family, String snippet,
                       Side found, double winnersWithPct, double losersWithPct,
                       double lift, double luckLift, boolean beatsLuck, Side later, Rerun rerun,
                       Verdict verdict, String appliedSource) {
    }

    /** One tried filter, for the full list. */
    public record Tried(String label, int kept, double winRateKept, double lift,
                        double luckLift, boolean beatsLuck) {
    }

    public record Report(
            Status status,
            String message,
            int trades,
            int wins,
            double winRate,
            int foundTrades,
            int laterTrades,
            /** Open time of the first later trade's signal candle (ISO-8601), or null. */
            String laterFrom,
            int conditions,
            int filtersTried,
            int shuffles,
            /** The luck bar in standard errors: the best score luck produced, 95th percentile. */
            double luckZ,
            List<Idea> ideas,
            /** The ten best tried filters, by strength of evidence on the earlier trades. */
            List<Tried> topTried,
            /** Why the backtest could not be re-run with a filter, or null. */
            String rerunNote
    ) {
    }

    private ConfluenceDiscovery() {
    }

    // ── The search space ────────────────────────────────────────────────

    /** The conditions tried for a strategy on {@code timeframe}. */
    static List<Condition> panel(String timeframe, boolean intraday) {
        List<Condition> p = new ArrayList<>();
        for (int t : new int[]{20, 25, 30, 35, 40}) {
            p.add(new Condition("rsi_below_" + t, "rsi", "RSI below " + t, "RSI " + t + " or above",
                    "RSI(14) < " + t, "RSI(14) >= " + t));
        }
        for (int t : new int[]{55, 60, 65, 70}) {
            p.add(new Condition("rsi_above_" + t, "rsi", "RSI above " + t, "RSI " + t + " or below",
                    "RSI(14) > " + t, "RSI(14) <= " + t));
        }
        for (int n : new int[]{10, 20, 30, 50, 100, 200}) {
            p.add(new Condition("above_ema" + n, "ema", "Price above its " + n + "-bar EMA",
                    "Price at or below its " + n + "-bar EMA",
                    "CLOSE > EMA(CLOSE, " + n + ")", "CLOSE <= EMA(CLOSE, " + n + ")"));
        }
        p.add(new Condition("sma50_above_sma200", "trend", "50-bar average above the 200-bar average",
                "50-bar average at or below the 200-bar average",
                "SMA(CLOSE, 50) > SMA(CLOSE, 200)", "SMA(CLOSE, 50) <= SMA(CLOSE, 200)"));
        for (int t : new int[]{20, 25, 30, 40}) {
            p.add(new Condition("adx_above_" + t, "adx", "Strong trend (ADX above " + t + ")",
                    "Weak trend (ADX " + t + " or below)", "ADX(14) > " + t, "ADX(14) <= " + t));
        }
        p.add(new Condition("volume_above_avg", "volume", "Volume above its 20-bar average",
                "Volume at or below its 20-bar average",
                "VOLUME > SMA(VOLUME, 20)", "VOLUME <= SMA(VOLUME, 20)"));
        for (String k : new String[]{"1.5", "2"}) {
            p.add(new Condition("volume_above_" + k + "x", "volume",
                    "Volume above " + k + "× its 20-bar average",
                    "Volume at most " + k + "× its 20-bar average",
                    "VOLUME > SMA(VOLUME, 20) * " + k, "VOLUME <= SMA(VOLUME, 20) * " + k));
        }
        p.add(new Condition("below_bb_lower", "bands", "Close below the lower Bollinger band",
                "Close at or above the lower Bollinger band",
                "CLOSE < BB_LOWER(CLOSE, 20, 2)", "CLOSE >= BB_LOWER(CLOSE, 20, 2)"));
        p.add(new Condition("above_bb_upper", "bands", "Close above the upper Bollinger band",
                "Close at or below the upper Bollinger band",
                "CLOSE > BB_UPPER(CLOSE, 20, 2)", "CLOSE <= BB_UPPER(CLOSE, 20, 2)"));
        p.add(new Condition("macd_above_signal", "macd", "MACD above its signal line",
                "MACD at or below its signal line",
                "MACD_LINE(12, 26) > MACD_SIGNAL(12, 26, 9)",
                "MACD_LINE(12, 26) <= MACD_SIGNAL(12, 26, 9)"));
        p.add(new Condition("up_candle", "candle", "Signal candle closed up",
                "Signal candle closed down or flat", "CLOSE > OPEN", "CLOSE <= OPEN"));
        p.add(new Condition("atr_rising", "volatility",
                "Volatility rising (ATR above its level 10 candles earlier)",
                "Volatility falling or flat", "ATR(14) > ATR(14)[10]", "ATR(14) <= ATR(14)[10]"));
        long own = Timeframe.millis(timeframe).orElse(0L);
        if (own > 0 && own < 4 * 3_600_000L) {
            p.add(new Condition("above_4h_ema50", "bigger_trend", "4h trend up (price above the 4h 50-EMA)",
                    "4h trend down (price at or below the 4h 50-EMA)",
                    "CLOSE > EMA(CLOSE, 50) ON 4h", "CLOSE <= EMA(CLOSE, 50) ON 4h"));
        }
        if (own > 0 && own < 86_400_000L) {
            p.add(new Condition("above_1d_ema20", "bigger_trend",
                    "Daily trend up (price above the daily 20-EMA)",
                    "Daily trend down (price at or below the daily 20-EMA)",
                    "CLOSE > EMA(CLOSE, 20) ON 1d", "CLOSE <= EMA(CLOSE, 20) ON 1d"));
        }
        if (intraday) {
            p.add(new Condition("session_asia", "time", "Asia session (00:00–08:00 UTC)",
                    "Outside the Asia session", null, null));
            p.add(new Condition("session_europe", "time", "Europe session (08:00–16:00 UTC)",
                    "Outside the Europe session", null, null));
            p.add(new Condition("session_us", "time", "US session (16:00–24:00 UTC)",
                    "Outside the US session", null, null));
        }
        p.add(new Condition("weekend", "time", "Weekend (Saturday or Sunday, UTC)",
                "On a weekday", null, null));
        return p;
    }

    // ── Trades → positions ──────────────────────────────────────────────

    /** A completed position: partial exits of one entry summed. */
    record Position(int signalBar, double pnl, double returnPct) {
        boolean won() {
            return pnl > 0;
        }
    }

    static List<Position> positions(List<Trade> trades) {
        Map<Integer, double[]> byEntry = new LinkedHashMap<>(); // pnl, cost
        for (Trade t : trades) {
            double[] acc = byEntry.computeIfAbsent(t.entryBar(), k -> new double[2]);
            acc[0] += t.pnl();
            acc[1] += t.qty() * t.entryPrice();
        }
        List<Position> out = new ArrayList<>();
        for (Map.Entry<Integer, double[]> e : byEntry.entrySet()) {
            double cost = e.getValue()[1];
            // Orders fill at the open after the signal candle.
            out.add(new Position(e.getKey() - 1, e.getValue()[0],
                    cost == 0 ? 0 : e.getValue()[0] / cost * 100.0));
        }
        out.sort(Comparator.comparingInt(Position::signalBar));
        return out;
    }

    // ── Analysis ────────────────────────────────────────────────────────

    /** A filter: a condition, kept when true ({@code keepTrue}) or when false. */
    private record Filter(Condition c, boolean keepTrue, int[] values) {
        String label() {
            return keepTrue ? c.label() : c.labelNot();
        }

        String tsl() {
            return keepTrue ? c.tsl() : c.tslNot();
        }

        /** Kept by the filter. An unknown value (not enough history) is not
         *  kept: in TSL the filter would be UNKNOWN, and nothing buys. */
        boolean keeps(int position) {
            return values[position] == (keepTrue ? 1 : 0);
        }
    }

    /**
     * @param source  the strategy's TSL, to re-run it with a filter added;
     *                null to skip the re-runs
     * @param rules   the exchange rules the original backtest ran with
     */
    public static Report analyze(String source, CompiledStrategy strategy, CandleSeries series,
                                 ExchangeRules rules, BacktestResult original) {
        List<Position> all = positions(original.trades());
        int n = all.size();
        int wins = (int) all.stream().filter(Position::won).count();
        double winRate = SignalStatistics.pct(wins, n);
        if (n < MIN_TRADES) {
            return new Report(Status.TOO_FEW_TRADES, String.format(Locale.ROOT,
                    "Only %d completed trades. Below %d, a difference between winners and "
                            + "losers is almost always luck, and there would be too few "
                            + "later trades to check an idea on. Widen the date range or "
                            + "loosen the entry to get more trades.", n, MIN_TRADES),
                    n, wins, winRate, 0, 0, null, 0, 0, 0, 0, List.of(), List.of(), null);
        }

        int nFound = (int) Math.round(n * FOUND_SHARE);
        int splitBar = all.get(nFound).signalBar();
        String laterFrom = Instant.ofEpochMilli(series.openTimeMillis()[splitBar]).toString();
        boolean[] won = new boolean[n];
        double[] ret = new double[n];
        for (int i = 0; i < n; i++) {
            won[i] = all.get(i).won();
            ret[i] = all.get(i).returnPct();
        }
        int foundWins = 0;
        for (int i = 0; i < nFound; i++) {
            foundWins += won[i] ? 1 : 0;
        }

        // Every condition, evaluated at every position's signal candle.
        boolean intraday = SignalStatistics.isIntraday(series);
        List<Condition> conditions = new ArrayList<>();
        List<int[]> values = new ArrayList<>();
        for (Condition c : panel(strategy.timeframe(), intraday)) {
            Optional<IntUnaryOperator> test = testFor(c, strategy, series);
            if (test.isEmpty()) {
                continue; // e.g. "ON 4h" on a strategy that already runs on 4h
            }
            int[] v = new int[n];
            for (int i = 0; i < n; i++) {
                v[i] = test.get().applyAsInt(all.get(i).signalBar());
            }
            conditions.add(c);
            values.add(v);
        }

        // Filters that keep a sensible number of the earlier trades.
        int minKept = Math.max(MIN_KEPT, (int) Math.ceil(MIN_KEPT_SHARE * nFound));
        int maxKept = (int) Math.floor(MAX_KEPT_SHARE * nFound);
        List<Filter> filters = new ArrayList<>();
        for (int k = 0; k < conditions.size(); k++) {
            for (boolean keepTrue : new boolean[]{true, false}) {
                Filter f = new Filter(conditions.get(k), keepTrue, values.get(k));
                int kept = 0;
                for (int i = 0; i < nFound; i++) {
                    kept += f.keeps(i) ? 1 : 0;
                }
                if (kept >= minKept && kept <= maxKept) {
                    filters.add(f);
                }
            }
        }

        double base = (double) foundWins / nFound;
        double luckZ = luckZ(filters, won, nFound, base);

        // Rank by strength of evidence on the earlier trades: lift in standard errors.
        record Scored(Filter f, Side found, double lift, double z, double luckLift) {
            boolean beatsLuck() {
                return luckLift > 0 && lift > luckLift;
            }
        }
        List<Scored> scored = new ArrayList<>();
        for (Filter f : filters) {
            Side s = side(f, won, ret, 0, nFound);
            double lift = s.winRateKept() - s.winRateAll();
            double se = standardError(s.kept(), nFound, base);
            scored.add(new Scored(f, s, lift, se == 0 ? 0 : lift / se, luckZ * se));
        }
        scored.sort(Comparator.comparingDouble(Scored::z).reversed());
        List<Tried> topTried = scored.stream().limit(10)
                .map(s -> new Tried(s.f().label(), s.found().kept(), s.found().winRateKept(),
                        s.lift(), s.luckLift(), s.beatsLuck()))
                .toList();

        // The best filter of each family that raises both the win rate and the
        // average return on the earlier trades; at most MAX_IDEAS families.
        Injector injector = source == null ? null : Injector.of(source);
        String rerunNote = source == null ? "No strategy source to re-run."
                : injector.problem();
        List<Idea> ideas = new ArrayList<>();
        Set<String> families = new HashSet<>();
        for (Scored s : scored) {
            if (ideas.size() == MAX_IDEAS) {
                break;
            }
            if (s.lift() <= 0 || s.found().avgReturnKept() <= s.found().avgReturnAll()
                    || !families.add(s.f().c().family())) {
                continue;
            }
            ideas.add(idea(s.f(), s.found(), s.lift(), s.luckLift(), s.beatsLuck(), won, ret,
                    nFound, n, injector, rules, series, original, all, splitBar));
        }

        long held = ideas.stream().filter(i -> i.verdict() == Verdict.HELD_UP).count();
        long beat = ideas.stream().filter(Idea::beatsLuck).count();
        String message;
        if (filters.isEmpty()) {
            message = "No filter kept enough of the earlier trades to be judged.";
        } else if (beat == 0) {
            Scored best = scored.get(0);
            message = String.format(Locale.ROOT,
                    "We tried %d filters on your first %d trades. The strongest, \u201c%s\u201d, "
                            + "raised the win rate by %.0f points, but with this many filters "
                            + "tried, luck alone reaches %.0f points for one that keeps %d trades. "
                            + "So none of these is worth adding to your strategy.",
                    filters.size(), nFound, best.f().label(), best.lift(), best.luckLift(),
                    best.found().kept());
        } else if (held == 0) {
            message = String.format(Locale.ROOT,
                    "%d idea%s beat luck on your first %d trades, but none held up on the %d "
                            + "later trades the search never saw. Treat %s as overfit.",
                    beat, beat == 1 ? "" : "s", nFound, n - nFound, beat == 1 ? "it" : "them");
        } else {
            message = String.format(Locale.ROOT,
                    "%d idea%s beat luck on your first %d trades and held up on the %d later "
                            + "trades the search never saw.",
                    held, held == 1 ? "" : "s", nFound, n - nFound);
        }
        return new Report(Status.OK, message, n, wins, winRate, nFound, n - nFound, laterFrom,
                conditions.size(), filters.size(), SHUFFLES, luckZ, List.copyOf(ideas),
                topTried, rerunNote);
    }

    /** Win rate and average return of positions [from, to), all of them and those the filter keeps. */
    private static Side side(Filter f, boolean[] won, double[] ret, int from, int to) {
        int nAll = 0, winsAll = 0, nKept = 0, winsKept = 0;
        double retAll = 0, retKept = 0;
        for (int i = from; i < to; i++) {
            nAll++;
            winsAll += won[i] ? 1 : 0;
            retAll += ret[i];
            if (f.keeps(i)) {
                nKept++;
                winsKept += won[i] ? 1 : 0;
                retKept += ret[i];
            }
        }
        return new Side(nAll, nKept, SignalStatistics.pct(winsAll, nAll),
                SignalStatistics.pct(winsKept, nKept),
                nAll == 0 ? 0 : retAll / nAll, nKept == 0 ? 0 : retKept / nKept);
    }

    /**
     * Standard error of a filter's kept win rate, in points, when the winners
     * are spread at random: {@code k} of {@code n} trades kept, a share
     * {@code p} of the {@code n} won (hypergeometric).
     */
    static double standardError(int k, int n, double p) {
        if (k <= 0 || n <= 1 || p <= 0 || p >= 1) {
            return 0;
        }
        return Math.sqrt(p * (1 - p) * (n - k) / ((double) (n - 1) * k)) * 100.0;
    }

    /**
     * The luck bar: shuffle which earlier trades won, re-run the whole search,
     * and keep the best score (lift in standard errors). Done
     * {@value #SHUFFLES} times, the 95th percentile is the score luck reaches
     * 1 time in 20 with this many filters on these trades.
     */
    static double luckZ(List<Filter> filters, boolean[] won, int nFound, double base) {
        if (filters.isEmpty()) {
            return 0;
        }
        int[][] kept = new int[filters.size()][];
        for (int k = 0; k < filters.size(); k++) {
            Filter f = filters.get(k);
            kept[k] = java.util.stream.IntStream.range(0, nFound).filter(f::keeps).toArray();
        }
        boolean[] shuffled = Arrays.copyOf(won, nFound);
        Random random = new Random(SEED);
        double[] best = new double[SHUFFLES];
        for (int s = 0; s < SHUFFLES; s++) {
            for (int i = nFound - 1; i > 0; i--) { // Fisher–Yates
                int j = random.nextInt(i + 1);
                boolean t = shuffled[i];
                shuffled[i] = shuffled[j];
                shuffled[j] = t;
            }
            double max = Double.NEGATIVE_INFINITY;
            for (int[] idx : kept) {
                int w = 0;
                for (int i : idx) {
                    w += shuffled[i] ? 1 : 0;
                }
                double se = standardError(idx.length, nFound, base);
                if (se > 0) {
                    max = Math.max(max, (SignalStatistics.pct(w, idx.length) - base * 100) / se);
                }
            }
            best[s] = max == Double.NEGATIVE_INFINITY ? 0 : max;
        }
        Arrays.sort(best);
        return best[(int) Math.ceil(LUCK_LEVEL * SHUFFLES) - 1];
    }

    private static Idea idea(Filter f, Side found, double lift, double luckLift,
                             boolean beatsLuck, boolean[] won, double[] ret, int nFound, int n,
                             Injector injector, ExchangeRules rules, CandleSeries series,
                             BacktestResult original, List<Position> all, int splitBar) {
        int winners = 0, winnersWith = 0, losers = 0, losersWith = 0;
        for (int i = 0; i < nFound; i++) {
            boolean has = f.keeps(i);
            if (won[i]) {
                winners++;
                winnersWith += has ? 1 : 0;
            } else {
                losers++;
                losersWith += has ? 1 : 0;
            }
        }
        Side later = side(f, won, ret, nFound, n);

        Rerun rerun = null;
        String applied = null;
        if (f.tsl() != null && injector != null && injector.problem() == null) {
            // Re-run the very code the "Add" button would put in the editor;
            // only if it can't be written as code, the filter added to the tree.
            Optional<String> edited = injector.edit(f.tsl());
            Optional<CompiledStrategy> filtered = edited.flatMap(e -> compile(parse(e)));
            if (filtered.isPresent()) {
                applied = edited.get();
            } else {
                filtered = injector.with(f.tsl());
            }
            if (filtered.isPresent()) {
                BacktestResult after = new Backtester().run(filtered.get(), series, rules);
                rerun = rerun(original, after, all, splitBar);
            }
        }

        Verdict verdict;
        if (!beatsLuck) {
            verdict = Verdict.WITHIN_LUCK;
        } else if (later.kept() < MIN_LATER_KEPT) {
            verdict = Verdict.TOO_FEW_LATER_TRADES;
        } else {
            boolean winUp = later.winRateKept() > later.winRateAll();
            boolean retUp = later.avgReturnKept() > later.avgReturnAll();
            boolean profitUp = rerun == null || rerun.returnAfter() > rerun.returnBefore();
            if (winUp && retUp && profitUp) {
                verdict = Verdict.HELD_UP;
            } else if (!winUp && !retUp) {
                verdict = Verdict.DID_NOT_HOLD;
            } else {
                verdict = Verdict.MIXED;
            }
        }
        String snippet = f.tsl() == null ? null : "AND " + f.tsl();
        return new Idea(f.c().id() + (f.keepTrue() ? "" : "_not"), f.label(), f.c().family(),
                snippet, found, SignalStatistics.pct(winnersWith, winners),
                SignalStatistics.pct(losersWith, losers), lift, luckLift, beatsLuck, later, rerun,
                verdict, applied);
    }

    /** The two runs compared over the later period: from the first later signal to the end. */
    private static Rerun rerun(BacktestResult before, BacktestResult after,
                               List<Position> beforePositions, int splitBar) {
        List<Position> afterPositions = positions(after.trades());
        return new Rerun(windowReturn(before, splitBar), windowReturn(after, splitBar),
                (int) beforePositions.stream().filter(p -> p.signalBar() >= splitBar).count(),
                (int) afterPositions.stream().filter(p -> p.signalBar() >= splitBar).count(),
                laterWinRate(beforePositions, splitBar), laterWinRate(afterPositions, splitBar),
                before.totalReturnPct(), after.totalReturnPct(),
                before.trades().size(), after.trades().size());
    }

    private static double windowReturn(BacktestResult r, int splitBar) {
        double[] eq = r.equityCurve();
        double start = splitBar > 0 ? eq[splitBar - 1] : r.initialCapital();
        return start <= 0 ? 0 : (eq[eq.length - 1] / start - 1) * 100.0;
    }

    private static double laterWinRate(List<Position> ps, int splitBar) {
        List<Position> later = ps.stream().filter(p -> p.signalBar() >= splitBar).toList();
        return SignalStatistics.pct((int) later.stream().filter(Position::won).count(),
                later.size());
    }

    // ── Evaluating a condition at a candle ──────────────────────────────

    /** 1 = true, 0 = false, -1 = unknown (not enough history yet). Empty when
     *  the condition does not compile for this strategy's timeframe. */
    static Optional<IntUnaryOperator> testFor(Condition c, CompiledStrategy strategy,
                                              CandleSeries series) {
        if (c.tsl() == null) {
            return Optional.of(switch (c.id()) {
                case "session_asia" -> bar -> hour(series, bar) < 8 ? 1 : 0;
                case "session_europe" -> bar -> hour(series, bar) >= 8 && hour(series, bar) < 16 ? 1 : 0;
                case "session_us" -> bar -> hour(series, bar) >= 16 ? 1 : 0;
                case "weekend" -> bar -> {
                    DayOfWeek d = utc(series, bar).getDayOfWeek();
                    return d == DayOfWeek.SATURDAY || d == DayOfWeek.SUNDAY ? 1 : 0;
                };
                default -> throw new IllegalStateException("unknown time condition " + c.id());
            });
        }
        Optional<CompiledStrategy> probe = probe(c.tsl(), strategy);
        if (probe.isEmpty()) {
            return Optional.empty();
        }
        Interpreter interp = new Interpreter(probe.get(), series,
                IndicatorBank.compute(probe.get(), series));
        Expr condition = probe.get().rules().get(0).body().get(0).condition();
        int warmup = probe.get().warmupBars();
        return Optional.of(bar -> bar < warmup ? -1 : interp.bool(condition, bar) ? 1 : 0);
    }

    /** One condition in a minimal strategy, through the real compiler. */
    static Optional<CompiledStrategy> probe(String condition, CompiledStrategy base) {
        String source = "strategy \"probe\" {\n"
                + "    symbol = " + base.symbol() + "\n"
                + "    timeframe = " + base.timeframe() + "\n"
                + "    capital = 1000\n"
                + "    rule probe { IF " + condition + " THEN BUY ALL }\n"
                + "}\n";
        return compile(parse(source));
    }

    private static Optional<StrategyAst.StrategyDecl> parse(String source) {
        Lexer.LexResult lexed = new Lexer(source).scan();
        Parser.ParseResult parsed = new Parser(lexed.tokens()).parse();
        if (lexed.hasErrors() || parsed.hasErrors()) {
            return Optional.empty();
        }
        return parsed.strategy();
    }

    private static Optional<CompiledStrategy> compile(Optional<StrategyAst.StrategyDecl> decl) {
        if (decl.isEmpty()) {
            return Optional.empty();
        }
        Analyzer.AnalysisResult analyzed = new Analyzer(decl.get()).analyze();
        if (analyzed.diagnostics().stream().anyMatch(Diagnostic::isError)) {
            return Optional.empty();
        }
        return analyzed.strategy();
    }

    private static ZonedDateTime utc(CandleSeries series, int bar) {
        return Instant.ofEpochMilli(series.openTimeMillis()[bar]).atZone(ZoneOffset.UTC);
    }

    private static int hour(CandleSeries series, int bar) {
        return utc(series, bar).getHour();
    }

    // ── Adding a filter to the strategy ─────────────────────────────────

    /**
     * Adds a filter to every buying IF of the strategy: {@code IF c THEN BUY}
     * becomes {@code IF (c) AND (filter) THEN BUY}, exactly what a trader
     * would write. Done on the parse tree and checked by the analyzer, so
     * the re-run is the real engine running real TSL.
     */
    static final class Injector {
        private final StrategyAst.StrategyDecl decl;
        private final String problem;
        private final String source;

        private Injector(StrategyAst.StrategyDecl decl, String problem) {
            this(decl, problem, null);
        }

        private Injector(StrategyAst.StrategyDecl decl, String problem, String source) {
            this.decl = decl;
            this.problem = problem;
            this.source = source;
        }

        static Injector of(String source) {
            Optional<StrategyAst.StrategyDecl> decl = parse(source);
            if (decl.isEmpty()) {
                return new Injector(null, "The strategy could not be read again to add a filter.");
            }
            boolean anyBuy = false;
            for (StrategyAst.RuleDecl r : decl.get().rules()) {
                for (StrategyAst.IfStmt s : r.body()) {
                    if (s.elseAction().isPresent()
                            && s.elseAction().get() instanceof StrategyAst.Action.Buy) {
                        return new Injector(null, "Your strategy buys in an ELSE branch, so a "
                                + "filter can't simply be added to its entry; ideas are checked on "
                                + "the later trades only, without re-running the backtest.");
                    }
                    anyBuy |= s.thenAction() instanceof StrategyAst.Action.Buy;
                }
            }
            if (!anyBuy) {
                return new Injector(null, "No IF statement buys, so there is no entry to filter.");
            }
            return new Injector(decl.get(), null, source);
        }

        /** Why re-runs are not possible, or null. */
        String problem() {
            return problem;
        }

        /**
         * The source with {@code AND <filter>} added to the condition of every
         * IF that buys, and nothing else touched: layout, comments and the other
         * rules stay as written. The condition is the text between IF and THEN
         * (read from the tokens, so brackets around it are kept); one whose top
         * level is an OR is put in brackets first, since AND binds tighter.
         * Empty when the tokens and the parsed statements don't line up.
         */
        Optional<String> edit(String filterTsl) {
            if (decl == null || source == null) {
                return Optional.empty();
            }
            Lexer.LexResult lexed = new Lexer(source).scan();
            if (lexed.hasErrors()) {
                return Optional.empty();
            }
            List<StrategyAst.IfStmt> ifs = new ArrayList<>();
            decl.rules().forEach(r -> ifs.addAll(r.body()));
            int[] lineStart = lineStarts(source);
            List<Token> toks = lexed.tokens();
            StringBuilder out = new StringBuilder(source.length() + 64);
            int copied = 0;
            int k = 0;
            for (int i = 0; i < toks.size(); i++) {
                if (!toks.get(i).is(TokenType.IF)) {
                    continue;
                }
                if (k >= ifs.size()) {
                    return Optional.empty();
                }
                StrategyAst.IfStmt stmt = ifs.get(k++);
                Span at = toks.get(i).span();
                if (stmt.span().startLine() != at.startLine()
                        || stmt.span().startCol() != at.startCol()) {
                    return Optional.empty();
                }
                int j = i + 1;
                while (j < toks.size() && !toks.get(j).is(TokenType.THEN)) {
                    j++;
                }
                if (j >= toks.size() || j == i + 1) {
                    return Optional.empty();
                }
                if (!(stmt.thenAction() instanceof StrategyAst.Action.Buy)) {
                    continue;
                }
                Span first = toks.get(i + 1).span();
                Span last = toks.get(j - 1).span();
                int a = offset(lineStart, first.startLine(), first.startCol());
                int b = offset(lineStart, last.endLine(), last.endCol());
                if (a < copied || b > source.length() || a >= b) {
                    return Optional.empty();
                }
                boolean wrap = stmt.condition() instanceof Expr.Binary bin
                        && bin.op() == Expr.BinaryOp.OR;
                out.append(source, copied, a);
                out.append(wrap ? "(" : "").append(source, a, b).append(wrap ? ")" : "");
                out.append(" AND ").append(filterTsl);
                copied = b;
            }
            if (k != ifs.size()) {
                return Optional.empty();
            }
            out.append(source, copied, source.length());
            return Optional.of(out.toString());
        }

        private static int[] lineStarts(String text) {
            List<Integer> starts = new ArrayList<>();
            starts.add(0);
            for (int i = 0; i < text.length(); i++) {
                if (text.charAt(i) == '\n') {
                    starts.add(i + 1);
                }
            }
            return starts.stream().mapToInt(Integer::intValue).toArray();
        }

        /** Spans are 1-based line and column, one column per character. */
        private static int offset(int[] lineStart, int line, int col) {
            return line - 1 < lineStart.length ? lineStart[line - 1] + col - 1 : Integer.MAX_VALUE;
        }

        Optional<CompiledStrategy> with(String filterTsl) {
            Optional<StrategyAst.StrategyDecl> probe = parse("strategy \"f\" { rule f { IF "
                    + filterTsl + " THEN BUY ALL } }");
            if (probe.isEmpty() || decl == null) {
                return Optional.empty();
            }
            Expr filter = probe.get().rules().get(0).body().get(0).condition();
            List<StrategyAst.RuleDecl> rules = new ArrayList<>();
            for (StrategyAst.RuleDecl r : decl.rules()) {
                List<StrategyAst.IfStmt> body = new ArrayList<>();
                for (StrategyAst.IfStmt s : r.body()) {
                    body.add(s.thenAction() instanceof StrategyAst.Action.Buy
                            ? new StrategyAst.IfStmt(new Expr.Binary(Expr.BinaryOp.AND,
                            s.condition(), filter, s.condition().span()),
                            s.thenAction(), s.elseAction(), s.span())
                            : s);
                }
                rules.add(new StrategyAst.RuleDecl(r.name(), body, r.span()));
            }
            return compile(Optional.of(new StrategyAst.StrategyDecl(decl.name(), decl.config(),
                    decl.lets(), rules, decl.span())));
        }
    }
}
