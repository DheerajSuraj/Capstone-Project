package com.tsb.execution;

import com.tsb.compiler.Analyzer;
import com.tsb.compiler.CompiledStrategy;
import com.tsb.compiler.Lexer;
import com.tsb.compiler.Parser;
import com.tsb.compiler.Registry;
import com.tsb.marketdata.CandleSeries;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The automatic look-ahead test: proves that nothing a strategy can read at
 * candle i depends on candles after i.
 *
 * <p><b>How:</b> compute everything on the full candle series, then again on
 * the same series cut short at many points. If any value at a candle before
 * the cut changes, that value was using candles after the cut — the future.
 * This is the idea behind Freqtrade's {@code lookahead-analysis}, run here on
 * every build instead of on request.
 *
 * <p>It covers <b>every indicator in the registry automatically</b> (a new
 * indicator is tested the moment it is registered) and <b>whole
 * strategies</b>: their trades and equity, so the interpreter and the fill
 * loop are checked too, not just the indicator maths.
 *
 * <p>Comparison is exact (bit for bit, NaN equal to NaN): the same
 * arithmetic on the same past candles must give the same number.
 */
@DisplayName("No look-ahead (truncation test)")
class LookAheadTest {

    static final long HOUR = 3_600_000L;

    // ── Candles ─────────────────────────────────────────────────────────

    /**
     * A deterministic random walk with what real candles have: gaps between
     * one close and the next open, wicks, changing volume. Starts at a UTC
     * midnight so bigger candles line up the same way real ones do.
     */
    static CandleSeries candles(long seed, int n) {
        Random r = new Random(seed);
        long[] t = new long[n];
        double[] o = new double[n], h = new double[n], l = new double[n],
                c = new double[n], v = new double[n];
        double px = 30_000;
        long start = 1_704_067_200_000L; // 2024-01-01T00:00:00Z
        for (int i = 0; i < n; i++) {
            double open = px * Math.exp(r.nextGaussian() * 0.004);
            double close = open * Math.exp(r.nextGaussian() * 0.012);
            t[i] = start + i * HOUR;
            o[i] = open;
            c[i] = close;
            h[i] = Math.max(open, close) * Math.exp(Math.abs(r.nextGaussian()) * 0.006);
            l[i] = Math.min(open, close) * Math.exp(-Math.abs(r.nextGaussian()) * 0.006);
            v[i] = 50 + 200 * r.nextDouble();
            px = close;
        }
        return new CandleSeries(t, o, h, l, c, v);
    }

    /** The first k candles. */
    static CandleSeries head(CandleSeries s, int k) {
        return new CandleSeries(
                java.util.Arrays.copyOf(s.openTimeMillis(), k),
                java.util.Arrays.copyOf(s.open(), k),
                java.util.Arrays.copyOf(s.high(), k),
                java.util.Arrays.copyOf(s.low(), k),
                java.util.Arrays.copyOf(s.close(), k),
                java.util.Arrays.copyOf(s.volume(), k));
    }

    /** Cut points: the edges, around the warm-up, and a spread at random. */
    static List<Integer> cuts(int n, int warmup, long seed) {
        Set<Integer> out = new TreeSet<>(List.of(1, 2, 3, 5, n - 1));
        for (int d = -2; d <= 2; d++) {
            int k = warmup + d;
            if (k >= 1 && k < n) {
                out.add(k);
            }
        }
        Random r = new Random(seed);
        for (int i = 0; i < 25; i++) {
            out.add(1 + r.nextInt(n - 1));
        }
        return List.copyOf(out);
    }

    /**
     * The check itself: the first candle before {@code cut} where the
     * shortened run disagrees with the full run, or null if none does.
     */
    static Integer firstLeak(double[] full, double[] part, int cut) {
        for (int i = 0; i < cut; i++) {
            if (Double.compare(full[i], part[i]) != 0) {
                return i;
            }
        }
        return null;
    }

