package com.tsb.execution;

import com.tsb.compiler.Analyzer;
import com.tsb.compiler.AstPrinter;
import com.tsb.compiler.CompiledStrategy;
import com.tsb.compiler.Expr;
import com.tsb.compiler.Lexer;
import com.tsb.compiler.Parser;
import com.tsb.competition.CompetitionEngine;
import com.tsb.competition.EntryState;
import com.tsb.competition.RuleSet;
import com.tsb.marketdata.CandleSeries;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@code ... ON 4h}: the language, the warm-up, the values, the engines. */
@DisplayName("Multi-timeframe ON")
class MultiTimeframeTest {

    static final long HOUR = 3_600_000L;
    static final long MIDNIGHT = 1_704_067_200_000L; // 2024-01-01T00:00:00Z

    static String strategy(String timeframe, String body) {
        return "strategy \"T\" {\n symbol = BTCUSDT\n timeframe = " + timeframe
                + "\n capital = 10000\n fee = 0.1%\n" + body + "\n}";
    }

    static Analyzer.AnalysisResult analyze(String source) {
        Lexer.LexResult lexed = new Lexer(source).scan();
        Parser.ParseResult parsed = new Parser(lexed.tokens()).parse();
        assertEquals(List.of(), lexed.diagnostics());
        assertEquals(List.of(), parsed.diagnostics(), source);
        return new Analyzer(parsed.strategy().orElseThrow()).analyze();
    }

    static CompiledStrategy compile(String timeframe, String body) {
        Analyzer.AnalysisResult r = analyze(strategy(timeframe, body));
        assertEquals(List.of(), r.diagnostics());
        return r.strategy().orElseThrow();
    }

    static List<String> errors(String timeframe, String condition) {
        return analyze(strategy(timeframe, "let fast = EMA(CLOSE, 9)\n rule r { IF "
                + condition + " THEN BUY ALL }")).diagnostics().stream()
                .map(d -> d.code() + " " + d.message()).toList();
    }

    /** Parses an expression the way a strategy would hold it (as a let). */
    static Expr expr(String expression) {
        Parser.ParseResult r = new Parser(new Lexer(strategy("1h", "let x = " + expression
                + "\n rule r { IF CLOSE > 0 THEN BUY ALL }")).scan().tokens()).parse();
        assertEquals(List.of(), r.diagnostics(), expression);
        return r.strategy().orElseThrow().lets().get(0).value();
    }

    static String parsed(String expression) {
        return AstPrinter.print(expr(expression));
    }

    // ── Syntax ──────────────────────────────────────────────────────────

    @Nested
    @DisplayName("syntax")
    class Syntax {

        @Test
        @DisplayName("ON binds tighter than comparison and arithmetic")
        void precedence() {
            assertEquals("(EMA(CLOSE, 50) ON 4h > CLOSE)", parsed("EMA(CLOSE, 50) ON 4h > CLOSE"));
            assertEquals("(CLOSE > EMA(CLOSE, 50) ON 4h)", parsed("CLOSE > EMA(CLOSE, 50) ON 4h"));
            assertEquals("(CLOSE ON 1d - 1)", parsed("CLOSE ON 1d - 1"));
            assertEquals("-CLOSE ON 1d", parsed("-CLOSE ON 1d"));
        }

        @Test
        @DisplayName("a lookback inside ON counts big candles; outside, the strategy's own")
        void lookbacks() {
            Expr inside = expr("CLOSE[1] ON 1d");
            assertTrue(inside instanceof Expr.On o && o.target() instanceof Expr.Lookback);
            Expr outside = expr("(CLOSE ON 1d)[1]");
            assertTrue(outside instanceof Expr.Lookback l && l.target() instanceof Expr.On);
        }

        @Test
        @DisplayName("ON without a timeframe is a clear parse error")
        void missingTimeframe() {
            Parser.ParseResult r = new Parser(new Lexer(strategy("1h",
                    "rule r { IF CLOSE > EMA(CLOSE, 50) ON THEN BUY ALL }")).scan().tokens()).parse();
            assertTrue(r.diagnostics().stream().anyMatch(d -> d.code().equals("PAR010")),
                    r.diagnostics().toString());
        }
    }

    // ── Checks ──────────────────────────────────────────────────────────

    @Nested
    @DisplayName("checks")
    class Checks {

        @Test
        @DisplayName("bigger timeframes that fit compile")
        void valid() {
            for (String ok : List.of("EMA(CLOSE, 50) ON 4h > CLOSE", "CLOSE > CLOSE[1] ON 1d",
                    "RSI(14) ON 1w < 30", "CROSSOVER(EMA(CLOSE, 9) ON 4h, EMA(CLOSE, 21) ON 4h)",
                    "(CLOSE ON 1d)[1] < CLOSE ON 1d", "ADX(14) ON 12h > 25")) {
                assertEquals(List.of(), errors("1h", ok), ok);
            }
        }

