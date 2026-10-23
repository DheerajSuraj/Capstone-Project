package com.tsb.execution;

import com.tsb.compiler.Analyzer;
import com.tsb.compiler.CompiledStrategy;
import com.tsb.compiler.Lexer;
import com.tsb.compiler.Parser;
import com.tsb.marketdata.CandleSeries;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The fill-model contract, pinned scenario by scenario. Every expected
 * number is hand-computable from the bar data in the fixture — fees are 0
 * unless the test is ABOUT fees, so the money arithmetic stays checkable
 * on paper.
 */
@DisplayName("Backtester")
class BacktesterTest {

    // ── Fixtures ────────────────────────────────────────────────────────

    private static CompiledStrategy compile(String configExtra, String rules) {
        // Fee-free unless the test sets one: the language's default is 0.1%,
        // but these scenarios check fills, and round numbers keep them
        // checkable by hand.
        String config = configExtra.contains("fee") ? configExtra
                : "fee = 0%\n" + configExtra;
        String source = """
                strategy "T" {
                    symbol = BTCUSDT
                    timeframe = 1h
                    capital = 1000
                    %s
                    %s
                }
                """.formatted(config, rules);
        Lexer.LexResult lexed = new Lexer(source).scan();
        Parser.ParseResult parsed = new Parser(lexed.tokens()).parse();
        Analyzer.AnalysisResult analyzed =
                new Analyzer(parsed.strategy().orElseThrow()).analyze();
        assertEquals(List.of(), analyzed.diagnostics(),
                "fixture must compile: " + analyzed.diagnostics());
        return analyzed.strategy().orElseThrow();
    }

    private static CandleSeries series(double[] open, double[] high,
                                       double[] low, double[] close) {
        long[] t = new long[open.length];
        double[] v = new double[open.length];
        for (int i = 0; i < open.length; i++) {
            t[i] = i * 3_600_000L;
            v[i] = 100;
        }
        return new CandleSeries(t, open, high, low, close, v);
    }

    /** Series where highs/lows hug the o/c range — no stray stop triggers. */
    private static CandleSeries tightSeries(double[] open, double[] close) {
        double[] high = new double[open.length];
        double[] low = new double[open.length];
        for (int i = 0; i < open.length; i++) {
            high[i] = Math.max(open[i], close[i]);
            low[i] = Math.min(open[i], close[i]);
        }
        return series(open, high, low, close);
    }

    private static BacktestResult run(CompiledStrategy s, CandleSeries c) {
        return new Backtester().run(s, c, ExchangeRules.none());
    }

    // ── The marquee guarantee ───────────────────────────────────────────

    @Nested
    @DisplayName("signal on close, fill at next open")
    class SignalCloseFillOpen {

        @Test
        @DisplayName("THE no-look-ahead fill: signal bar's close is NOT the fill price")
        void fillsAtNextOpen() {
            // closes: 10, 10, 20, 20, 25   opens: 10, 10, 12, 13, 20
            // Bar 2 closes at 20 -> signal. Fill must be bar 3's OPEN = 13,
            // not bar 2's close = 20.
            CompiledStrategy s = compile("",
                    "rule r { IF CLOSE > 15 THEN BUY ALL }");
            BacktestResult result = run(s, tightSeries(
                    new double[]{10, 10, 12, 13, 20},
                    new double[]{10, 10, 20, 20, 25}));

            Trade trade = result.trades().get(0);
            assertEquals(3, trade.entryBar());
            assertEquals(13.0, trade.entryPrice(), 1e-9);
            // Held to end of data, closed at last close 25.
            assertEquals(Trade.ExitReason.END_OF_DATA, trade.exitReason());
            // qty = 1000/13; final equity = qty * 25.
            assertEquals(1000.0 / 13 * 25, result.finalEquity(), 1e-6);
        }