    static CompiledStrategy compile(String body) {
        String source = "strategy \"T\" {\n symbol = BTCUSDT\n timeframe = 1h\n"
                + " capital = 10000\n fee = 0.1%\n" + body + "\n}";
        Lexer.LexResult lexed = new Lexer(source).scan();
        Parser.ParseResult parsed = new Parser(lexed.tokens()).parse();
        assertTrue(lexed.diagnostics().isEmpty() && parsed.diagnostics().isEmpty(),
                () -> "fixture must parse: " + lexed.diagnostics() + parsed.diagnostics()
                        + "\n" + source);
        Analyzer.AnalysisResult analyzed = new Analyzer(parsed.strategy().orElseThrow()).analyze();
        assertEquals(List.of(), analyzed.diagnostics(), "fixture must compile:\n" + source);
        return analyzed.strategy().orElseThrow();
    }

    // ── The checker can see a leak ──────────────────────────────────────

    @Test
    @DisplayName("the check catches a planted leak (so a pass means something)")
    void catchesAPlantedLeak() {
        CandleSeries s = candles(1, 200);
        // A cheating "indicator": tomorrow's close, today.
        java.util.function.Function<CandleSeries, double[]> cheat = c -> {
            double[] out = new double[c.size()];
            for (int i = 0; i < c.size(); i++) {
                out[i] = i + 1 < c.size() ? c.close()[i + 1] : Double.NaN;
            }
            return out;
        };
        double[] full = cheat.apply(s);
        double[] part = cheat.apply(head(s, 100));
        assertEquals(Integer.valueOf(99), firstLeak(full, part, 100),
                "the last candle before the cut read a candle after it");
        // And an honest one passes.
        assertNull(firstLeak(Indicators.sma(s.close(), 5),
                Indicators.sma(head(s, 100).close(), 5), 100));
    }

    // ── Every indicator ─────────────────────────────────────────────────

    /** A valid call for a registry entry, built from its parameter list,
     *  so new indicators are covered without touching this test. */
    static String callFor(Registry.Signature sig, boolean small) {
        List<String> args = new ArrayList<>();
        for (Registry.Param p : sig.params()) {
            switch (p.kind()) {
                case PRICE_SERIES -> args.add("CLOSE");
                case CONST_NUMBER -> {
                    if (p.positiveInt()) {
                        args.add(String.valueOf(small ? switch (p.name()) {
                            case "fast" -> 2; // MACD: fast below slow, or the
                            case "slow" -> 4; // analyzer warns (equal = always 0)
                            default -> 3;
                        } : switch (p.name()) {
                            case "fast" -> 12;
                            case "slow" -> 26;
                            case "signal" -> 9;
                            case "dSmooth" -> 3;
                            default -> 14;
                        }));
                    } else {
                        args.add(p.name().equals("multiplier") ? "3" : "2");
                    }
                }
                case NUMERIC -> throw new AssertionError(
                        "a precomputed indicator with a NUMERIC parameter needs a "
                                + "case in this test: " + sig.describe());
            }
        }
        return sig.name() + "(" + String.join(", ", args) + ")";
    }

    @Test
    @DisplayName("every registered indicator, at every cut, only uses earlier candles")
    void everyIndicator() {
        CandleSeries s = candles(7, 700);
        int checked = 0;
        for (String name : Registry.allNames()) {
            Registry.Signature sig = Registry.lookup(name).orElseThrow();
            if (!sig.precomputed()) {
                continue; // CROSSOVER, MAX…: evaluated per candle, covered below
            }
            for (boolean small : new boolean[]{false, true}) {
                String call = callFor(sig, small);
                CompiledStrategy st = compile("let x = " + call
                        + "\n rule r { IF x > 0 THEN BUY ALL }");
                Map<String, double[]> full = IndicatorBank.compute(st, s);
                for (int cut : cuts(s.size(), st.warmupBars(), name.hashCode())) {
                    Map<String, double[]> part = IndicatorBank.compute(st, head(s, cut));
                    for (String key : full.keySet()) {
                        assertNotNull(part.get(key), key);
                        Integer leak = firstLeak(full.get(key), part.get(key), cut);
                        assertNull(leak, () -> call + " [" + key + "] changed at candle "
                                + leak + " when candles from " + cut
                                + " on were removed: it reads the future");
                    }
                    checked++;
                }
            }
        }
        assertTrue(checked > 30 * 2 * 20, "covered " + checked + " indicator/cut pairs");
    }

