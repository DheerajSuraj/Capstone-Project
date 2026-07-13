/**
 * Symbols, candles, and frozen datasets. Candles are bulk-loaded (COPY) and
 * read via plain JDBC into primitive column arrays — deliberately NOT mapped
 * as JPA entities (900k rows through Hibernate is a self-inflicted wound).
 * Owns the Caffeine candle-series cache. (Roadmap §6, §12)
 */
package com.tsb.marketdata;