        @Test
        @DisplayName("smaller, equal and uneven timeframes are refused with the reason")
        void timeframes() {
            assertTrue(errors("1h", "EMA(CLOSE, 9) ON 15m > 0").get(0).contains("bigger timeframe"));
            assertTrue(errors("1h", "EMA(CLOSE, 9) ON 1h > 0").get(0).contains("already runs on 1h"));
            assertTrue(errors("4h", "EMA(CLOSE, 9) ON 6h > 0").get(0).contains("fit evenly"));
            assertTrue(errors("1h", "EMA(CLOSE, 9) ON 5h > 0").get(0).contains("ON accepts"));
        }

        @Test
        @DisplayName("ON on a calculation, a name, a function or twice says what to write instead")
        void shapes() {
            assertTrue(errors("1h", "(CLOSE + 1) ON 4h > 0").get(0).contains("name it first"));
            assertTrue(errors("1h", "fast ON 4h > 0").get(0).contains("let fast = EMA(CLOSE, 9) ON 4h"));
            assertTrue(errors("1h", "CROSSOVER(CLOSE, OPEN) ON 4h").get(0).contains("function"));
            assertTrue(errors("1h", "CLOSE ON 1d ON 1w > 0").get(0).contains("once"));
            assertTrue(errors("1h", "SMAA(CLOSE, 9) ON 4h > 0").get(0).contains("did you mean 'SMA'"));
        }

        @Test
        @DisplayName("huge periods and lookbacks are refused instead of overflowing the warm-up")
        void limits() {
            assertTrue(errors("1h", "RSI(10000000000) > 30").get(0).contains("at most"));
            assertTrue(errors("1h", "CLOSE[3000000000] > 1").get(0).contains("at most"));
        }
    }

    // ── Warm-up and keys ────────────────────────────────────────────────

    @Test
    @DisplayName("warm-up is counted in the strategy's own candles")
    void warmup() {
        // SMA(20) needs 20 four-hour candles; +2 for a partial first one and
        // the wait for each to close; x4 one-hour candles each.
        assertEquals((20 + 2) * 4, compile("1h",
                "rule r { IF CLOSE > SMA(CLOSE, 20) ON 4h THEN BUY ALL }").warmupBars());
        assertEquals((1 + 2) * 24, compile("1h",
                "rule r { IF CLOSE > CLOSE[1] ON 1d THEN BUY ALL }").warmupBars());
        // A lookback outside ON counts own candles: +1.
        assertEquals((0 + 2) * 6 + 1, compile("4h",
                "rule r { IF CLOSE > (CLOSE ON 1d)[1] THEN BUY ALL }").warmupBars());
    }

    @Test
    @DisplayName("each timeframe gets its own key; own-candle keys are unchanged")
    void keys() {
        CompiledStrategy s = compile("1h", """
                rule r { IF EMA(CLOSE, 50) > EMA(CLOSE, 50) ON 4h AND CLOSE > HIGH[1] ON 1d THEN BUY ALL }
                """);
        Set<String> keys = s.indicators().stream()
                .map(CompiledStrategy.IndicatorInstance::key).collect(Collectors.toSet());
        assertEquals(Set.of("EMA(CLOSE,50)", "EMA(CLOSE,50)@4h", "PRICE(HIGH)[1]@1d"), keys);
    }

    // ── Values ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("values equal the indicator computed on 4h candles built by hand")
    void valuesMatchHandBuiltCandles() {
        CandleSeries hours = LookAheadTest.candles(3, 24 * 30);
        // Hand-built 4h candles: groups of four hours (the data starts at midnight).
        int m = hours.size() / 4;
        long[] t = new long[m];
        double[] o = new double[m], h = new double[m], l = new double[m],
                c = new double[m], v = new double[m];
        for (int j = 0; j < m; j++) {
            t[j] = hours.openTimeMillis()[4 * j];
            o[j] = hours.open()[4 * j];
            c[j] = hours.close()[4 * j + 3];
            h[j] = Double.NEGATIVE_INFINITY;
            l[j] = Double.POSITIVE_INFINITY;
            for (int q = 0; q < 4; q++) {
                h[j] = Math.max(h[j], hours.high()[4 * j + q]);
                l[j] = Math.min(l[j], hours.low()[4 * j + q]);
                v[j] += hours.volume()[4 * j + q];
            }
        }
        CandleSeries fours = new CandleSeries(t, o, h, l, c, v);
        double[] rsi4h = Indicators.rsi(fours.close(), 14);
        double[] atr4h = Indicators.atr(fours.high(), fours.low(), fours.close(), 14);

        CompiledStrategy s = compile("1h",
                "rule r { IF RSI(14) ON 4h < 30 AND ATR(14)[2] ON 4h > 0 THEN BUY ALL }");
        Map<String, double[]> bank = IndicatorBank.compute(s, hours);
        double[] rsi = bank.get("RSI(14)@4h");
        double[] atr = bank.get("ATR(14)[2]@4h");
        for (int i = 0; i < hours.size(); i++) {
            int last = (i + 1) / 4 - 1; // last 4h candle closed by the end of hour i
            assertEquals(last >= 0 ? rsi4h[last] : Double.NaN, rsi[i], 0.0, "RSI at hour " + i);
            assertEquals(last - 2 >= 0 ? atr4h[last - 2] : Double.NaN, atr[i], 0.0, "ATR[2] at hour " + i);
        }
    }

