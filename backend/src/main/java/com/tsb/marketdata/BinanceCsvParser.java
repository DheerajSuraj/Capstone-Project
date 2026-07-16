package com.tsb.marketdata;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Parses Binance's kline CSV archives (the file inside each monthly ZIP
 * from data.binance.vision).
 *
 * <p>Columns: open_time, open, high, low, close, volume, close_time,
 * quote_volume, trade_count, taker_buy_base, taker_buy_quote, ignore.
 * We keep the first six; the rest are irrelevant to OHLCV backtesting.
 *
 * <p><b>The timestamp trap:</b> archives dated before 2025 use
 * MILLISECONDS since epoch; from 2025-01-01 Binance switched the files to
 * MICROSECONDS. A parser that assumes one or the other silently produces
 * dates in the year 56,000 or 1970. We detect by magnitude — every real
 * candle timestamp in ms is < 1e14 for centuries to come, while any µs
 * value is > 1e15 — and normalise to Instant. This is exactly the kind of
 * upstream quirk the validation-first design exists to survive.
 *
 * <p>Pure static functions of an InputStream: trivially unit-testable with
 * no network, which is how the tests do it.
 */
public final class BinanceCsvParser {

    /** Reads the single CSV entry inside a Binance monthly ZIP. */
    public static List<CandleBar> parseZip(InputStream zipStream) {
        try (ZipInputStream zip = new ZipInputStream(zipStream)) {
            ZipEntry entry = zip.getNextEntry();
            if (entry == null) {
                throw new IllegalStateException("empty ZIP archive");
            }
            return parseCsv(zip);
        } catch (IOException e) {
            throw new UncheckedIOException("failed reading kline ZIP", e);
        }
    }

    /** Parses raw kline CSV lines into validated bars. */
    public static List<CandleBar> parseCsv(InputStream csvStream) {
        List<CandleBar> bars = new ArrayList<>();
        BufferedReader reader = new BufferedReader(
                new InputStreamReader(csvStream, StandardCharsets.US_ASCII));
        try {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                // Some archives ship a header row; skip anything whose first
                // field is not a number.
                if (!Character.isDigit(line.charAt(0))) {
                    continue;
                }
                bars.add(parseLine(line));
            }
        } catch (IOException e) {
            throw new UncheckedIOException("failed reading kline CSV", e);
        }
        return bars;
    }

    static CandleBar parseLine(String line) {
        String[] f = line.split(",", 8);
        if (f.length < 6) {
            throw new IllegalArgumentException("malformed kline row: " + line);
        }
        return new CandleBar(
                toInstant(Long.parseLong(f[0])),
                Double.parseDouble(f[1]),
                Double.parseDouble(f[2]),
                Double.parseDouble(f[3]),
                Double.parseDouble(f[4]),
                Double.parseDouble(f[5]));
    }

    /**
     * Normalises a Binance timestamp to an Instant regardless of unit.
     * ms timestamps stay < 1e14 until the year 5138; µs timestamps are
     * > 1e15 from 2001 onward. The gap between makes detection unambiguous.
     */
    static Instant toInstant(long raw) {
        return raw > 1_000_000_000_000_000L      // definitely microseconds
                ? Instant.ofEpochMilli(raw / 1_000)
                : Instant.ofEpochMilli(raw);
    }

    private BinanceCsvParser() {
    }
}