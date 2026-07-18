package com.tsb.execution;

import com.tsb.compiler.Analyzer;
import com.tsb.compiler.CompiledStrategy;
import com.tsb.compiler.Expr;
import com.tsb.compiler.Lexer;
import com.tsb.compiler.Parser;
import com.tsb.marketdata.CandleSeries;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives the per-bar evaluator through REAL compiled strategies (full
 * lexer -> parser -> analyzer pipeline) over hand-built candles, so every
 * assertion also exercises the compiler/engine contract end to end.
 */
@DisplayName("Interpreter")
class InterpreterTest {

    /** closes: 4, 6, 5, 8, 2, 9 — chosen for crossings and reversals. */
    private static CandleSeries series() {
        double[] close = {4, 6, 5, 8, 2, 9};
        int n = close.length;
        long[] t = new long[n];
        double[] o = new double[n];
        double[] h = new double[n];
        double[] l = new double[n];
        double[] v = new double[n];
        for (int i = 0; i < n; i++) {
            t[i] = i * 3_600_000L;
            o[i] = close[i] - 0.5;
            h[i] = Math.max(o[i], close[i]) + 1;
            l[i] = Math.min(o[i], close[i]) - 1;
            v[i] = 100;
        }
        return new CandleSeries(t, o, h, l, close, v);
    }

    /** Compiles a strategy whose single rule condition we evaluate. */
    private static Fixture fixtureFor(String lets, String condition) {
        String source = """
                strategy "T" {
                    symbol = BTCUSDT
                    timeframe = 1h
                    capital = 10000
                    %s
                    rule r { IF %s THEN BUY ALL }
                }
                """.formatted(lets, condition);
        Lexer.LexResult lexed = new Lexer(source).scan();
        Parser.ParseResult parsed = new Parser(lexed.tokens()).parse();
        Analyzer.AnalysisResult analyzed =
                new Analyzer(parsed.strategy().orElseThrow()).analyze();
        assertEquals(List.of(), analyzed.diagnostics(),
                "fixture must compile cleanly: " + condition);
        CompiledStrategy s = analyzed.strategy().orElseThrow();

        CandleSeries candles = series();
        Map<String, double[]> bank = IndicatorBank.compute(s.indicators(), candles);
        Interpreter interp = new Interpreter(s.lets(), candles, bank);
        Expr cond = s.rules().get(0).body().get(0).condition();
        return new Fixture(interp, cond);
    }

    private record Fixture(Interpreter interp, Expr condition) {
        boolean at(int i) {
            return interp.bool(condition, i);
        }
    }

    @Nested
    @DisplayName("numeric evaluation")
    class Numerics {

        @Test
        @DisplayName("prices, arithmetic, and comparison compose per bar")
        void arithmetic() {
            // closes: 4,6,5,8,2,9 ; CLOSE * 2 > 10 -> false,true,false,true,false,true
            Fixture f = fixtureFor("", "CLOSE * 2 > 10");
            assertFalse(f.at(0));
            assertTrue(f.at(1));
            assertFalse(f.at(2));
            assertTrue(f.at(3));
        }

        @Test
        @DisplayName("lookback reads exactly k bars back")
        void lookback() {
            // CLOSE > CLOSE[1]: rises at 1 (6>4), 3 (8>5), 5 (9>2)
            Fixture f = fixtureFor("", "CLOSE > CLOSE[1]");
            assertTrue(f.at(1));
            assertFalse(f.at(2));
            assertTrue(f.at(3));
            assertFalse(f.at(4));
            assertTrue(f.at(5));
        }

        @Test
        @DisplayName("MAX / ABS functions evaluate inline")
        void functions() {
            // MAX(CLOSE, 5) >= 5 always; ABS(CLOSE - 6) < 1 only where close in (5,7)
            Fixture always = fixtureFor("", "MAX(CLOSE, 5) >= 5");
            assertTrue(always.at(0));
            Fixture near6 = fixtureFor("", "ABS(CLOSE - 6) < 1");
            assertFalse(near6.at(0)); // |4-6| = 2
            assertTrue(near6.at(1));  // |6-6| = 0
        }
    }