    @Test
    @DisplayName("every indicator read ON a bigger timeframe only uses bigger candles that had closed")
    void everyIndicatorOnBiggerTimeframes() {
        CandleSeries s = candles(17, 24 * 60);
        int checked = 0;
        for (String name : Registry.allNames()) {
            Registry.Signature sig = Registry.lookup(name).orElseThrow();
            if (!sig.precomputed()) {
                continue;
            }
            for (String[] variant : new String[][]{{"4h", "typical"}, {"4h", "small"}, {"1d", "small"}}) {
                String call = callFor(sig, variant[1].equals("small"));
                String tf = variant[0];
                CompiledStrategy st = compile("let x = " + call + " ON " + tf
                        + "\n let y = " + call + "[1] ON " + tf
                        + "\n rule r { IF x > y THEN BUY ALL }");
                Map<String, double[]> full = IndicatorBank.compute(st, s);
                for (int cut : cuts(s.size(), st.warmupBars(), (name + tf).hashCode())) {
                    Map<String, double[]> part = IndicatorBank.compute(st, head(s, cut));
                    for (String key : full.keySet()) {
                        Integer leak = firstLeak(full.get(key), part.get(key), cut);
                        assertNull(leak, () -> call + " ON " + tf + " [" + key
                                + "] changed at candle " + leak + " when candles from " + cut
                                + " on were removed: it reads a bigger candle before it closed");
                    }
                    checked++;
                }
            }
        }
        assertTrue(checked > 30 * 3 * 20, "covered " + checked + " pairs");
    }

    // ── Whole strategies ────────────────────────────────────────────────

    /** Strategies that exercise every part of the language and the engine. */
    static final List<String> STRATEGIES = List.of(
            // Trend Rider (starter): crossovers, a 200 filter, a stop.
            """
            let fast = EMA(CLOSE, 20)
            let slow = EMA(CLOSE, 50)
            rule entry { IF CROSSOVER(fast, slow) AND CLOSE > SMA(CLOSE, 200) THEN BUY ALL }
            rule exit { IF CROSSUNDER(fast, slow) THEN SELL ALL }
            rule protect { IF CLOSE > 0 THEN SET STOPLOSS = 8% }
            """,
            // Breakout (starter): lookback on a channel, ADX, trailing stop.
            """
            rule entry { IF CLOSE > DONCHIAN_UPPER(20)[1] AND ADX(14) > 20 THEN BUY ALL }
            rule exit { IF CLOSE < DONCHIAN_LOWER(10)[1] THEN SELL ALL }
            rule protect { IF CLOSE > 0 THEN SET TRAILING = 3% }
            """,
            // RSI dip: stop and take-profit together.
            """
            let rsi = RSI(14)
            rule entry { IF rsi < 35 THEN BUY ALL }
            rule exit { IF rsi > 55 THEN SELL ALL }
            rule protect {
                IF CLOSE > 0 THEN SET STOPLOSS = 2%
                IF CLOSE > 0 THEN SET TAKEPROFIT = 3%
            }
            """,
            // ELSE branches, partial sells, % sizing, lets of lets, lookback of a let.
            """
            let m = MACD_LINE(12, 26) - MACD_SIGNAL(12, 26, 9)
            let rising = m > m[1]
            rule a { IF rising AND NOT (CLOSE < BB_LOWER(CLOSE, 20, 2)) THEN BUY qty = 50% OF EQUITY ELSE SELL qty = 50% OF POSITION }
            rule b { IF CROSSUNDER(CLOSE, SUPERTREND(10, 3)) THEN SELL ALL }
            """,
            // Arithmetic, MAX/MIN/ABS, volume, OR.
            """
            let body = ABS(CLOSE - OPEN) / OPEN * 100
            rule a { IF body > 0.8 AND VOLUME > SMA(VOLUME, 20) OR CLOSE > MAX(HIGHEST(HIGH, 10)[1], VWAP()) THEN BUY ALL }
            rule b { IF CLOSE < MIN(LOWEST(LOW, 10)[1], EMA(CLOSE, 9)) THEN SELL ALL }
            rule c { IF CLOSE > 0 THEN SET TRAILING = 2% }
            """
    );

