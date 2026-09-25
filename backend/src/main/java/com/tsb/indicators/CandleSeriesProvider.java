package com.tsb.indicators;

import com.tsb.marketdata.CandleSeries;
import java.time.Instant;

/**
 * The one thing this feature needs from the market data layer.
 *
 * <p>It exists as an interface because the chart must read the SAME candles
 * the backtester reads — closed rows out of our own database, never the live
 * stream. Naming that requirement in a type makes it hard to accidentally wire
 * a different source in later.
 *
 * <p><b>You implement this</b>, in one small class that delegates to whatever
 * your candle repository is actually called. Find it with:
 *
 * <pre>
 * Get-ChildItem -Recurse -Filter "*Repository.java" src\main\java\com\tsb\marketdata
 * </pre>
 *
 * and then:
 *
 * <pre>
 * &#64;Component
 * class RepositoryCandleSeriesProvider implements CandleSeriesProvider {
 *     private final CandleRepository candles;
 *
 *     RepositoryCandleSeriesProvider(CandleRepository candles) {
 *         this.candles = candles;
 *     }
 *
 *     &#64;Override
 *     public CandleSeries load(String symbol, String timeframe,
 *                              Instant from, Instant to) {
 *         return candles.loadSeries(symbol, timeframe, from, to);
 *     }
 * }
 * </pre>
 *
 * If the backtest endpoint already builds a {@code CandleSeries} somewhere,
 * call the same method it calls — that is the point.
 */
public interface CandleSeriesProvider {

    /**
     * @param from inclusive lower bound, or null for "as far back as we have"
     * @param to   inclusive upper bound, or null for "up to the newest closed
     *             candle"
     */
    CandleSeries load(String symbol, String timeframe, Instant from, Instant to);
}