package com.tsb.debugger;

import com.tsb.compiler.Analyzer;
import com.tsb.compiler.CompiledStrategy;
import com.tsb.compiler.Lexer;
import com.tsb.compiler.Parser;
import com.tsb.compiler.StrategyAst;
import com.tsb.execution.BacktestResult;
import com.tsb.execution.Backtester;
import com.tsb.execution.ExchangeRules;
import com.tsb.execution.IndicatorBank;
import com.tsb.execution.Interpreter;
import com.tsb.marketdata.CandleSeries;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The debugger, pinned against a scenario small enough to follow on paper.
 *
 * <pre>
 *   bar    0   1   2     3     4     5     6   7 (last)
 *   close 10  11  12    13    12    11    14  13
 *   open = close - 0.5
 *
 *   entry: CLOSE > 11.5 AND CLOSE < 14  -> BUY ALL
 *   exit:  CLOSE < 11.5                 -> SELL ALL
 *
 *   bar 0,1  exit true while flat          -> SELL ignored
 *   bar 2    entry true                    -> BUY fills at bar 3 open (12.5)
 *   bar 3,4  entry true while long         -> BUY ignored
 *   bar 5    exit true                     -> SELL fills at bar 6 open (13.5)
 *   bar 6    entry false: CLOSE < 14 by 0
 *   bar 7    entry true on the last bar    -> nothing to fill at
 * </pre>
 */
@DisplayName("DecisionExplainer")
class DecisionExplainerTest {

    private static final double[] CLOSES = {10, 11, 12, 13, 12, 11, 14, 13};

    private static final String RULES = """
            rule entry { IF CLOSE > 11.5 AND CLOSE < 14 THEN BUY ALL }
            rule exit { IF CLOSE < 11.5 THEN SELL ALL }
            """;

    static CompiledStrategy compile(String body) {
        String source = """
                strategy "T" {
                    symbol = BTCUSDT
                    timeframe = 1h
                    capital = 1000
                    %s
                }
                """.formatted(body);
        Lexer.LexResult lexed = new Lexer(source).scan();
        Parser.ParseResult parsed = new Parser(lexed.tokens()).parse();
        Analyzer.AnalysisResult analyzed =
                new Analyzer(parsed.strategy().orElseThrow()).analyze();
        assertEquals(List.of(), analyzed.diagnostics(),
                "fixture must compile: " + analyzed.diagnostics());
        return analyzed.strategy().orElseThrow();
    }

    static CandleSeries series(double[] close) {
        int n = close.length;
        long[] t = new long[n];
        double[] o = new double[n];
        double[] h = new double[n];
        double[] l = new double[n];
        double[] v = new double[n];
        for (int i = 0; i < n; i++) {
            t[i] = i * 3_600_000L;
            o[i] = close[i] - 0.5;
            h[i] = Math.max(o[i], close[i]);
            l[i] = Math.min(o[i], close[i]);
            v[i] = 100;
        }
        return new CandleSeries(t, o, h, l, close, v);
    }

    private static DecisionExplainer scenario() {
        return new DecisionExplainer(compile(RULES), series(CLOSES), ExchangeRules.none());
    }

    private static Explanation.Statement entry(Explanation.Bar b) {
        return b.statements().get(0);
    }

    private static Explanation.Statement exit(Explanation.Bar b) {
        return b.statements().get(1);
    }

    @Nested
    @DisplayName("never disagrees with the engine")
    class Agreement {