        @Test
        @DisplayName("a signal on the LAST bar never fills — no next open exists")
        void lastBarSignalDropped() {
            CompiledStrategy s = compile("",
                    "rule r { IF CLOSE > 15 THEN BUY ALL }");
            BacktestResult result = run(s, tightSeries(
                    new double[]{10, 10, 10},
                    new double[]{10, 10, 20})); // signal only on final bar
            assertEquals(0, result.trades().size());
            assertEquals(1000.0, result.finalEquity(), 1e-9);
        }

        @Test
        @DisplayName("round trip via SELL signal: both fills at their next opens")
        void roundTrip() {
            // buy signal at bar1 close 20 -> entry bar2 open 16
            // sell signal at bar3 close 4 -> exit bar4 open 3
            CompiledStrategy s = compile("", """
                    rule entry { IF CLOSE > 15 THEN BUY ALL }
                    rule exit { IF CLOSE < 5 THEN SELL ALL }
                    """);
            BacktestResult result = run(s, tightSeries(
                    new double[]{10, 10, 16, 20, 3},
                    new double[]{10, 20, 20, 4, 4}));

            Trade trade = result.trades().get(0);
            assertEquals(16.0, trade.entryPrice(), 1e-9);
            assertEquals(3.0, trade.exitPrice(), 1e-9);
            assertEquals(Trade.ExitReason.SIGNAL, trade.exitReason());
            // qty = 1000/16 = 62.5; exit proceeds 62.5*3 = 187.5.
            // Tolerance, not exact equality: 1000.0/16 is not perfectly
            // representable in IEEE-754, leaving a sub-nanometer residue —
            // real, expected floating-point behaviour, so we compare within
            // an epsilon like every other money assertion here.
            assertEquals(187.5, result.finalEquity(), 1e-6);
            assertEquals(0.0, result.winRate(), 1e-9);
        }

        @Test
        @DisplayName("warm-up is honored: no execution before the compiler's warmupBars")
        void warmupHonored() {
            CompiledStrategy s = compile("",
                    "rule r { IF CLOSE > SMA(CLOSE, 3) THEN BUY ALL }");
            assertEquals(3, s.warmupBars());
            // Rising closes: condition would be true from bar 1 if the
            // engine cheated. First evaluable bar is 3 -> first fill bar 4.
            BacktestResult result = run(s, tightSeries(
                    new double[]{1, 2, 3, 4, 5, 6},
                    new double[]{2, 3, 4, 5, 6, 7}));
            assertTrue(result.trades().get(0).entryBar() >= 4);
        }
    }

    // ── Protective exits ────────────────────────────────────────────────

    @Nested
    @DisplayName("protective exits")
    class ProtectiveExits {

        private static final String STOP_RULES = """
                rule entry { IF CLOSE > 15 THEN BUY ALL }
                rule protect { IF CLOSE > 0 THEN SET STOPLOSS = 10% }
                """;

        @Test
        @DisplayName("stop fills AT THE STOP LEVEL, not at the bar's low")
        void stopFillsAtLevel() {
            // Entry bar2 open 20 -> stop level 18. Bar 3 low 15 pierces it.
            CompiledStrategy s = compile("", STOP_RULES);
            BacktestResult result = run(s, series(
                    new double[]{10, 10, 20, 19, 19},
                    new double[]{10, 20, 21, 19, 19},
                    new double[]{10, 10, 19.5, 15, 19},
                    new double[]{10, 20, 20, 19, 19}));

            Trade trade = result.trades().get(0);
            assertEquals(Trade.ExitReason.STOPLOSS, trade.exitReason());
            assertEquals(18.0, trade.exitPrice(), 1e-9,
                    "pessimistic fill at the level; the low (15) is not ours");
        }

        @Test
        @DisplayName("take-profit triggers off the high, fills at the level")
        void takeProfit() {
            CompiledStrategy s = compile("", """
                    rule entry { IF CLOSE > 15 THEN BUY ALL }
                    rule protect { IF CLOSE > 0 THEN SET TAKEPROFIT = 10% }
                    """);
            // Entry bar2 open 20 -> tp level 22. Bar 3 high 25 reaches it.
            BacktestResult result = run(s, series(
                    new double[]{10, 10, 20, 21, 21},
                    new double[]{10, 20, 21, 25, 21},
                    new double[]{10, 10, 19.5, 20.5, 21},
                    new double[]{10, 20, 20, 21, 21}));

            Trade trade = result.trades().get(0);
            assertEquals(Trade.ExitReason.TAKEPROFIT, trade.exitReason());
            assertEquals(22.0, trade.exitPrice(), 1e-9);
            assertTrue(trade.isWin());
        }

