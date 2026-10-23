package com.tsb.execution;

import com.tsb.compiler.Analyzer;
import com.tsb.compiler.CompiledStrategy;
import com.tsb.compiler.Lexer;
import com.tsb.compiler.Parser;
import com.tsb.compiler.StrategyAst;
import com.tsb.marketdata.CandleSeries;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The engine bugs found in the October review, one test each, so they stay
 * fixed: missing data under NOT, buying more than the cash, unsellable
 * leftovers, swapped MACD periods, Hull's window, and lets read through
 * lookbacks.
 */
@DisplayName("Engine fixes")
class EngineFixesTest {

    static CompiledStrategy compile(String config, String body) {
        String source = """
                strategy "T" {
                    symbol = BTCUSDT
                    timeframe = 1h
                    capital = 1000
                    %s
                    %s
                }
                """.formatted(config.contains("fee") ? config : "fee = 0%\n" + config, body);
        Lexer.LexResult lexed = new Lexer(source).scan();
        Parser.ParseResult parsed = new Parser(lexed.tokens()).parse();
        Analyzer.AnalysisResult analyzed = new Analyzer(parsed.strategy().orElseThrow()).analyze();
        assertTrue(analyzed.diagnostics().stream().noneMatch(d -> d.isError()),
                () -> "fixture must compile: " + analyzed.diagnostics());
        return analyzed.strategy().orElseThrow();
    }

    /** Flat-topped candles: open[i] given, close[i] given, wicks hug them. */
    static CandleSeries series(double[] open, double[] close) {
        int n = open.length;
        long[] t = new long[n];
        double[] h = new double[n];
        double[] l = new double[n];
        double[] v = new double[n];
        for (int i = 0; i < n; i++) {
            t[i] = i * 3_600_000L;
            h[i] = Math.max(open[i], close[i]);
            l[i] = Math.min(open[i], close[i]);
            v[i] = 100;
        }
        return new CandleSeries(t, open, h, l, close, v);
    }

    static CandleSeries flat(double price, int n) {
        double[] p = new double[n];
        java.util.Arrays.fill(p, price);
        return series(p, p.clone());
    }

    /** Records what the engine did with each order. */
    static final class Outcomes implements BarObserver {
        final List<FillOutcome> seen = new ArrayList<>();

        @Override
        public void onOrder(int signalBar, int fillBar, int ruleIndex, int stmtIndex,
                            FillOutcome outcome) {
            seen.add(outcome);
        }
    }

    @Nested
    @DisplayName("missing data is 'unknown', and NOT keeps it unknown")
    class ThreeValued {

        @Test
        @DisplayName("NOT of a comparison that has no answer does not trade")
        void notOfUnknownDoesNotBuy() {
            // Flat candles: HIGH - LOW and CLOSE - OPEN are both 0, so the
            // ratio is 0 / 0 = NaN on every candle. With plain booleans the
            // comparison read as false and NOT turned it into a buy.
            CompiledStrategy nan = compile("",
                    "rule r { IF NOT ((CLOSE - OPEN) / (HIGH - LOW) > 0.5) THEN BUY ALL }");
            assertEquals(0, new Backtester().run(nan, flat(100, 20), ExchangeRules.none())
                    .trades().size());
            // x / 0 is Infinity: no honest answer either, so no trade.
            CompiledStrategy inf = compile("",
                    "rule r { IF NOT (CLOSE / (OPEN - OPEN) < 1) THEN BUY ALL }");
            assertEquals(0, new Backtester().run(inf, flat(100, 20), ExchangeRules.none())
                    .trades().size());
        }

        @Test
        @DisplayName("ELSE runs on a definite false only: no answer runs neither branch")
        void elseNeedsADefiniteFalse() {
            // No answer on any candle (0 / 0 on flat candles): before, the
            // ELSE ran as if the condition were false, and bought.
            CompiledStrategy unknown = compile("",
                    "rule r { IF (CLOSE - OPEN) / (HIGH - LOW) > 0.5 THEN SELL ALL ELSE BUY ALL }");
            assertEquals(0, new Backtester().run(unknown, flat(100, 20), ExchangeRules.none())
                    .trades().size());
            // A definite false still takes the ELSE: one buy, closed at the end.
            CompiledStrategy no = compile("",
                    "rule r { IF CLOSE < 0 THEN SELL ALL ELSE BUY ALL }");
            assertEquals(1, new Backtester().run(no, flat(100, 20), ExchangeRules.none())
                    .trades().size());
        }

        @Test
        @DisplayName("AND/OR follow Kleene logic: false AND unknown = false, true OR unknown = true")
        void kleene() {
            CompiledStrategy s = compile("""
                    let unknown = CLOSE / (OPEN - OPEN) > 1
                    let yes = CLOSE > 0
                    let no = CLOSE < 0
                    """, "rule r { IF yes THEN BUY ALL }");
            CandleSeries c = flat(100, 5);
            Interpreter in = new Interpreter(s, c, IndicatorBank.compute(s, c));
            var lets = s.lets();
            var unknown = lets.get(0).value();
            var yes = lets.get(1).value();
            var no = lets.get(2).value();
            assertEquals(Interpreter.UNKNOWN, in.tri(unknown, 2));
            assertEquals(Interpreter.FALSE, in.tri(and(no, unknown), 2));
            assertEquals(Interpreter.UNKNOWN, in.tri(and(yes, unknown), 2));
            assertEquals(Interpreter.TRUE, in.tri(or(yes, unknown), 2));
            assertEquals(Interpreter.UNKNOWN, in.tri(or(no, unknown), 2));
            assertFalse(in.bool(unknown, 2), "unknown never trades");
        }