        @Test
        @DisplayName("every node's verdict is the interpreter's, at every bar")
        void verdictsAreTheInterpreters() {
            CompiledStrategy s = compile("""
                    let m = SMA(CLOSE, 2)
                    let up = CLOSE > m AND NOT (CLOSE < OPEN)
                    rule a { IF up OR CROSSUNDER(CLOSE, 12) THEN BUY ALL }
                    rule b { IF CLOSE <= 11 OR CLOSE == 14 THEN SELL ALL }
                    """);
            CandleSeries c = series(CLOSES);
            DecisionExplainer ex = new DecisionExplainer(s, c, ExchangeRules.none());
            Interpreter interp = new Interpreter(s.lets(), c,
                    IndicatorBank.compute(s.indicators(), c));
            for (int i = 0; i < c.size(); i++) {
                Explanation.Bar bar = ex.explain(i);
                int k = 0;
                for (StrategyAst.RuleDecl rule : s.rules()) {
                    for (StrategyAst.IfStmt stmt : rule.body()) {
                        ConditionNode root = bar.statements().get(k++).condition();
                        assertEquals(interp.bool(stmt.condition(), i), root.passed(),
                                "bar " + i + " rule " + rule.name());
                        assertEquals(root.passed(), DecisionExplainer.structural(root, null),
                                "tree must agree with its own children at bar " + i);
                    }
                }
            }
        }

        @Test
        @DisplayName("observing a run does not change it")
        void observerDoesNotChangeTheRun() {
            DecisionExplainer ex = scenario();
            BacktestResult plain = new Backtester().run(compile(RULES),
                    series(CLOSES), ExchangeRules.none());
            assertEquals(plain.trades(), ex.result().trades());
            assertEquals(plain.finalEquity(), ex.result().finalEquity());
        }
    }

    @Nested
    @DisplayName("the four reasons a signal is not a trade")
    class Outcomes {

        @Test
        @DisplayName("SELL while flat is reported as ignored, not as a trade")
        void sellWhileFlat() {
            Explanation.Bar b = scenario().explain(1);
            assertEquals(Boolean.FALSE, b.inPosition());
            assertEquals(Explanation.Branch.THEN, exit(b).taken());
            assertEquals(Explanation.Outcome.IGNORED_NOTHING_TO_SELL, exit(b).outcome());
        }

        @Test
        @DisplayName("a BUY signal fills at the NEXT bar's open")
        void buyFillsNextOpen() {
            Explanation.Statement e = entry(scenario().explain(2));
            assertEquals(Explanation.Outcome.FILLED, e.outcome());
            assertEquals(12.5, e.fillPrice(), 1e-12); // bar 3 open, not bar 2 close
            assertEquals(3 * 3_600_000L, e.fillTimeMillis());
        }

        @Test
        @DisplayName("BUY while long is ignored: one position at a time")
        void buyWhileLong() {
            Explanation.Bar b = scenario().explain(3);
            assertEquals(Boolean.TRUE, b.inPosition());
            assertEquals(Explanation.Outcome.IGNORED_ALREADY_LONG, entry(b).outcome());
            assertTrue(entry(b).sentence().contains("already open"));
        }

        @Test
        @DisplayName("an order too small for the exchange is reported as rejected")
        void tooSmall() {
            // 1000 capital, min order value 1,000,000: nothing is placeable.
            DecisionExplainer ex = new DecisionExplainer(compile(RULES),
                    series(CLOSES), new ExchangeRules(1e-9, 1_000_000));
            assertEquals(Explanation.Outcome.REJECTED_TOO_SMALL,
                    entry(ex.explain(2)).outcome());
            assertEquals(List.of(), ex.result().trades());
        }

        @Test
        @DisplayName("a signal on the last bar has no next open to fill at")
        void lastBar() {
            Explanation.Bar b = scenario().explain(7);
            assertTrue(b.lastBar());
            assertEquals(Explanation.Outcome.NOT_FILLED_LAST_BAR, entry(b).outcome());
        }

