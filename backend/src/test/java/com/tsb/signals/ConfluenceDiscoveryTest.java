package com.tsb.signals;

import com.tsb.compiler.Analyzer;
import com.tsb.compiler.CompiledStrategy;
import com.tsb.compiler.Lexer;
import com.tsb.compiler.Parser;
import com.tsb.execution.BacktestResult;
import com.tsb.execution.Backtester;
import com.tsb.execution.ExchangeRules;
import com.tsb.execution.Trade;
import com.tsb.marketdata.CandleSeries;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.function.IntUnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("ConfluenceDiscovery")
class ConfluenceDiscoveryTest {

    private static final String SOURCE = """
            strategy "T" {
                symbol = BTCUSDT
                timeframe = 1h
                capital = 1000
                rule entry { IF CLOSE > OPEN THEN BUY ALL }
                rule exit { IF CLOSE < OPEN THEN SELL ALL }
            }
            """;

    private static CompiledStrategy compile(String source) {
        Lexer.LexResult lexed = new Lexer(source).scan();
        Parser.ParseResult parsed = new Parser(lexed.tokens()).parse();
        return new Analyzer(parsed.strategy().orElseThrow()).analyze().strategy().orElseThrow();
    }

    /** A seeded random walk, hourly. */
    private static CandleSeries walk(int n, long seed) {
        Random r = new Random(seed);
        long[] t = new long[n];
        double[] o = new double[n], h = new double[n], l = new double[n], c = new double[n],
                v = new double[n];
        double px = 100;
        for (int i = 0; i < n; i++) {
            t[i] = 1_704_067_200_000L + i * 3_600_000L;
            o[i] = px;
            px *= Math.exp(r.nextGaussian() * 0.01);
            c[i] = px;
            h[i] = Math.max(o[i], c[i]) * 1.002;
            l[i] = Math.min(o[i], c[i]) * 0.998;
            v[i] = 100 + r.nextInt(900);
        }
        return new CandleSeries(t, o, h, l, c, v);
    }

    /** One trade per 30 candles from candle 300; {@code wins} decides each outcome from its signal candle. */
    private static BacktestResult trades(CandleSeries c, int count,
                                         java.util.function.IntPredicate wins) {
        List<Trade> list = new ArrayList<>();
        for (int sig = 300; list.size() < count; sig += 30) {
            double pnl = wins.test(sig) ? 10 : -10;
            list.add(new Trade(sig + 1, sig + 5,
                    Instant.ofEpochMilli(c.openTimeMillis()[sig + 1]),
                    Instant.ofEpochMilli(c.openTimeMillis()[sig + 5]),
                    1, 100, 100 + pnl, 0, pnl, Trade.ExitReason.SIGNAL));
        }
        double[] eq = new double[c.size()];
        java.util.Arrays.fill(eq, 1000);
        return new BacktestResult(1000, 1000, eq, list, 0, c.size(), 0, eq, new double[c.size()]);
    }

    private static boolean up(CandleSeries c, int bar) {
        return c.close()[bar] > c.open()[bar];
    }

    @Test
    @DisplayName("every condition and its opposite is valid TSL; ON 4h is left out on 4h candles")
    void panelCompiles() {
        CompiledStrategy base = compile(SOURCE);
        for (ConfluenceDiscovery.Condition c : ConfluenceDiscovery.panel("1h", true)) {
            if (c.tsl() != null) {
                assertTrue(ConfluenceDiscovery.probe(c.tsl(), base).isPresent(), c.tsl());
                assertTrue(ConfluenceDiscovery.probe(c.tslNot(), base).isPresent(), c.tslNot());
            }
        }
        assertFalse(ConfluenceDiscovery.panel("4h", true).stream()
                .anyMatch(c -> c.id().equals("above_4h_ema50")));
        assertFalse(ConfluenceDiscovery.panel("1d", false).stream()
                .anyMatch(c -> c.id().startsWith("session_")));
    }

    @Test
    @DisplayName("refuses to look for patterns in fewer than 30 trades")
    void tooFewTrades() {
        CandleSeries c = walk(3000, 5);
        ConfluenceDiscovery.Report r = ConfluenceDiscovery.analyze(null, compile(SOURCE), c,
                ExchangeRules.none(), trades(c, 29, b -> up(c, b)));
        assertEquals(ConfluenceDiscovery.Status.TOO_FEW_TRADES, r.status());
        assertEquals(List.of(), r.ideas());
    }

