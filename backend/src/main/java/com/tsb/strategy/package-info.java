/**
 * Strategies and their append-only versions, backtest runs, trades, equity
 * curves, traces, and the Postgres SKIP LOCKED job queue + worker.
 * {@code strategy_versions} is immutable at the DB level (trigger) and must
 * be mapped {@code @Immutable} / insert-only in JPA. (Roadmap §12)
 */
package com.tsb.strategy;