        @Test
        @DisplayName("warm-up bars are marked as not checked")
        void warmup() {
            CompiledStrategy s = compile("rule a { IF CLOSE > SMA(CLOSE, 3) THEN BUY ALL }");
            DecisionExplainer ex = new DecisionExplainer(s, series(CLOSES),
                    ExchangeRules.none());
            Explanation.Bar b = ex.explain(1);
            assertTrue(b.warmup());
            assertNull(b.inPosition());
            assertEquals(Explanation.Outcome.WARMUP, entry(b).outcome());
            assertTrue(entry(b).condition().unknown(), "SMA(3) has no value at bar 1");
            assertFalse(ex.explain(3).warmup());
        }
    }

    @Nested
    @DisplayName("numbers and the closest change")
    class Numbers {

        @Test
        @DisplayName("a failed AND names the part that would have been enough")
        void closestChangeOfAnAnd() {
            Explanation.Statement e = entry(scenario().explain(0));
            assertEquals(Explanation.Outcome.NO_ACTION, e.outcome());
            ConditionNode and = e.condition();
            assertEquals(ConditionNode.Kind.AND, and.kind());
            assertEquals(2, and.children().size());

            ConditionNode first = and.children().get(0);
            assertFalse(first.passed());
            assertEquals(10.0, first.left());
            assertEquals(11.5, first.right());
            assertEquals(1.5, first.distance(), 1e-12);

            Explanation.ClosestChange closest = e.closestChange();
            assertNotNull(closest);
            assertEquals("CLOSE > 11.5", closest.text());
            assertEquals(1.5 / 11.5, closest.relativeDistance(), 1e-12);
        }

        @Test
        @DisplayName("an exact tie is a distance of zero")
        void tie() {
            Explanation.Statement e = entry(scenario().explain(6)); // CLOSE = 14
            assertEquals("CLOSE < 14", e.closestChange().text());
            assertEquals(0.0, e.closestChange().distance(), 1e-12);
        }

        @Test
        @DisplayName("two failed parts of an AND: no single change is enough")
        void noSingleChange() {
            CompiledStrategy s = compile("rule a { IF CLOSE > 100 AND CLOSE < 5 THEN BUY ALL }");
            Explanation.Statement e = entry(new DecisionExplainer(s, series(CLOSES),
                    ExchangeRules.none()).explain(2));
            assertNull(e.closestChange());
            assertTrue(e.sentence().contains("No single change"));
        }

        @Test
        @DisplayName("a crossover that already happened cannot be flipped by moving today's value")
        void crossoverAlreadyAbove() {
            CompiledStrategy s = compile("rule a { IF CROSSOVER(CLOSE, 11.5) THEN BUY ALL }");
            DecisionExplainer ex = new DecisionExplainer(s, series(CLOSES), ExchangeRules.none());
            assertTrue(entry(ex.explain(2)).condition().passed()); // 11 -> 12 crosses 11.5
            ConditionNode later = entry(ex.explain(3)).condition(); // 12 -> 13, already above
            assertFalse(later.passed());
            assertNull(later.distance());
            assertTrue(later.note().contains("already above"));
        }

        @Test
        @DisplayName("named conditions are expanded into their parts")
        void letsExpand() {
            CompiledStrategy s = compile("""
                    let inRange = CLOSE > 11.5 AND CLOSE < 14
                    rule a { IF inRange THEN BUY ALL }
                    """);
            ConditionNode root = entry(new DecisionExplainer(s, series(CLOSES),
                    ExchangeRules.none()).explain(0)).condition();
            assertEquals(ConditionNode.Kind.LET, root.kind());
            assertEquals(ConditionNode.Kind.AND, root.children().get(0).kind());
        }
    }

    @Test
    @DisplayName("barAt finds the bar containing a time")
    void barAt() {
        DecisionExplainer ex = scenario();
        assertEquals(Optional.of(2), ex.barAt(2 * 3_600_000L));
        assertEquals(Optional.of(2), ex.barAt(2 * 3_600_000L + 1_000));
        assertEquals(Optional.of(7), ex.barAt(99 * 3_600_000L));
        assertEquals(Optional.empty(), ex.barAt(-1));
    }
}