    @Test
    @DisplayName("a real edge is found, beats luck, and holds up on the later trades")
    void plantedEdgeHoldsUp() {
        CandleSeries c = walk(4000, 5);
        ConfluenceDiscovery.Report r = ConfluenceDiscovery.analyze(null, compile(SOURCE), c,
                ExchangeRules.none(), trades(c, 90, b -> up(c, b)));
        assertEquals(ConfluenceDiscovery.Status.OK, r.status());
        assertEquals(60, r.foundTrades());
        assertEquals(30, r.laterTrades());
        ConfluenceDiscovery.Idea top = r.ideas().get(0);
        assertEquals("up_candle", top.id());
        assertEquals("AND CLOSE > OPEN", top.snippet());
        assertTrue(top.beatsLuck());
        assertEquals(100.0, top.winnersWithPct(), 1e-9);
        assertEquals(0.0, top.losersWithPct(), 1e-9);
        assertEquals(100.0, top.later().winRateKept(), 1e-9);
        assertEquals(ConfluenceDiscovery.Verdict.HELD_UP, top.verdict());
        assertNull(top.rerun()); // no source given
    }

    @Test
    @DisplayName("a pattern that only existed in the earlier trades is reported as not holding up")
    void regimeChangeIsCaught() {
        CandleSeries c = walk(4000, 5);
        // Earlier trades win on up candles; from the 61st trade on, on down candles.
        int switchBar = 300 + 60 * 30;
        ConfluenceDiscovery.Report r = ConfluenceDiscovery.analyze(null, compile(SOURCE), c,
                ExchangeRules.none(), trades(c, 90, b -> b < switchBar ? up(c, b) : !up(c, b)));
        ConfluenceDiscovery.Idea top = r.ideas().get(0);
        assertEquals("up_candle", top.id());
        assertTrue(top.beatsLuck());
        assertEquals(ConfluenceDiscovery.Verdict.DID_NOT_HOLD, top.verdict());
    }

    @Test
    @DisplayName("noise: on 40 sets of coin-flip trades, almost nothing beats luck, and nothing holds up")
    void noiseIsNotMistakenForAnEdge() {
        CandleSeries c = walk(4000, 5);
        int beat = 0;
        int held = 0;
        for (int seed = 1; seed <= 40; seed++) {
            Random coin = new Random(seed);
            boolean[] outcome = new boolean[4000];
            for (int i = 0; i < outcome.length; i++) {
                outcome[i] = coin.nextBoolean();
            }
            ConfluenceDiscovery.Report r = ConfluenceDiscovery.analyze(null, compile(SOURCE), c,
                    ExchangeRules.none(), trades(c, 90, b -> outcome[b]));
            if (r.ideas().stream().anyMatch(ConfluenceDiscovery.Idea::beatsLuck)) {
                beat++;
            }
            if (r.ideas().stream().anyMatch(i -> i.verdict() == ConfluenceDiscovery.Verdict.HELD_UP)) {
                held++;
            }
        }
        // The luck bar is set so that luck clears it about 1 time in 20.
        assertTrue(beat <= 5, "beat luck in " + beat + " of 40 noise runs");
        assertTrue(held <= 2, "held up in " + held + " of 40 noise runs");
    }

    @Test
    @DisplayName("the backtest is re-run with the filter added to the entry, through the real engine")
    void rerunAddsTheFilter() {
        CandleSeries c = walk(4000, 9);
        CompiledStrategy s = compile(SOURCE);
        var injector = ConfluenceDiscovery.Injector.of(SOURCE);
        assertNull(injector.problem());
        CompiledStrategy filtered = injector.with("RSI(14) < 40").orElseThrow();
        BacktestResult after = new Backtester().run(filtered, c, ExchangeRules.none());
        IntUnaryOperator rsiBelow40 = ConfluenceDiscovery.testFor(
                ConfluenceDiscovery.panel("1h", true).stream()
                        .filter(x -> x.id().equals("rsi_below_40")).findFirst().orElseThrow(),
                s, c).orElseThrow();
        assertFalse(after.trades().isEmpty());
        for (Trade t : after.trades()) {
            assertEquals(1, rsiBelow40.applyAsInt(t.entryBar() - 1), "entry at " + t.entryBar());
            assertTrue(up(c, t.entryBar() - 1)); // the original condition still applies
        }

        BacktestResult original = new Backtester().run(s, c, ExchangeRules.none());
        ConfluenceDiscovery.Report r = ConfluenceDiscovery.analyze(SOURCE, s, c,
                ExchangeRules.none(), original);
        assertEquals(ConfluenceDiscovery.Status.OK, r.status());
        assertNull(r.rerunNote());
        for (ConfluenceDiscovery.Idea idea : r.ideas()) {
            if (idea.snippet() != null) {
                assertNotNull(idea.rerun(), idea.label());
                // The whole-backtest "before" is the report's return, to the cent.
                assertEquals(original.totalReturnPct(), idea.rerun().fullReturnBefore(), 1e-9);
                assertEquals(original.trades().size(), idea.rerun().fullTradesBefore());
            }
        }
    }

