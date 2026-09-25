package com.tsb.signals;

import com.tsb.compiler.Analyzer;
import com.tsb.compiler.CompiledStrategy;
import com.tsb.compiler.Lexer;
import com.tsb.compiler.Parser;
import com.tsb.debugger.DecisionExplainer;
import com.tsb.debugger.Explanation;
import com.tsb.execution.ExchangeRules;
import com.tsb.marketdata.CandleSeries;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Same paper scenario as DecisionExplainerTest:
 * closes 10, 11, 12, 13, 12, 11, 14, 13 (open = close - 0.5).
 */
@DisplayName("SignalStatistics")
class SignalStatisticsTest {

    private static SignalStatistics.Report report(double nearMiss) {
        String source = """
                strategy "T" {
                    symbol = BTCUSDT
                    timeframe = 1h
                    capital = 1000
                    rule entry { IF CLOSE > 11.5 AND CLOSE < 14 THEN BUY ALL }
                    rule exit { IF CLOSE < 11.5 THEN SELL ALL }
                }
                """;
        Lexer.LexResult lexed = new Lexer(source).scan();
        Parser.ParseResult parsed = new Parser(lexed.tokens()).parse();
        CompiledStrategy s = new Analyzer(parsed.strategy().orElseThrow())
                .analyze().strategy().orElseThrow();

        double[] close = {10, 11, 12, 13, 12, 11, 14, 13};
        int n = close.length;
        long[] t = new long[n];
        double[] o = new double[n];
        double[] v = new double[n];
        for (int i = 0; i < n; i++) {
            t[i] = i * 3_600_000L;
            o[i] = close[i] - 0.5;
            v[i] = 100;
        }
        CandleSeries c = new CandleSeries(t, o, close, o, close, v);
        return SignalStatistics.compute(
                new DecisionExplainer(s, c, ExchangeRules.none()), nearMiss);
    }

    @Test
    @DisplayName("counts true bars and what became of each signal")
    void countsAndOutcomes() {
        SignalStatistics.Report r = report(0.02);
        assertEquals(8, r.barsEvaluated());

        SignalStatistics.StatementStats entry = r.statements().get(0);
        assertEquals(4, entry.trueBars()); // bars 2, 3, 4, 7
        assertEquals(50.0, entry.truePct(), 1e-12);
        assertEquals(1, entry.outcomes().get(Explanation.Outcome.FILLED));
        assertEquals(2, entry.outcomes().get(Explanation.Outcome.IGNORED_ALREADY_LONG));
        assertEquals(1, entry.outcomes().get(Explanation.Outcome.NOT_FILLED_LAST_BAR));

        SignalStatistics.StatementStats exit = r.statements().get(1);
        assertEquals(3, exit.trueBars()); // bars 0, 1, 5
        assertEquals(2, exit.outcomes().get(Explanation.Outcome.IGNORED_NOTHING_TO_SELL));
        assertEquals(1, exit.outcomes().get(Explanation.Outcome.FILLED));
    }

    @Test
    @DisplayName("finds the part that most often stands alone in the way")
    void bottleneck() {
        SignalStatistics.StatementStats entry = report(0.02).statements().get(0);
        List<SignalStatistics.LeafStats> parts = entry.parts();
        assertEquals("CLOSE > 11.5", parts.get(0).text());
        assertEquals(3, parts.get(0).soleBlockerBars()); // bars 0, 1, 5
        assertEquals(1, parts.get(1).soleBlockerBars()); // bar 6
        assertEquals("CLOSE > 11.5", entry.bottleneck());
    }

    @Test
    @DisplayName("near misses depend on the threshold")
    void nearMisses() {
        // Relative distances on the false bars: 13.0%, 4.35%, 4.35%, 0%.
        assertEquals(1, report(0.02).statements().get(0).nearMisses());
        assertEquals(3, report(0.05).statements().get(0).nearMisses());
    }

    @Test
    @DisplayName("hour-of-day counts add up to the totals")
    void hours() {
        SignalStatistics.StatementStats entry = report(0.02).statements().get(0);
        assertEquals(entry.trueBars(), Arrays.stream(entry.trueByHour()).sum());
        assertEquals(8, Arrays.stream(entry.barsByHour()).sum());
        assertTrue(report(0.02).intraday());
    }
}