    @Test
    @DisplayName("whole strategies: trades and equity before each cut never change")
    void wholeStrategies() {
        CandleSeries s = candles(42, 900);
        int trades = 0;
        for (String body : STRATEGIES) {
            CompiledStrategy st = compile(body);
            BacktestResult full = new Backtester().run(st, s, ExchangeRules.none());
            trades += full.trades().size();
            for (int cut : cuts(s.size(), st.warmupBars(), body.hashCode())) {
                BacktestResult part = new Backtester().run(st, head(s, cut), ExchangeRules.none());
                // Every trade the short run finished on its own (not the
                // forced close when its data ran out) happened in the full
                // run too, identically and in the same order.
                List<Trade> done = part.trades().stream()
                        .filter(t -> t.exitReason() != Trade.ExitReason.END_OF_DATA).toList();
                for (int k = 0; k < done.size(); k++) {
                    assertEquals(full.trades().get(k), done.get(k),
                            "trade " + k + " changed at cut " + cut + " in:\n" + body);
                }
                // Equity up to the candle before the last one (the last is
                // where the short run force-closes its position).
                if (cut >= 2) {
                    double[] f = java.util.Arrays.copyOf(full.equityCurve(), cut - 1);
                    double[] p = java.util.Arrays.copyOf(part.equityCurve(), cut - 1);
                    Integer leak = firstLeak(f, p, cut - 1);
                    assertNull(leak, () -> "equity changed at candle " + leak
                            + " with the cut at " + cut + " in:\n" + body);
                }
                // Every decision, on every candle before the cut — the last
                // one too. Trades alone can miss a leak of one candle: the
                // decision it changes, on the last candle, never fills.
                assertSameDecisions(st, s, head(s, cut), cut, body);
            }
        }
        assertTrue(trades > 50, "the strategies must actually trade (" + trades + ")");
    }

    /** Each IF's true/false at every candle before {@code cut}, both runs. */
    static void assertSameDecisions(CompiledStrategy st, CandleSeries fullSeries,
                                    CandleSeries partSeries, int cut, String body) {
        Interpreter full = new Interpreter(st.lets(), fullSeries,
                IndicatorBank.compute(st, fullSeries));
        Interpreter part = new Interpreter(st.lets(), partSeries,
                IndicatorBank.compute(st, partSeries));
        for (var rule : st.rules()) {
            for (var stmt : rule.body()) {
                for (int i = 0; i < cut; i++) {
                    boolean a = full.bool(stmt.condition(), i);
                    boolean b = part.bool(stmt.condition(), i);
                    if (a != b) {
                        throw new AssertionError("rule '" + rule.name() + "' decided "
                                + a + " with all the data but " + b + " without the candles from "
                                + cut + " on, at candle " + i + " — it reads the future:\n" + body);
                    }
                }
            }
        }
    }

    @Nested
    @DisplayName("the data itself")
    class Data {
        @Test
        @DisplayName("the test candles have real gaps and wicks")
        void realisticCandles() {
            CandleSeries s = candles(42, 900);
            int gaps = 0;
            for (int i = 1; i < s.size(); i++) {
                if (s.open()[i] != s.close()[i - 1]) {
                    gaps++;
                }
                assertTrue(s.high()[i] >= Math.max(s.open()[i], s.close()[i]));
                assertTrue(s.low()[i] <= Math.min(s.open()[i], s.close()[i]));
            }
            assertTrue(gaps > 800);
            assertFalse(s.isEmpty());
        }
    }
}