        @Test
        @DisplayName("when stop AND take-profit could both fire in one bar, the stop wins")
        void stopBeatsTakeProfitOnTie() {
            CompiledStrategy s = compile("", """
                    rule entry { IF CLOSE > 15 THEN BUY ALL }
                    rule a { IF CLOSE > 0 THEN SET STOPLOSS = 10% }
                    rule b { IF CLOSE > 0 THEN SET TAKEPROFIT = 10% }
                    """);
            // Entry 20: stop 18, tp 22. Bar 3 spans 15..25 — both reachable.
            BacktestResult result = run(s, series(
                    new double[]{10, 10, 20, 20, 20},
                    new double[]{10, 20, 21, 25, 20},
                    new double[]{10, 10, 19.5, 15, 20},
                    new double[]{10, 20, 20, 20, 20}));

            assertEquals(Trade.ExitReason.STOPLOSS,
                    result.trades().get(0).exitReason(),
                    "unknowable intrabar order -> assume the worse outcome");
        }
    }

    // ── Honest fills ────────────────────────────────────────────────────

    @Nested
    @DisplayName("honest fills")
    class HonestFills {

        private static final String STOP_5 = """
                rule entry { IF CLOSE > 15 THEN BUY ALL }
                rule protect { IF CLOSE > 0 THEN SET STOPLOSS = 5% }
                """;

        @Test
        @DisplayName("a stop the candle opens below fills at the OPEN, not at the stop")
        void gappedStopFillsAtOpen() {
            // Entry bar 2 at 100 -> stop 95. Bar 3 OPENS at 80: nobody could
            // have sold at 95. (The old engine recorded 95.)
            CompiledStrategy s = compile("", STOP_5);
            BacktestResult result = run(s, series(
                    new double[]{10, 10, 100, 80, 81},
                    new double[]{10, 20, 101, 82, 81},
                    new double[]{10, 10, 99, 78, 81},
                    new double[]{10, 20, 100, 81, 81}));

            Trade trade = result.trades().get(0);
            assertEquals(Trade.ExitReason.STOPLOSS, trade.exitReason());
            assertEquals(80.0, trade.exitPrice(), 1e-9);
        }

        @Test
        @DisplayName("a stop reached inside the candle still fills at the stop")
        void stopInsideCandleFillsAtLevel() {
            CompiledStrategy s = compile("", STOP_5);
            BacktestResult result = run(s, series(
                    new double[]{10, 10, 100, 97, 97},
                    new double[]{10, 20, 101, 98, 97},
                    new double[]{10, 10, 99, 90, 97},
                    new double[]{10, 20, 100, 96, 97}));
            assertEquals(95.0, result.trades().get(0).exitPrice(), 1e-9);
        }

        @Test
        @DisplayName("a take-profit the candle opens above fills at the higher open")
        void gappedTakeProfitFillsAtOpen() {
            CompiledStrategy s = compile("", """
                    rule entry { IF CLOSE > 15 THEN BUY ALL }
                    rule protect { IF CLOSE > 0 THEN SET TAKEPROFIT = 10% }
                    """);
            // Entry 100 -> target 110. Bar 3 opens at 115.
            BacktestResult result = run(s, series(
                    new double[]{10, 10, 100, 115, 115},
                    new double[]{10, 20, 101, 116, 115},
                    new double[]{10, 10, 99, 114, 115},
                    new double[]{10, 20, 100, 115, 115}));
            Trade trade = result.trades().get(0);
            assertEquals(Trade.ExitReason.TAKEPROFIT, trade.exitReason());
            assertEquals(115.0, trade.exitPrice(), 1e-9);
        }

