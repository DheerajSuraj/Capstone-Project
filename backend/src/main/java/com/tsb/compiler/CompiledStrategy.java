package com.tsb.compiler;

import java.util.List;
import java.util.Set;

/**
 * A strategy that passed ALL compiler phases — the contract between the
 * compiler and the execution engine. If one of these exists, the engine may
 * run it without re-validating anything: names resolve, types check,
 * indicator arguments are constants, and the warm-up is known.
 *
 * <p>{@code indicators} is the <b>indicator manifest</b>: the deduplicated
 * set of concrete indicator instances the strategy uses. It is literally the
 * work order for the engine's precompute phase (roadmap §8): one
 * {@code double[]} gets computed per entry, before any bar executes.
 *
 * <p>{@code warmupBars} is the first bar index at which every indicator and
 * lookback the strategy touches has meaningful data; the engine refuses to
 * trade before it. Computing this at compile time kills the classic backtest
 * bug of trading on garbage warm-up values.
 *
 * @param name        strategy display name
 * @param symbol      e.g. "BTCUSDT" (existence is checked against the DB
 *                    later, at run submission — not a compiler concern)
 * @param timeframe   e.g. "1h"
 * @param capital     starting capital, > 0
 * @param feePercent  taker fee as a fraction (0.1% -> 0.001) — the /100
 *                    happens HERE, in exactly one place, as promised in
 *                    Token's javadoc
 * @param lets        let declarations in source (= evaluation) order
 * @param rules       rules in source (= evaluation) order
 * @param indicators  the indicator manifest for precompute
 * @param warmupBars  first tradeable bar index
 */
public record CompiledStrategy(
        String name,
        String symbol,
        String timeframe,
        double capital,
        double feePercent,
        List<StrategyAst.LetDecl> lets,
        List<StrategyAst.RuleDecl> rules,
        Set<IndicatorInstance> indicators,
        int warmupBars
) {

    /**
     * One concrete indicator the engine must precompute, e.g.
     * {@code SMA(CLOSE, 200)} = {@code ("SMA", CLOSE, [200.0])}.
     * A record so that identical instances deduplicate in the manifest Set —
     * two rules both using RSI(14) cost one computation, which is the
     * "computed exactly once" rule from §5.4 of the addendum enforced by
     * the type system.
     *
     * @param name      registry name (RSI, SMA, ...)
     * @param source    the price series argument, or null for indicators
     *                  that fix their own inputs (RSI, ATR, VWAP, MACD)
     * @param constArgs the constant arguments in declaration order
     */
    public record IndicatorInstance(
            String name,
            Expr.PriceField source,
            List<Double> constArgs
    ) {
        /** Stable cache/display key, e.g. {@code SMA(CLOSE,200)}. */
        public String key() {
            StringBuilder sb = new StringBuilder(name).append('(');
            boolean first = true;
            if (source != null) {
                sb.append(source);
                first = false;
            }
            for (double a : constArgs) {
                if (!first) {
                    sb.append(',');
                }
                sb.append(a == Math.floor(a) ? String.valueOf((long) a)
                        : String.valueOf(a));
                first = false;
            }
            return sb.append(')').toString();
        }
    }
}