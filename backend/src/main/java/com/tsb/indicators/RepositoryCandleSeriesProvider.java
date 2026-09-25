package com.tsb.indicators;

import com.tsb.marketdata.CandleRepository;
import com.tsb.marketdata.CandleSeries;
import com.tsb.marketdata.Symbol;
import com.tsb.marketdata.SymbolRepository;
import java.time.Instant;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Loads the candles the chart's indicators are computed over.
 *
 * <p>This deliberately mirrors {@code BacktestService.run} line for line —
 * ticker to symbol id, then {@code loadAll} or {@code loadBetween}. That is
 * the whole point of the feature: an indicator drawn on the chart must be
 * computed over exactly the rows a backtest would read, or the chart and the
 * engine can disagree about a number and the user has no way to tell which is
 * lying.
 *
 * <p>If the backtest's loading ever changes, change it here too. Better still,
 * extract the shared part — the duplication is small enough to live with today
 * and large enough to be worth noticing.
 */
@Component
public class RepositoryCandleSeriesProvider implements CandleSeriesProvider {

    private final SymbolRepository symbols;
    private final CandleRepository candles;

    public RepositoryCandleSeriesProvider(SymbolRepository symbols,
                                          CandleRepository candles) {
        this.symbols = symbols;
        this.candles = candles;
    }

    @Override
    public CandleSeries load(String symbol, String timeframe,
                             Instant from, Instant to) {
        Optional<Symbol> found = symbols.findByTicker(symbol);
        if (found.isEmpty()) {
            throw new IllegalArgumentException(
                    "No data for symbol '" + symbol + "'.");
        }
        long symbolId = found.get().getId();

        // loadAll is its own unbounded query rather than loadBetween with a
        // sentinel — see CandleRepository's note about far-future timestamps
        // overflowing Postgres's timestamptz range.
        if (from == null && to == null) {
            return candles.loadAll(symbolId, timeframe);
        }
        return candles.loadBetween(
                symbolId,
                timeframe,
                from == null ? Instant.EPOCH : from,
                to == null ? Instant.now() : to);
    }
}