        @Test
        @DisplayName("a trailing stop does not use the same candle's high (it may have come after the low)")
        void trailingIgnoresSameCandleHigh() {
            CompiledStrategy s = compile("", """
                    rule entry { IF CLOSE > 15 THEN BUY ALL }
                    rule protect { IF CLOSE > 0 THEN SET TRAILING = 5% }
                    """);
            // Entry bar 2 at 100. Bar 3: high 120, low 99. Trailing from the
            // high would say "sold at 114" — but if the low came first, the
            // price never stood at 114 after a 120 peak. Trailing from the
            // earlier peak (100) puts the level at 95, untouched.
            BacktestResult result = run(s, series(
                    new double[]{10, 10, 100, 100, 118},
                    new double[]{10, 20, 100, 120, 119},
                    new double[]{10, 10, 100, 99, 115},
                    new double[]{10, 20, 100, 110, 116}));

            Trade trade = result.trades().get(0);
            // Bar 4 trails bar 3's high: level 120 x 0.95 = 114, not reached
            // by bar 4 (low 115), so the trade runs to the end of the data.
            assertEquals(Trade.ExitReason.END_OF_DATA, trade.exitReason());
        }

        @Test
        @DisplayName("the next candle trails the new high")
        void trailingUsesEarlierHighs() {
            CompiledStrategy s = compile("", """
                    rule entry { IF CLOSE > 15 THEN BUY ALL }
                    rule protect { IF CLOSE > 0 THEN SET TRAILING = 5% }
                    """);
            // Bar 3 makes the high 120. Bar 4 opens 116 (above 114), dips to
            // 113: the trailing stop at 114 fills.
            BacktestResult result = run(s, series(
                    new double[]{10, 10, 100, 100, 116, 113},
                    new double[]{10, 20, 100, 120, 117, 113},
                    new double[]{10, 10, 100, 99, 113, 113},
                    new double[]{10, 20, 100, 110, 113, 113}));

            Trade trade = result.trades().get(0);
            assertEquals(Trade.ExitReason.TRAILING, trade.exitReason());
            assertEquals(114.0, trade.exitPrice(), 1e-9);
            assertEquals(4, trade.exitBar());
        }

        @Test
        @DisplayName("with a stop-loss and a higher trailing stop, the price meets the trailing stop first")
        void higherStopFillsFirst() {
            CompiledStrategy s = compile("", """
                    rule entry { IF CLOSE > 15 THEN BUY ALL }
                    rule a { IF CLOSE > 0 THEN SET STOPLOSS = 10% }
                    rule b { IF CLOSE > 0 THEN SET TRAILING = 5% }
                    """);
            // Entry 100: stop 90. Bar 3 peaks at 110 -> trailing 104.5 for
            // bar 4, which falls to 85: on the way down it passes 104.5 first.
            BacktestResult result = run(s, series(
                    new double[]{10, 10, 100, 105, 106, 85},
                    new double[]{10, 20, 100, 110, 106, 85},
                    new double[]{10, 10, 100, 105, 85, 85},
                    new double[]{10, 20, 100, 106, 86, 85}));

            Trade trade = result.trades().get(0);
            assertEquals(Trade.ExitReason.TRAILING, trade.exitReason());
            assertEquals(104.5, trade.exitPrice(), 1e-9);
        }

        @Test
        @DisplayName("a strategy without 'fee' pays the 0.1% default")
        void defaultFee() {
            String source = """
                    strategy "T" {
                        symbol = BTCUSDT
                        timeframe = 1h
                        capital = 1000
                        rule r { IF CLOSE > 15 THEN BUY ALL }
                    }
                    """;
            CompiledStrategy s = new Analyzer(new Parser(new Lexer(source).scan()
                    .tokens()).parse().strategy().orElseThrow()).analyze()
                    .strategy().orElseThrow();
            assertEquals(0.001, s.feePercent(), 1e-15);
            BacktestResult result = run(s, tightSeries(
                    new double[]{10, 10, 10, 10},
                    new double[]{10, 20, 10, 10}));
            assertTrue(result.trades().get(0).fees() > 0, "fees were charged");
        }
    }