        private com.tsb.compiler.Expr and(com.tsb.compiler.Expr a, com.tsb.compiler.Expr b) {
            return new com.tsb.compiler.Expr.Binary(com.tsb.compiler.Expr.BinaryOp.AND, a, b, a.span());
        }

        private com.tsb.compiler.Expr or(com.tsb.compiler.Expr a, com.tsb.compiler.Expr b) {
            return new com.tsb.compiler.Expr.Binary(com.tsb.compiler.Expr.BinaryOp.OR, a, b, a.span());
        }
    }

    @Nested
    @DisplayName("order sizes a spot exchange would accept")
    class Sizes {

        @Test
        @DisplayName("a fixed BUY that costs more than the cash is refused, not bought on credit")
        void fixedBuyNeedsCash() {
            // 1000 USDT, BTC at 100: 20 coins would cost 2000.
            CompiledStrategy s = compile("", "rule r { IF CLOSE > 0 THEN BUY qty = 20 }");
            Outcomes seen = new Outcomes();
            BacktestResult r = new Backtester().run(s, flat(100, 5), ExchangeRules.none(), seen);
            assertEquals(0, r.trades().size());
            assertEquals(1000, r.finalEquity(), 1e-9);
            assertTrue(seen.seen.contains(BarObserver.FillOutcome.REJECTED_NOT_ENOUGH_CASH));
        }

        @Test
        @DisplayName("BUY qty = 2 buys exactly 2 coins, with the fee paid on top")
        void fixedBuyIsExact() {
            CompiledStrategy s = compile("fee = 1%", "rule r { IF CLOSE > 0 THEN BUY qty = 2 }");
            BacktestResult r = new Backtester().run(s, flat(100, 3), ExchangeRules.none());
            Trade t = r.trades().get(0);
            assertEquals(2, t.qty(), 1e-12);
            // cash after buying: 1000 - 200 - 2 (fee); the position is sold
            // at the end: + 200 - 2.
            assertEquals(1000 - 2 - 2, r.finalEquity(), 1e-9);
        }

        @Test
        @DisplayName("SELL ALL sells exactly what is held")
        void sellAllIsEverything() {
            ExchangeRules lots = new ExchangeRules(0.1, 5);
            assertEquals(1.05, Backtester.sellSize(new StrategyAst.Sizing.All(span()),
                    1.05, 0, 100, lots), 1e-12);
        }

        @Test
        @DisplayName("a partial sell that would leave less than one step closes everything")
        void noDust() {
            ExchangeRules lots = new ExchangeRules(0.1, 0);
            // 99% of 1.05 is 1.0395 -> rounded 1.0, leaving 0.05: less than one
            // step, never sellable later. Sell all 1.05 instead.
            assertEquals(1.05, Backtester.sellSize(percent(99, StrategyAst.Sizing.Base.POSITION),
                    1.05, 0, 100, lots), 1e-12);
            // 50% leaves 0.55 — a real remainder, so only 0.5 is sold.
            assertEquals(0.5, Backtester.sellSize(percent(50, StrategyAst.Sizing.Base.POSITION),
                    1.05, 0, 100, lots), 1e-12);
        }

        @Test
        @DisplayName("a partial sell below the exchange minimum is refused; a full exit never is")
        void partialSellMinimum() {
            ExchangeRules lots = new ExchangeRules(0.001, 5);
            // 10 coins at 1 USDT: 10% is 1 USDT, under the 5 USDT minimum.
            assertEquals(0, Backtester.sellSize(percent(10, StrategyAst.Sizing.Base.POSITION),
                    10, 0, 1, lots), 1e-12);
            // The whole 10 USDT position can always be closed.
            assertEquals(10, Backtester.sellSize(new StrategyAst.Sizing.All(span()),
                    10, 0, 0.1, lots), 1e-12);
        }

        @Test
        @DisplayName("SELL qty = 25% OF EQUITY sells coins worth a quarter of the account")
        void sellShareOfEquity() {
            // 10 coins at 100 + 1000 cash = 2000 equity; a quarter is 500 = 5 coins.
            assertEquals(5, Backtester.sellSize(percent(25, StrategyAst.Sizing.Base.EQUITY),
                    10, 1000, 100, ExchangeRules.none()), 1e-9);
        }

        private StrategyAst.Sizing percent(double pct, StrategyAst.Sizing.Base base) {
            return new StrategyAst.Sizing.PercentOf(
                    new com.tsb.compiler.Expr.PercentLit(pct, span()), base, span());
        }

