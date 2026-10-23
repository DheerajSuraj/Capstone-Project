package com.tsb.strategy;

import com.tsb.compiler.Analyzer;
import com.tsb.compiler.CompiledStrategy;
import com.tsb.compiler.Lexer;
import com.tsb.compiler.Parser;
import com.tsb.execution.BacktestResult;
import com.tsb.execution.Backtester;
import com.tsb.execution.ExchangeRules;
import com.tsb.marketdata.CandleSeries;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** The "vs buy & hold" number must be what a real buy-and-hold run makes. */
@DisplayName("Buy & hold benchmark")
class BuyHoldBenchmarkTest {

    static CompiledStrategy compile(String body) {
        String src = """
                strategy "T" {
                    symbol = BTCUSDT
                    timeframe = 1h
                    capital = 1000
                    fee = 0.1%%
                    %s
                }
                """.formatted(body);
        var lexed = new Lexer(src).scan();
        var parsed = new Parser(lexed.tokens()).parse();
        return new Analyzer(parsed.strategy().orElseThrow()).analyze().strategy().orElseThrow();
    }

    static CandleSeries series(int n) {
        long[] t = new long[n];
        double[] o = new double[n], h = new double[n], l = new double[n], c = new double[n], v = new double[n];
        double p = 100;
        for (int i = 0; i < n; i++) {
            double prev = p;
            p = p * (1 + 0.01 * Math.sin(i * 0.7) + 0.002);
            t[i] = i * 3_600_000L;
            o[i] = prev;
            c[i] = p;
            h[i] = Math.max(prev, p);
            l[i] = Math.min(prev, p);
            v[i] = 1;
        }
        return new CandleSeries(t, o, h, l, c, v);
    }

    @Test
    @DisplayName("equals the return of a strategy that buys once and holds")
    void matchesARealRun() {
        CompiledStrategy hold = compile("rule entry { IF CLOSE > 0 THEN BUY ALL }");
        CandleSeries s = series(300);
        BacktestResult r = new Backtester().run(hold, s, ExchangeRules.none());
        Double benchmark = BacktestController.Result.buyHoldReturnPct(s, r.warmupBars(), hold.feePercent());
        assertEquals(r.totalReturnPct(), benchmark, 1e-6);
    }

    @Test
    @DisplayName("starts where the strategy could first trade, not at bar 0")
    void startsAfterWarmup() {
        CandleSeries s = series(300);
        CompiledStrategy withWarmup = compile("rule entry { IF CLOSE > SMA(CLOSE, 50) THEN BUY ALL }");
        double fee = withWarmup.feePercent();
        int w = withWarmup.warmupBars();
        double expected = (s.close()[299] * (1 - fee)) / (s.open()[w + 1] * (1 + fee)) * 100 - 100;
        assertEquals(expected, BacktestController.Result.buyHoldReturnPct(s, w, fee), 1e-9);
    }

    @Test
    @DisplayName("is empty when no bar is left to buy on")
    void tooShort() {
        assertNull(BacktestController.Result.buyHoldReturnPct(series(10), 20, 0.001));
    }
}