    // ── Money mechanics ─────────────────────────────────────────────────

    @Nested
    @DisplayName("money mechanics")
    class Money {

        @Test
        @DisplayName("percent-of-equity sizing: 50% of 1000 at open 10 buys qty 50")
        void percentSizing() {
            CompiledStrategy s = compile("",
                    "rule r { IF CLOSE > 15 THEN BUY qty = 50% OF EQUITY }");
            BacktestResult result = run(s, tightSeries(
                    new double[]{10, 10, 10, 10},
                    new double[]{10, 20, 20, 20}));
            assertEquals(50.0, result.trades().get(0).qty(), 1e-9);
        }

        @Test
        @DisplayName("fees are charged both sides and land in the trade")
        void feeAccounting() {
            // fee 1%. BUY ALL at open 10: qty = 1000/(10*1.01) = 99.0099...
            // notional 990.099, entry fee 9.90099; END_OF_DATA exit at 10:
            // exit fee 9.90099; pnl = 0 - fees = -19.80198
            CompiledStrategy s = compile("fee = 1%",
                    "rule r { IF CLOSE > 15 THEN BUY ALL }");
            BacktestResult result = run(s, tightSeries(
                    new double[]{10, 10, 10, 10},
                    new double[]{10, 20, 10, 10}));

            Trade trade = result.trades().get(0);
            double qty = 1000.0 / (10 * 1.01);
            assertEquals(qty, trade.qty(), 1e-9);
            assertEquals(2 * qty * 10 * 0.01, trade.fees(), 1e-9);
            assertEquals(-trade.fees(), trade.pnl(), 1e-9);
            assertEquals(1000 - trade.fees(), result.finalEquity(), 1e-9);
        }

        @Test
        @DisplayName("orders below minNotional are skipped, never rounded up")
        void minNotionalSkip() {
            CompiledStrategy s = compile("",
                    "rule r { IF CLOSE > 15 THEN BUY ALL }");
            BacktestResult result = new Backtester().run(s, tightSeries(
                            new double[]{10, 10, 10, 10},
                            new double[]{10, 20, 20, 20}),
                    new ExchangeRules(1e-9, 5_000)); // min 5000 > capital
            assertEquals(0, result.trades().size());
        }

        @Test
        @DisplayName("the equity curve marks open positions to market each bar")
        void equityMarksToMarket() {
            CompiledStrategy s = compile("",
                    "rule r { IF CLOSE > 15 THEN BUY ALL }");
            // Entry bar2 open 10, qty 100. Bar 2 close 12 -> equity 1200;
            // bar 3 close 8 -> equity 800 (the drawdown is VISIBLE).
            BacktestResult result = run(s, series(
                    new double[]{10, 10, 10, 8, 9},
                    new double[]{10, 20, 12, 8, 9},
                    new double[]{10, 10, 9, 7.5, 8.5},
                    new double[]{10, 20, 12, 8, 9}));

            assertEquals(1200.0, result.equityCurve()[2], 1e-9);
            assertEquals(800.0, result.equityCurve()[3], 1e-9);
            assertTrue(result.maxDrawdownPct() > 30.0);
        }

        @Test
        @DisplayName("ELSE branches execute when the condition is false")
        void elseBranch() {
            CompiledStrategy s = compile("",
                    "rule r { IF CLOSE > 15 THEN BUY ALL ELSE SELL ALL }");
            // Bar1 close 20 -> buy, fills bar2 open 10.
            // Bar2 close 10 -> ELSE sell, fills bar3 open 12.
            BacktestResult result = run(s, tightSeries(
                    new double[]{10, 10, 10, 12, 12},
                    new double[]{10, 20, 10, 12, 12}));

            Trade trade = result.trades().get(0);
            assertEquals(10.0, trade.entryPrice(), 1e-9);
            assertEquals(12.0, trade.exitPrice(), 1e-9);
            assertEquals(200.0, trade.pnl(), 1e-9); // qty 100 x (12-10)
        }
    }
}