package com.tsb.signals;

import com.tsb.compiler.Analyzer;
import com.tsb.compiler.CompiledStrategy;
import com.tsb.compiler.Lexer;
import com.tsb.compiler.Parser;
import com.tsb.execution.Trade;
import com.tsb.marketdata.CandleSeries;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Confluence")
class ConfluenceTest {

    private static CompiledStrategy strategy() {
        String source = """
                strategy "T" {
                    symbol = BTCUSDT
                    timeframe = 1h
                    capital = 1000
                    rule a { IF CLOSE > OPEN THEN BUY ALL }
                }
                """;
        Lexer.LexResult lexed = new Lexer(source).scan();
        Parser.ParseResult parsed = new Parser(lexed.tokens()).parse();
        return new Analyzer(parsed.strategy().orElseThrow()).analyze()
                .strategy().orElseThrow();
    }

    /** A seeded random walk, bars {@code stepMillis} apart. */
    private static CandleSeries walk(int n, long stepMillis) {
        Random r = new Random(5);
        long[] t = new long[n];
        double[] o = new double[n];
        double[] h = new double[n];
        double[] l = new double[n];
        double[] c = new double[n];
        double[] v = new double[n];
        double px = 100;
        for (int i = 0; i < n; i++) {
            t[i] = i * stepMillis;
            o[i] = px;
            px *= Math.exp(r.nextGaussian() * 0.02);
            c[i] = px;
            h[i] = Math.max(o[i], c[i]) * 1.002;
            l[i] = Math.min(o[i], c[i]) * 0.998;
            v[i] = 100 + r.nextInt(900);
        }
        return new CandleSeries(t, o, h, l, c, v);
    }

    /** One trade per 30 bars; it wins exactly when the signal bar closed up. */
    private static List<Trade> plantedTrades(CandleSeries c, int count) {
        List<Trade> trades = new ArrayList<>();
        for (int sig = 300; trades.size() < count; sig += 30) {
            double pnl = c.close()[sig] > c.open()[sig] ? 10 : -10;
            trades.add(new Trade(sig + 1, sig + 5,
                    Instant.ofEpochMilli(c.openTimeMillis()[sig + 1]),
                    Instant.ofEpochMilli(c.openTimeMillis()[sig + 5]),
                    1, 100, 100 + pnl, 0, pnl, Trade.ExitReason.SIGNAL));
        }
        return trades;
    }

    @Test
    @DisplayName("every market condition in the panel is valid TSL")
    void panelCompiles() {
        CompiledStrategy base = strategy();
        for (Confluence.Feature f : Confluence.PANEL) {
            if (f.tsl() != null) {
                Confluence.compileProbe(f.tsl(), base); // throws if not
            }
        }
    }

    @Test
    @DisplayName("refuses to look for patterns in fewer than 30 trades")
    void tooFewTrades() {
        CandleSeries c = walk(3000, 3_600_000L);
        Confluence.Report r = Confluence.analyze(strategy(), c, plantedTrades(c, 29));
        assertEquals(Confluence.Status.TOO_FEW_TRADES, r.status());
        assertEquals(List.of(), r.findings());
    }

    @Test
    @DisplayName("finds a planted edge and suggests the matching TSL")
    void plantedEdge() {
        CandleSeries c = walk(3000, 3_600_000L);
        Confluence.Report r = Confluence.analyze(strategy(), c, plantedTrades(c, 80));
        assertEquals(Confluence.Status.OK, r.status());
        Confluence.Finding top = r.findings().get(0);
        assertEquals("up_bar", top.feature().id());
        assertTrue(top.significant());
        assertEquals("AND CLOSE > OPEN", top.suggestion());
        // Bonferroni: the bar is 0.05 split across every test actually run.
        assertEquals(0.05 / r.testsRun(), r.threshold(), 1e-12);
        long tested = r.findings().stream().filter(Confluence.Finding::tested).count();
        assertEquals(r.testsRun(), tested);
    }

    @Test
    @DisplayName("daily bars skip the time-of-day sessions")
    void dailySkipsSessions() {
        CandleSeries c = walk(3000, 86_400_000L);
        Confluence.Report r = Confluence.analyze(strategy(), c, plantedTrades(c, 80));
        assertFalse(r.findings().stream()
                .anyMatch(f -> f.feature().id().startsWith("session_")));
    }
}
