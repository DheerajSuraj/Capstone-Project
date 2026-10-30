package com.tsb.research;

import com.tsb.compiler.Analyzer;
import com.tsb.compiler.CompiledStrategy;
import com.tsb.compiler.Lexer;
import com.tsb.compiler.Parser;
import com.tsb.competition.CompetitionEngine;
import com.tsb.competition.EntryState;
import com.tsb.competition.RuleSet;
import com.tsb.execution.BacktestResult;
import com.tsb.execution.Backtester;
import com.tsb.execution.ExchangeRules;
import com.tsb.execution.Trade;
import com.tsb.marketdata.BinanceCsvParser;
import com.tsb.marketdata.CandleBar;
import com.tsb.marketdata.CandleSeries;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * Walk-forward search for a 15-minute strategy, run with TSB's own compiler,
 * backtester and competition engine — the same code the app uses.
 *
 * <p>The rule that keeps it honest: a strategy is chosen on the months
 * BEFORE a test window and then run, untouched, on the test window. The
 * only result that counts is the chain of those test windows. Picking the
 * best strategy on the whole history and reporting its return would show
 * what fitting can do, not what the strategy will do.
 *
 * <p>Then the placebo: the same search on the same candles shuffled inside
 * each month (same month-end prices, same candle sizes, random order). On
 * those, no rule can have an edge; a real edge must beat most of them.
 *
 * <pre>
 *   Search15m &lt;zip/csv file or folder&gt;... [--train 6] [--test 2] [--placebo 50]
 *   Search15m --synthetic 24 7 --placebo 10   (24 months of random-walk candles, seed 7)
 * </pre>
 *
 * Input files: Binance monthly kline ZIPs (BTCUSDT-15m-2024-01.zip from
 * data.binance.vision) or CSVs whose first six columns are open time in
 * epoch ms (or µs), open, high, low, close, volume.
 *
 * <p>Run it from {@code backend/} after {@code mvnw test-compile}:
 * {@code java -cp target/classes:target/test-classes com.tsb.research.Search15m ...}
 * (a {@code ;} instead of {@code :} on Windows). It lives with the tests so
 * it never ships in the app; it is a research tool, not a test.
 */
public final class Search15m {

    static final long BAR = 15 * 60_000L;
    static final double CAPITAL = 10_000;
    static final int MIN_TRAIN_TRADES = 20;

    record Variant(String family, String label, String rules, CompiledStrategy compiled) {
    }

    record Score(double retPct, double maxDdPct, int trades, double winPct, double profitFactor) {
        double objective() {
            // Return per unit of pain; nothing that lost money or barely traded.
            if (trades < MIN_TRAIN_TRADES || retPct <= 0) {
                return Double.NEGATIVE_INFINITY;
            }
            return retPct / Math.max(maxDdPct, 2.0);
        }
    }

    record Fold(int trainFrom, int testFrom, int testTo) {
    }