        private com.tsb.compiler.Span span() {
            return com.tsb.compiler.Span.of(1, 1, 1);
        }
    }

    @Nested
    @DisplayName("indicator maths")
    class IndicatorMaths {

        private double[] ramp(int n) {
            double[] x = new double[n];
            for (int i = 0; i < n; i++) {
                x[i] = 100 + 3 * Math.sin(i / 5.0) + i * 0.1;
            }
            return x;
        }

        @Test
        @DisplayName("MACD with swapped periods is the usual line, upside down, and has a signal")
        void swappedMacd() {
            double[] c = ramp(120);
            double[] normal = Indicators.macdLine(c, 12, 26);
            double[] swapped = Indicators.macdLine(c, 26, 12);
            for (int i = 25; i < c.length; i++) {
                assertEquals(-normal[i], swapped[i], 1e-9);
            }
            double[] signal = Indicators.macdSignal(c, 26, 12, 9);
            assertTrue(Double.isNaN(signal[32]));
            assertFalse(Double.isNaN(signal[33]), "first signal at max(26,12)-1 + 9-1 = 33");
        }

        @Test
        @DisplayName("HMA(14) smooths with floor(sqrt(14)) = 3, as Hull defined it")
        void hullWindow() {
            double[] c = ramp(60);
            double[] hma = Indicators.hma(c, 14);
            double[] half = Indicators.wma(c, 7);
            double[] full = Indicators.wma(c, 14);
            double[] raw = new double[c.length];
            for (int i = 0; i < c.length; i++) {
                raw[i] = 2 * half[i] - full[i];
            }
            double[] expected = Indicators.wma(raw, 3);
            for (int i = 0; i < c.length; i++) {
                assertEquals(expected[i], hma[i], 1e-9, "bar " + i);
            }
            assertTrue(Double.isNaN(hma[14]));
            assertFalse(Double.isNaN(hma[15]), "14-1 + 3-1 = 15");
        }

        @Test
        @DisplayName("WMA's weight total no longer overflows for huge periods")
        void wmaNoOverflow() {
            int p = 50_000; // p*(p+1)/2 is past Integer.MAX_VALUE
            double[] x = new double[p];
            java.util.Arrays.fill(x, 5);
            assertEquals(5, Indicators.wma(x, p)[p - 1], 1e-9);
        }
    }

    @Nested
    @DisplayName("lets read at earlier candles")
    class LetHistoryCache {

        private String chain(int n) {
            StringBuilder sb = new StringBuilder("let a0 = CLOSE\n");
            for (int k = 1; k <= n; k++) {
                sb.append("let a").append(k).append(" = a").append(k - 1)
                        .append("[1] + a").append(k - 1).append("[2]\n");
            }
            return sb.toString();
        }

        @Test
        @DisplayName("a let read at earlier candles is worked out once per candle, not once per read")
        void notExponential() {
            // Each line reads the line above twice, at two earlier candles.
            // Caching only the current candle made every line double the
            // work: 30 lines would have needed about a billion steps per candle.
            CompiledStrategy s = compile("", chain(30) + "rule r { IF a30 > 0 THEN BUY ALL }");
            CandleSeries c = flat(1, 3_000);
            long t0 = System.nanoTime();
            BacktestResult r = new Backtester().run(s, c, ExchangeRules.none());
            long ms = (System.nanoTime() - t0) / 1_000_000;
            assertTrue(ms < 20_000, "took " + ms + " ms");
            assertEquals(1, r.trades().size());
        }

        @Test
        @DisplayName("values are right, in order and in random order (the debugger jumps around)")
        void sameValuesAnyOrder() {
            CompiledStrategy s = compile("", chain(4) + "rule r { IF a4 > 0 THEN BUY ALL }");
            double[] p = new double[80];
            for (int i = 0; i < p.length; i++) {
                p[i] = 10 + (i * 7 % 13);
            }
            CandleSeries c = series(p, p.clone());
            var a4 = s.lets().get(4).value();
            Interpreter forward = new Interpreter(s, c, IndicatorBank.compute(s, c));
            double[] seq = new double[p.length];
            for (int i = 0; i < p.length; i++) {
                seq[i] = forward.num(a4, i);
            }
            // By hand: a1[i] = c[i-1] + c[i-2], and so on.
            double[][] a = new double[5][p.length];
            for (int i = 0; i < p.length; i++) {
                a[0][i] = p[i];
            }
            for (int k = 1; k <= 4; k++) {
                for (int i = 0; i < p.length; i++) {
                    a[k][i] = i < 2 ? Double.NaN : a[k - 1][i - 1] + a[k - 1][i - 2];
                }
            }
            for (int i = 0; i < p.length; i++) {
                assertEquals(a[4][i], seq[i], 1e-9, "bar " + i);
            }
            Interpreter jumpy = new Interpreter(s, c, IndicatorBank.compute(s, c));
            int[] order = {70, 12, 70, 69, 3, 40, 41, 39, 79, 8, 8, 55};
            for (int i : order) {
                assertEquals(a[4][i], jumpy.num(a4, i), 1e-9, "bar " + i);
            }
        }
    }
}
