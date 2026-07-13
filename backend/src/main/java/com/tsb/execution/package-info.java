/**
 * The backtest execution engine: streaming indicator precompute, bar-by-bar
 * AST interpreter, portfolio/fill model (signal on close, fill at next open),
 * metrics, and the trace recorder. Pure computation over primitive arrays —
 * consumes a validated AST from {@code compiler} and candle columns supplied
 * by {@code marketdata}. Never imports web or persistence code. (Roadmap §8)
 */
package com.tsb.execution;
