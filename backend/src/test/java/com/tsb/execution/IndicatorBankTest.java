package com.tsb.execution;

import com.tsb.compiler.Analyzer;
import com.tsb.compiler.CompiledStrategy;
import com.tsb.compiler.Expr;
import com.tsb.compiler.Lexer;
import com.tsb.compiler.Parser;
import com.tsb.compiler.Registry;
import com.tsb.compiler.Span;
import com.tsb.marketdata.CandleSeries;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("IndicatorBank")
class IndicatorBankTest {

    /** 40 bars of gently trending synthetic data. */
    private static CandleSeries series() {
        int n = 40;
        long[] t = new long[n];
        double[] o = new double[n];
        double[] h = new double[n];
        double[] l = new double[n];
        double[] c = new double[n];
        double[] v = new double[n];
        for (int i = 0; i < n; i++) {
            t[i] = i * 3_600_000L;
            c[i] = 100 + i + (i % 3);      // wiggly rise
            o[i] = c[i] - 1;
            h[i] = c[i] + 2;
            l[i] = o[i] - 2;
            v[i] = 10 + i;
        }
        return new CandleSeries(t, o, h, l, c, v);
    }

    private static CompiledStrategy compile(String source) {
        Lexer.LexResult lexed = new Lexer(source).scan();
        Parser.ParseResult parsed = new Parser(lexed.tokens()).parse();
        Analyzer.AnalysisResult analyzed =
                new Analyzer(parsed.strategy().orElseThrow()).analyze();
        assertEquals(List.of(), analyzed.diagnostics());
        return analyzed.strategy().orElseThrow();
    }

    @Test
    @DisplayName("bank arrays are exactly what Indicators computes directly")
    void bankMatchesDirectComputation() {
        CompiledStrategy s = compile("""
                strategy "T" {
                    symbol = BTCUSDT
                    timeframe = 1h
                    capital = 10000
                    rule r { IF RSI(14) < 30 AND CLOSE > SMA(CLOSE, 5)
                             THEN BUY ALL }
                }
                """);
        CandleSeries candles = series();
        Map<String, double[]> bank = IndicatorBank.compute(s.indicators(), candles);

        assertEquals(2, bank.size());
        assertArrayEquals(Indicators.rsi(candles.close(), 14),
                bank.get("RSI(14)"));
        assertArrayEquals(Indicators.sma(candles.close(), 5),
                bank.get("SMA(CLOSE,5)"));
    }

    @Test
    @DisplayName("REGISTRY CONSISTENCY: every precomputed indicator in the registry is computable by the bank")
    void everyRegistryIndicatorComputes() {
        CandleSeries candles = series();
        for (String name : Registry.allNames()) {
            Registry.Signature sig = Registry.lookup(name).orElseThrow();
            if (!sig.precomputed()) {
                continue;
            }
            // Build a plausible instance per arity: periods small, k = 2.
            List<Double> args = switch (sig.params().stream()
                    .filter(p -> p.kind() == Registry.ParamKind.CONST_NUMBER)
                    .count() + "") {
                case "0" -> List.of();
                case "1" -> List.of(3.0);
                case "2" -> name.startsWith("BB")
                        ? List.of(3.0, 2.0) : List.of(3.0, 6.0);
                default -> List.of(3.0, 6.0, 2.0);
            };
            Expr.PriceField source = sig.params().stream()
                    .anyMatch(p -> p.kind() == Registry.ParamKind.PRICE_SERIES)
                    ? Expr.PriceField.CLOSE : null;

            double[] out = IndicatorBank.computeOne(
                    new CompiledStrategy.IndicatorInstance(name, source, args),
                    candles);
            assertEquals(candles.size(), out.length,
                    name + " must produce an aligned array");
        }
    }

    @Test
    @DisplayName("execution-time keys agree with compile-time manifest keys by construction")
    void keysAgree() {
        Span span = Span.of(1, 1, 2);
        Expr.Call call = new Expr.Call("SMA", List.of(
                new Expr.PriceRef(Expr.PriceField.CLOSE, span),
                new Expr.NumberLit(5, span)), span);

        CompiledStrategy.IndicatorInstance instance =
                IndicatorBank.instanceFor(call).orElseThrow();
        assertEquals("SMA(CLOSE,5)", instance.key());
    }

    @Test
    @DisplayName("functions (CROSSOVER, MAX...) are never precomputed")
    void functionsAreNotPrecomputed() {
        Span span = Span.of(1, 1, 2);
        Expr.Call crossover = new Expr.Call("CROSSOVER", List.of(
                new Expr.PriceRef(Expr.PriceField.CLOSE, span),
                new Expr.NumberLit(5, span)), span);
        assertTrue(IndicatorBank.instanceFor(crossover).isEmpty());
    }
}