    public static void main(String[] args) throws IOException {
        int train = 6;
        int test = 2;
        int placebos = 0;
        List<Path> inputs = new ArrayList<>();
        CandleSeries series = null;
        String source;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--train" -> train = Integer.parseInt(args[++i]);
                case "--test" -> test = Integer.parseInt(args[++i]);
                case "--placebo" -> placebos = Integer.parseInt(args[++i]);
                case "--synthetic" -> series = synthetic(Integer.parseInt(args[++i]),
                        Long.parseLong(args[++i]), -0.003 * 0.003 / 2, 0.003, 40_000,
                        Instant.parse("2024-01-01T00:00:00Z").toEpochMilli());
                default -> inputs.add(Path.of(args[i]));
            }
        }
        if (series == null) {
            series = load(inputs);
            source = inputs.toString();
        } else {
            source = "SYNTHETIC random walk (no real market data)";
        }
        if (series.size() < 1000) {
            System.out.println("Not enough candles: " + series.size());
            return;
        }
        System.out.printf(Locale.ROOT, "# 15m walk-forward search%n%nData: %s%n%d candles, %s to %s, %d gaps%n%n",
                source, series.size(), day(series.openTimeMillis()[0]),
                day(series.openTimeMillis()[series.size() - 1]), gaps(series));

        List<Variant> variants = variants();
        System.out.printf(Locale.ROOT, "%d candidate strategies in %d families; train %d months, test %d months, fee 0.1%% per fill.%n%n",
                variants.size(), variants.stream().map(Variant::family).distinct().count(), train, test);

        Summary real = walkForward(series, variants, train, test);
        if (real == null) {
            System.out.println("Need more than " + train + " months of data.");
            return;
        }
        System.out.println("| Test window | Picked on the months before | Train return | Test return | Buy & hold | Test max DD | Test trades |");
        System.out.println("|---|---|---:|---:|---:|---:|---:|");
        real.rows().forEach(System.out::println);
        System.out.printf(Locale.ROOT, "%nOut of sample, chained: **%+.2f%%** vs buy & hold %+.2f%% over the same months.%n",
                real.oosPct(), real.holdPct());
        System.out.printf(Locale.ROOT, "Test windows with a profit: %d of %d; beat buy & hold: %d of %d.%n",
                real.positive(), real.folds(), real.beat(), real.folds());
        System.out.printf(Locale.ROOT, "Prop challenge step 1 (+10%%, 10%% fixed max loss, 5%% daily loss ends it, 4 days) on 30-day windows of the test months: %s%n",
                real.prop().isEmpty() ? "no windows" : real.prop().toString());

        // Placebo: the same search on the same candles shuffled inside each
        // month. Every month still starts and ends at the same price (so buy
        // & hold is unchanged) with the same candle sizes, but the order —
        // the only thing a timing rule can use — is random. A real edge
        // has to beat what the shuffles produce.
        if (placebos > 0) {
            List<Double> fake = new ArrayList<>();
            int passed = 0;
            int windows = 0;
            for (int k = 1; k <= placebos; k++) {
                Summary f = walkForward(shuffled(series, 1000 + k), variants, train, test);
                fake.add(f.oosPct());
                passed += f.prop().getOrDefault(EntryState.Status.PASSED, 0);
                windows += f.prop().values().stream().mapToInt(Integer::intValue).sum();
            }
            long beaten = fake.stream().filter(x -> x < real.oosPct()).count();
            System.out.printf(Locale.ROOT, "%nPlacebo: the same search on %d copies of the data shuffled inside each month.%n",
                    placebos);
            System.out.printf(Locale.ROOT, "Their out-of-sample results: %s%n", fake.stream()
                    .sorted().map(x -> String.format(Locale.ROOT, "%+.1f%%", x)).toList());
            System.out.printf(Locale.ROOT, "The data in its real order beat %d of %d of them. Prop passes on the shuffles: %d of %d windows.%n",
                    beaten, placebos, passed, windows);
        }

        // The strategy to try next: picked on the latest months only. Its
        // future is unknown; the chain above is the evidence for the method.
        List<Integer> months = monthStarts(series);
        int lastFrom = months.get(Math.max(0, months.size() - train));
        Variant next = null;
        Score nextScore = null;
        for (Variant v : variants) {
            Score sc = score(v, series, lastFrom, series.size());
            if (sc.objective() > (nextScore == null ? Double.NEGATIVE_INFINITY : nextScore.objective())) {
                next = v;
                nextScore = sc;
            }
        }
        System.out.println();
        if (next == null) {
            System.out.println("Latest " + train + " months: no candidate made money with at least "
                    + MIN_TRAIN_TRADES + " trades. Recommendation: do not trade these rules.");
        } else {
            System.out.printf(Locale.ROOT, "Latest %d months, best candidate: %s %s: %+.2f%%, max DD %.2f%%, %d trades, win %.0f%%, profit factor %.2f (in sample, so expect less).%n%n```%n%s%n```%n",
                    train, next.family(), next.label(), nextScore.retPct(), nextScore.maxDdPct(),
                    nextScore.trades(), nextScore.winPct(), nextScore.profitFactor(), tsl(next));
        }
    }

    record Summary(double oosPct, double holdPct, int positive, int beat, int folds,
                   Map<EntryState.Status, Integer> prop, List<String> rows) {
    }

    /**
     * Pick on each training window, run on the test window after it, chain
     * the test windows. Null when there is not enough data for one fold.
     */
    static Summary walkForward(CandleSeries series, List<Variant> variants, int train, int test) {
        List<Integer> months = monthStarts(series);
        List<Fold> folds = new ArrayList<>();
        for (int k = train; k < months.size(); k += test) {
            int to = k + test < months.size() ? months.get(k + test) : series.size();
            folds.add(new Fold(months.get(k - train), months.get(k), to));
        }
        if (folds.isEmpty()) {
            return null;
        }
        List<String> rows = new ArrayList<>();
        double oos = 1;
        double hold = 1;
        int beatHold = 0;
        int positive = 0;
        Map<EntryState.Status, Integer> prop = new EnumMap<>(EntryState.Status.class);
        for (Fold f : folds) {
            Variant best = null;
            Score bestScore = null;
            for (Variant v : variants) {
                Score s = score(v, series, f.trainFrom(), f.testFrom());
                if (s.objective() > (bestScore == null ? Double.NEGATIVE_INFINITY : bestScore.objective())) {
                    best = v;
                    bestScore = s;
                }
            }
            double bh = buyHold(series, f.testFrom(), f.testTo());
            hold *= 1 + bh / 100;
            String window = day(series.openTimeMillis()[f.testFrom()]) + " to "
                    + day(series.openTimeMillis()[f.testTo() - 1]);
            if (best == null) {
                // Nothing made money in training: the honest choice is to stay out.
                rows.add(String.format(Locale.ROOT, "| %s | nothing qualified, stay flat | - | +0.00%% | %+.2f%% | 0.00%% | 0 |", window, bh));
                if (bh < 0) {
                    beatHold++;
                }
                continue;
            }
            Score t = score(best, series, f.testFrom(), f.testTo());
            oos *= 1 + t.retPct() / 100;
            if (t.retPct() > bh) {
                beatHold++;
            }
            if (t.retPct() > 0) {
                positive++;
            }
            rows.add(String.format(Locale.ROOT, "| %s | %s %s | %+.2f%% | %+.2f%% | %+.2f%% | %.2f%% | %d |",
                    window, best.family(), best.label(), bestScore.retPct(), t.retPct(), bh, t.maxDdPct(), t.trades()));
            propWindows(best, series, f.testFrom(), f.testTo(), prop);
        }
        return new Summary((oos - 1) * 100, (hold - 1) * 100, positive, beatHold, folds.size(), prop, rows);
    }

    /**
     * The candles of each calendar month in a random order, chained so each
     * opens where the last closed (with its own gap). Month-end prices, candle
     * sizes and wicks are kept; only the order within the month is new.
     */
    static CandleSeries shuffled(CandleSeries s, long seed) {
        Random r = new Random(seed);
        int n = s.size();
        double[] gap = new double[n];
        double[] body = new double[n];
        double[] up = new double[n];
        double[] down = new double[n];
        for (int i = 0; i < n; i++) {
            double o = s.open()[i];
            double c = s.close()[i];
            gap[i] = i == 0 ? 0 : Math.log(o / s.close()[i - 1]);
            body[i] = Math.log(c / o);
            up[i] = Math.log(s.high()[i] / Math.max(o, c));
            down[i] = Math.log(Math.min(o, c) / s.low()[i]);
        }
        int[] order = new int[n];
        List<Integer> months = monthStarts(s);
        for (int m = 0; m < months.size(); m++) {
            int from = months.get(m);
            int to = m + 1 < months.size() ? months.get(m + 1) : n;
            for (int i = from; i < to; i++) {
                order[i] = i;
            }
            for (int i = to - 1; i > from; i--) { // Fisher-Yates inside the month
                int j = from + r.nextInt(i - from + 1);
                int tmp = order[i];
                order[i] = order[j];
                order[j] = tmp;
            }
        }
        double[] o = new double[n];
        double[] h = new double[n];
        double[] l = new double[n];
        double[] c = new double[n];
        double prev = s.open()[0];
        for (int i = 0; i < n; i++) {
            int k = order[i];
            o[i] = i == 0 ? s.open()[0] : prev * Math.exp(gap[k]);
            c[i] = o[i] * Math.exp(body[k]);
            h[i] = Math.max(o[i], c[i]) * Math.exp(up[k]);
            l[i] = Math.min(o[i], c[i]) * Math.exp(-down[k]);
            prev = c[i];
        }
        return new CandleSeries(s.openTimeMillis().clone(), o, h, l, c, s.volume().clone());
    }

    // ── Candidates ──────────────────────────────────────────────────────

    static List<Variant> variants() {
        List<Variant> out = new ArrayList<>();
        for (int t : new int[] {100, 200})
            for (int lo : new int[] {25, 30, 35})
                for (int hi : new int[] {60, 70})
                    for (double sl : new double[] {1.5, 3})
                        add(out, "trend-pullback", "EMA" + t + " RSI↑" + lo + " exit" + hi + " SL" + sl, """
                                let trend = EMA(CLOSE, %d)
                                let rsi = RSI(14)
                                rule entry { IF CLOSE > trend AND CROSSOVER(rsi, %d) THEN BUY ALL }
                                rule exit { IF rsi > %d OR CLOSE < trend THEN SELL ALL }
                                rule protect { IF CLOSE > 0 THEN SET STOPLOSS = %s%% }""".formatted(t, lo, hi, pct(sl)));
        for (int n : new int[] {48, 96, 192})
            for (int m : new int[] {24, 48, 96})
                for (double tr : new double[] {2, 4})
                    add(out, "breakout", "Donchian" + n + "/" + m + " trail" + tr, """
                            let up = DONCHIAN_UPPER(%d)
                            let lo = DONCHIAN_LOWER(%d)
                            rule entry { IF CLOSE > up[1] THEN BUY ALL }
                            rule exit { IF CLOSE < lo[1] THEN SELL ALL }
                            rule protect { IF CLOSE > 0 THEN SET TRAILING = %s%% }""".formatted(n, m, pct(tr)));
        for (int fast : new int[] {9, 21})
            for (int slow : new int[] {50, 100})
                for (int adx : new int[] {20, 25})
                    for (double sl : new double[] {2, 4})
                        add(out, "ema-cross", "EMA" + fast + "/" + slow + " ADX>" + adx + " SL" + sl, """
                                let fast = EMA(CLOSE, %d)
                                let slow = EMA(CLOSE, %d)
                                let adx = ADX(14)
                                rule entry { IF CROSSOVER(fast, slow) AND adx > %d THEN BUY ALL }
                                rule exit { IF CROSSUNDER(fast, slow) THEN SELL ALL }
                                rule protect { IF CLOSE > 0 THEN SET STOPLOSS = %s%% }""".formatted(fast, slow, adx, pct(sl)));
        for (int t : new int[] {50, 100})
            for (int lo : new int[] {25, 30})
                for (int hi : new int[] {55, 65})
                    for (double sl : new double[] {2, 4})
                        add(out, "4h-trend-dip", "EMA" + t + "@4h RSI<" + lo + " exit" + hi + " SL" + sl, """
                                let trend = EMA(CLOSE, %d) ON 4h
                                let rsi = RSI(14)
                                rule entry { IF CLOSE > trend AND rsi < %d THEN BUY ALL }
                                rule exit { IF rsi > %d THEN SELL ALL }
                                rule protect { IF CLOSE > 0 THEN SET STOPLOSS = %s%% }""".formatted(t, lo, hi, pct(sl)));
        for (double k : new double[] {2, 2.5})
            for (double sl : new double[] {1.5, 3})
                add(out, "band-revert", "BB20x" + k + " SL" + sl, """
                        let lower = BB_LOWER(CLOSE, 20, %s)
                        let mid = SMA(CLOSE, 20)
                        let trend = EMA(CLOSE, 200)
                        rule entry { IF CLOSE < lower AND CLOSE > trend THEN BUY ALL }
                        rule exit { IF CLOSE > mid THEN SELL ALL }
                        rule protect { IF CLOSE > 0 THEN SET STOPLOSS = %s%% }""".formatted(pct(k), pct(sl)));
        for (int p : new int[] {10, 14})
            for (double m : new double[] {2, 3, 4})
                add(out, "supertrend", "ST" + p + "x" + m, """
                        let st = SUPERTREND(%d, %s)
                        rule entry { IF CROSSOVER(CLOSE, st) THEN BUY ALL }
                        rule exit { IF CROSSUNDER(CLOSE, st) THEN SELL ALL }""".formatted(p, pct(m)));
        return out;
    }

    static String pct(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(v);
    }

    static void add(List<Variant> out, String family, String label, String rules) {
        out.add(new Variant(family, label, rules, compile(family + " " + label, rules)));
    }

    static String tsl(Variant v) {
        return "strategy \"" + v.family() + " " + v.label() + "\" {\n  symbol = BTCUSDT\n  timeframe = 15m\n"
                + "  capital = 10000\n  fee = 0.1%\n\n  " + v.rules().replace("\n", "\n  ") + "\n}";
    }

    static CompiledStrategy compile(String name, String rules) {
        String src = "strategy \"" + name.replace('"', '\'') + "\" {\n symbol = BTCUSDT\n timeframe = 15m\n"
                + " capital = 10000\n fee = 0.1%\n" + rules + "\n}";
        var lexed = new Lexer(src).scan();
        var parsed = new Parser(lexed.tokens()).parse();
        if (lexed.hasErrors() || parsed.hasErrors() || parsed.strategy().isEmpty()) {
            throw new IllegalStateException(name + ": " + lexed.diagnostics() + parsed.diagnostics());
        }
        var analyzed = new Analyzer(parsed.strategy().get()).analyze();
        if (analyzed.hasErrors() || analyzed.strategy().isEmpty()) {
            throw new IllegalStateException(name + ": " + analyzed.diagnostics());
        }
        return analyzed.strategy().get();
    }

    // ── Running ─────────────────────────────────────────────────────────

    /** Bars before {@code from} kept so indicators are warm when trading starts. */
    static int warmStart(Variant v, int from) {
        return Math.max(0, from - v.compiled().warmupBars() - 100);
    }

    /** The strategy trades only inside [from, to); earlier bars just warm it up. */
    static Score score(Variant v, CandleSeries all, int from, int to) {
        int start = warmStart(v, from);
        CandleSeries s = slice(all, start, to);
        BacktestResult r = new Backtester().run(v.compiled(), s, ExchangeRules.none(),
                com.tsb.execution.BarObserver.NONE, from - start);
        double[] eq = r.equityCurve();
        double peak = CAPITAL;
        double dd = 0;
        for (int i = from - start; i < eq.length; i++) {
            peak = Math.max(peak, eq[i]);
            dd = Math.max(dd, (peak - eq[i]) / peak * 100);
        }
        double won = 0;
        double lost = 0;
        int wins = 0;
        for (Trade t : r.trades()) {
            if (t.pnl() > 0) {
                won += t.pnl();
                wins++;
            } else {
                lost -= t.pnl();
            }
        }
        int n = r.trades().size();
        return new Score(r.totalReturnPct(), dd, n, n == 0 ? 0 : 100.0 * wins / n,
                lost == 0 ? (won > 0 ? 99 : 0) : won / lost);
    }

    static double buyHold(CandleSeries s, int from, int to) {
        return (s.close()[to - 1] / s.open()[from] - 1) * 100;
    }

    /** Runs the pick through the prop-challenge rules on each 30-day window. */
    static void propWindows(Variant v, CandleSeries all, int from, int to,
                            Map<EntryState.Status, Integer> tally) {
        RuleSet step1 = new RuleSet(CAPITAL, 0.001, 10, 10, 5, 0, 4,
                RuleSet.DailyLoss.FAIL, RuleSet.Drawdown.STATIC, true);
        int window = 30 * 96;
        for (int w = from; w + window <= to; w += window) {
            int start = warmStart(v, w);
            CandleSeries s = slice(all, start, w + window);
            CompetitionEngine engine = new CompetitionEngine(v.compiled(), s, ExchangeRules.none(), step1);
            EntryState st = EntryState.start(step1);
            for (int i = w - start; i < s.size() && !st.finished(); i++) {
                engine.step(st, i);
            }
            engine.finish(st, s.size() - 1);
            tally.merge(st.status, 1, Integer::sum);
        }
    }

    // ── Data ────────────────────────────────────────────────────────────

    static CandleSeries load(List<Path> inputs) throws IOException {
        TreeMap<Long, CandleBar> bars = new TreeMap<>();
        for (Path p : inputs) {
            List<Path> files;
            if (Files.isDirectory(p)) {
                try (Stream<Path> list = Files.list(p)) {
                    files = list.sorted().toList();
                }
            } else {
                files = List.of(p);
            }
            for (Path f : files) {
                String name = f.getFileName().toString().toLowerCase(Locale.ROOT);
                if (!name.endsWith(".zip") && !name.endsWith(".csv")) {
                    continue;
                }
                try (InputStream in = Files.newInputStream(f)) {
                    List<CandleBar> got = name.endsWith(".zip")
                            ? BinanceCsvParser.parseZip(in) : BinanceCsvParser.parseCsv(in);
                    for (CandleBar b : got) {
                        bars.put(b.openTime().toEpochMilli(), b);
                    }
                }
            }
        }
        int n = bars.size();
        long[] t = new long[n];
        double[] o = new double[n];
        double[] h = new double[n];
        double[] l = new double[n];
        double[] c = new double[n];
        double[] v = new double[n];
        int i = 0;
        for (CandleBar b : bars.values()) {
            t[i] = b.openTime().toEpochMilli();
            o[i] = b.open();
            h[i] = b.high();
            l[i] = b.low();
            c[i] = b.close();
            v[i] = b.volume();
            i++;
        }
        return new CandleSeries(t, o, h, l, c, v);
    }

    /**
     * A random walk: no edge exists in it by construction. {@code size}
     * under 1000 means months (30 days of 96 candles), otherwise candles.
     */
    static CandleSeries synthetic(int size, long seed, double mu, double sigma, double startPrice, long t0) {
        Random r = new Random(seed);
        int n = size < 1000 ? size * 30 * 96 : size;
        long[] t = new long[n];
        double[] o = new double[n];
        double[] h = new double[n];
        double[] l = new double[n];
        double[] c = new double[n];
        double[] v = new double[n];
        double p = startPrice;
        for (int i = 0; i < n; i++) {
            t[i] = t0 + i * BAR;
            o[i] = p;
            c[i] = p * Math.exp(mu + sigma * r.nextGaussian());
            h[i] = Math.max(o[i], c[i]) * (1 + Math.abs(r.nextGaussian()) * sigma * 0.5);
            l[i] = Math.min(o[i], c[i]) * (1 - Math.abs(r.nextGaussian()) * sigma * 0.5);
            v[i] = 1;
            p = c[i];
        }
        return new CandleSeries(t, o, h, l, c, v);
    }

    static CandleSeries slice(CandleSeries s, int from, int to) {
        return new CandleSeries(Arrays.copyOfRange(s.openTimeMillis(), from, to),
                Arrays.copyOfRange(s.open(), from, to), Arrays.copyOfRange(s.high(), from, to),
                Arrays.copyOfRange(s.low(), from, to), Arrays.copyOfRange(s.close(), from, to),
                Arrays.copyOfRange(s.volume(), from, to));
    }

    static List<Integer> monthStarts(CandleSeries s) {
        List<Integer> out = new ArrayList<>();
        YearMonth last = null;
        for (int i = 0; i < s.size(); i++) {
            YearMonth m = YearMonth.from(Instant.ofEpochMilli(s.openTimeMillis()[i]).atOffset(ZoneOffset.UTC));
            if (!m.equals(last)) {
                out.add(i);
                last = m;
            }
        }
        return out;
    }

    static int gaps(CandleSeries s) {
        int g = 0;
        for (int i = 1; i < s.size(); i++) {
            if (s.openTimeMillis()[i] - s.openTimeMillis()[i - 1] != BAR) {
                g++;
            }
        }
        return g;
    }

    static String day(long millis) {
        return Instant.ofEpochMilli(millis).atOffset(ZoneOffset.UTC).toLocalDate().toString();
    }

    private Search15m() {
    }
}