    @Test
    @DisplayName("the Add button's code: the filter goes on every buying IF, nothing else changes")
    void editAddsTheFilterToTheCode() {
        String source = """
                strategy "E" {
                    symbol = BTCUSDT
                    timeframe = 1h
                    capital = 1000
                    // buy dips or breakouts
                    rule dip { IF RSI(14) < 30 OR CLOSE > HIGH[1] THEN BUY ALL }
                    rule more {
                        IF (CLOSE > OPEN) AND VOLUME > 100   // strong candle
                        THEN BUY ALL
                    }
                    rule take { IF RSI(14) > 70 THEN SELL ALL }
                }
                """;
        String edited = ConfluenceDiscovery.Injector.of(source).edit("CLOSE > EMA(CLOSE, 50)")
                .orElseThrow();
        String expected = source
                .replace("IF RSI(14) < 30 OR CLOSE > HIGH[1] THEN",
                        "IF (RSI(14) < 30 OR CLOSE > HIGH[1]) AND CLOSE > EMA(CLOSE, 50) THEN")
                .replace("IF (CLOSE > OPEN) AND VOLUME > 100   // strong candle",
                        "IF (CLOSE > OPEN) AND VOLUME > 100 AND CLOSE > EMA(CLOSE, 50)   // strong candle");
        assertEquals(expected, edited);
    }

    @Test
    @DisplayName("the edited code trades exactly like the filter added to the parsed strategy")
    void editTradesLikeTheInjectedTree() {
        String[] sources = {
                SOURCE,
                SOURCE.replace("IF CLOSE > OPEN THEN BUY ALL",
                        "IF (CLOSE > OPEN OR RSI(14) < 35) THEN BUY ALL"),
                SOURCE.replace("IF CLOSE > OPEN THEN BUY ALL",
                        "IF CLOSE > OPEN AND (RSI(14) < 60 OR VOLUME > 500) THEN BUY ALL"),
                SOURCE.replace("IF CLOSE > OPEN THEN BUY ALL",
                        "IF NOT CLOSE < OPEN OR CLOSE > HIGH[1] THEN BUY ALL"),
        };
        CandleSeries c = walk(3000, 3);
        for (String source : sources) {
            var injector = ConfluenceDiscovery.Injector.of(source);
            for (ConfluenceDiscovery.Condition cond : ConfluenceDiscovery.panel("1h", true)) {
                for (String filter : new String[] {cond.tsl(), cond.tslNot()}) {
                    if (filter == null) {
                        continue;
                    }
                    String edited = injector.edit(filter).orElseThrow();
                    BacktestResult viaText = new Backtester().run(compile(edited), c,
                            ExchangeRules.none());
                    BacktestResult viaTree = new Backtester().run(
                            injector.with(filter).orElseThrow(), c, ExchangeRules.none());
                    assertEquals(viaTree.trades(), viaText.trades(), filter + " on " + edited);
                }
            }
        }
    }

    @Test
    @DisplayName("each idea with code carries the strategy with it added, and the re-run ran that code")
    void ideasCarryTheirCode() {
        CandleSeries c = walk(4000, 9);
        CompiledStrategy s = compile(SOURCE);
        BacktestResult original = new Backtester().run(s, c, ExchangeRules.none());
        ConfluenceDiscovery.Report r = ConfluenceDiscovery.analyze(SOURCE, s, c,
                ExchangeRules.none(), original);
        assertFalse(r.ideas().isEmpty());
        for (ConfluenceDiscovery.Idea idea : r.ideas()) {
            if (idea.snippet() == null) {
                assertNull(idea.appliedSource());
                continue;
            }
            String applied = idea.appliedSource();
            assertNotNull(applied, idea.label());
            assertTrue(applied.contains("IF CLOSE > OPEN " + idea.snippet() + " THEN BUY ALL"),
                    applied);
            BacktestResult after = new Backtester().run(compile(applied), c, ExchangeRules.none());
            assertEquals(after.totalReturnPct(), idea.rerun().fullReturnAfter(), 1e-9);
            assertEquals(after.trades().size(), idea.rerun().fullTradesAfter());
        }
    }

    @Test
    @DisplayName("a strategy that buys in ELSE is not re-run, and says why")
    void elseBuyIsNotRerun() {
        String source = SOURCE.replace("IF CLOSE < OPEN THEN SELL ALL",
                "IF CLOSE < OPEN THEN SELL ALL ELSE BUY ALL");
        assertNotNull(ConfluenceDiscovery.Injector.of(source).problem());
    }
}
