package com.tsb.marketdata;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Arrays;

/**
 * GET /api/candles — OHLCV for the chart, as COLUMNAR JSON: six parallel
 * arrays ({@code {t,o,h,l,c,v}}), index i across all six is one bar. Same
 * shape as the internal {@link CandleSeries}, and ~3x smaller on the wire
 * than an array of per-bar objects; the chart converts either way.
 *
 * <p>Hard-capped at {@value #MAX_BARS} bars, keeping the MOST RECENT when
 * a range exceeds it: full two-year coverage for 1h/4h; 5m charts its most
 * recent ~69 days. A documented MVP tradeoff — proper windowed paging is a
 * V2 item.
 */
@RestController
@RequestMapping("/api/candles")
public class CandleController {

    static final int MAX_BARS = 20_000;

    private final SymbolRepository symbols;
    private final CandleRepository candles;

    public CandleController(SymbolRepository symbols, CandleRepository candles) {
        this.symbols = symbols;
        this.candles = candles;
    }

    public record CandleColumns(
            long[] t, double[] o, double[] h, double[] l, double[] c, double[] v
    ) {
    }

    @GetMapping
    public CandleColumns get(@RequestParam String symbol,
                             @RequestParam String timeframe,
                             @RequestParam(required = false) String from,
                             @RequestParam(required = false) String to) {
        Symbol sym = symbols.findByTicker(symbol).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "unknown symbol " + symbol));
        Instant fromT;
        Instant toT;
        try {
            fromT = from == null ? Instant.EPOCH : Instant.parse(from);
            toT = to == null ? Instant.now() : Instant.parse(to);
        } catch (DateTimeParseException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "from/to must be ISO-8601 instants");
        }

        CandleSeries s = candles.loadBetween(sym.getId(), timeframe, fromT, toT);
        int n = s.size();
        if (n <= MAX_BARS) {
            return new CandleColumns(s.openTimeMillis(), s.open(), s.high(),
                    s.low(), s.close(), s.volume());
        }
        int start = n - MAX_BARS; // keep the most recent window
        return new CandleColumns(
                Arrays.copyOfRange(s.openTimeMillis(), start, n),
                Arrays.copyOfRange(s.open(), start, n),
                Arrays.copyOfRange(s.high(), start, n),
                Arrays.copyOfRange(s.low(), start, n),
                Arrays.copyOfRange(s.close(), start, n),
                Arrays.copyOfRange(s.volume(), start, n));
    }
}