    @Nested
    @DisplayName("safety semantics")
    class Safety {

        @Test
        @DisplayName("lookback past the start of data is NaN -> condition false, no crash")
        void lookbackBeforeStart() {
            Fixture f = fixtureFor("", "CLOSE > CLOSE[1]");
            assertFalse(f.at(0), "bar 0 has no previous bar; must be inert");
        }

        @Test
        @DisplayName("indicator warm-up NaN makes ANY comparison false — even ones 'always true' on real numbers")
        void warmupIsInert() {
            // RSI(3) < 200 would be true for any real RSI value (0..100);
            // during warm-up it must still be FALSE, because NaN answers
            // every question with no.
            Fixture f = fixtureFor("", "RSI(3) < 200");
            assertFalse(f.at(0));
            assertFalse(f.at(2));  // rsi(3) first valid at index 3
            assertTrue(f.at(3));
        }

        @Test
        @DisplayName("NEQ with NaN is false (Java's NaN != x is true; we override)")
        void neqWithNaN() {
            Fixture f = fixtureFor("", "RSI(3) != 999");
            assertFalse(f.at(0), "unknown data must not satisfy !=");
            assertTrue(f.at(3));
        }
    }

    @Nested
    @DisplayName("lets and crossings")
    class LetsAndCrossings {

        @Test
        @DisplayName("lets evaluate through VarRef with per-bar caching")
        void letReuse() {
            Fixture f = fixtureFor("let doubled = CLOSE * 2",
                    "doubled > 10 AND doubled < 17");
            assertFalse(f.at(0)); // 8
            assertTrue(f.at(1));  // 12
            assertTrue(f.at(3));  // 16
            assertFalse(f.at(5)); // 18
        }

        @Test
        @DisplayName("boolean lets work: a named condition")
        void booleanLet() {
            Fixture f = fixtureFor("let rising = CLOSE > CLOSE[1]",
                    "rising AND CLOSE > 7");
            assertFalse(f.at(1)); // rising but close 6
            assertTrue(f.at(3));  // rising and close 8
        }

        @Test
        @DisplayName("CROSSOVER fires only on the crossing bar, not while above")
        void crossoverSemantics() {
            // closes vs constant 5: 4, 6, 5, 8, 2, 9
            // crosses above at 1 (4<=5 -> 6>5); at 3 (5<=5 -> 8>5); at 5 (2<=5 -> 9>5)
            // NOT at 0 (no previous bar)
            Fixture f = fixtureFor("", "CROSSOVER(CLOSE, 5)");
            assertFalse(f.at(0));
            assertTrue(f.at(1));
            assertFalse(f.at(2));
            assertTrue(f.at(3));
            assertFalse(f.at(4));
            assertTrue(f.at(5));
        }

        @Test
        @DisplayName("CROSSUNDER is the mirror")
        void crossunder() {
            // crosses below 5 at bar 4 (8>=5 -> 2<5) and bar 2 (6>=5 -> 5<5? no, 5 is not < 5)
            Fixture f = fixtureFor("", "CROSSUNDER(CLOSE, 5)");
            assertFalse(f.at(2)); // 5 is not strictly below
            assertTrue(f.at(4));
        }
    }

    @Test
    @DisplayName("indicators come from the bank: SMA condition over the pipeline")
    void indicatorViaBank() {
        // SMA(CLOSE,2): _, 5, 5.5, 6.5, 5, 5.5 ; CLOSE > SMA at 1(6>5), 3(8>6.5), 5(9>5.5)
        Fixture f = fixtureFor("", "CLOSE > SMA(CLOSE, 2)");
        assertFalse(f.at(0)); // SMA NaN
        assertTrue(f.at(1));
        assertFalse(f.at(2));
        assertTrue(f.at(3));
        assertFalse(f.at(4));
        assertTrue(f.at(5));
    }
}