    // ── Engines ─────────────────────────────────────────────────────────

    static final String MTF_STRATEGY = """
            let trend = EMA(CLOSE, 20) ON 4h
            let day = CLOSE[1] ON 1d
            rule entry { IF CLOSE > trend AND CLOSE > day AND RSI(14) < 45 THEN BUY ALL }
            rule exit { IF CROSSUNDER(CLOSE, trend) OR RSI(14) ON 4h > 70 THEN SELL ALL }
            rule protect { IF CLOSE > 0 THEN SET STOPLOSS = 3% }
            """;

    @Test
    @DisplayName("a strategy using ON trades, and the competition engine makes exactly the same trades")
    void competitionMatchesBacktester() {
        CandleSeries hours = LookAheadTest.candles(21, 24 * 60);
        CompiledStrategy s = compile("1h", MTF_STRATEGY);
        RuleSet unlimited = new RuleSet(10000, 0.001, 1e9, 100, 0, 0, 0);
        CompetitionEngine engine = new CompetitionEngine(s, hours, ExchangeRules.none(), unlimited);
        int start = s.warmupBars();
        BacktestResult bt = new Backtester().run(engine.strategy(), hours,
                ExchangeRules.none(), BarObserver.NONE, start);
        assertTrue(bt.trades().size() >= 3, "it should trade: " + bt.trades().size());

        EntryState st = EntryState.start(unlimited);
        List<CompetitionEngine.Closed> closed = new ArrayList<>();
        for (int i = start; i < hours.size(); i++) {
            for (CompetitionEngine.Event e : engine.step(st, i)) {
                if (e instanceof CompetitionEngine.Closed x) {
                    closed.add(x);
                }
            }
        }
        engine.finish(st, hours.size() - 1).forEach(e -> closed.add((CompetitionEngine.Closed) e));
        assertEquals(bt.trades().size(), closed.size());
        for (int k = 0; k < closed.size(); k++) {
            assertEquals(bt.trades().get(k).exitTime().toEpochMilli(), closed.get(k).exitTime());
            assertEquals(bt.trades().get(k).exitPrice(), closed.get(k).exitPrice(), 1e-12);
        }
    }

    @Test
    @DisplayName("ON values never move when later candles change (the look-ahead test, for ON)")
    void noLookAhead() {
        CandleSeries s = LookAheadTest.candles(5, 1500);
        CompiledStrategy st = compile("1h", MTF_STRATEGY);
        BacktestResult full = new Backtester().run(st, s, ExchangeRules.none());
        Map<String, double[]> fullBank = IndicatorBank.compute(st, s);
        for (int cut : LookAheadTest.cuts(s.size(), st.warmupBars(), 99)) {
            CandleSeries part = LookAheadTest.head(s, cut);
            Map<String, double[]> partBank = IndicatorBank.compute(st, part);
            for (String key : fullBank.keySet()) {
                Integer leak = LookAheadTest.firstLeak(fullBank.get(key), partBank.get(key), cut);
                assertEquals(null, leak, key + " changed at " + leak + " with the cut at " + cut);
            }
            LookAheadTest.assertSameDecisions(st, s, part, cut, MTF_STRATEGY);
            List<Trade> done = new Backtester().run(st, part, ExchangeRules.none()).trades()
                    .stream().filter(t -> t.exitReason() != Trade.ExitReason.END_OF_DATA).toList();
            for (int k = 0; k < done.size(); k++) {
                assertEquals(full.trades().get(k), done.get(k), "trade " + k + " at cut " + cut);
            }
        }
    }

    @Test
    @DisplayName("with an unsupported own timeframe, ON reports errors instead of crashing")
    void brokenOwnTimeframe() {
        Analyzer.AnalysisResult r = analyze(strategy("2h",
                "rule r { IF CLOSE > EMA(CLOSE, 9) ON 4h THEN BUY ALL }"));
        assertTrue(r.strategy().isEmpty());
        assertTrue(r.diagnostics().stream().anyMatch(d -> d.message().contains("timeframe")),
                r.diagnostics().toString());
    }
}
