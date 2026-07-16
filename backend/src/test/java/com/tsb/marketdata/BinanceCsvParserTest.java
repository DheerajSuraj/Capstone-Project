package com.tsb.marketdata;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;

@DisplayName("BinanceCsvParser")
class BinanceCsvParserTest {

    // A real-shaped kline row (ms timestamps): 2024-01-01T00:00Z.
    private static final String ROW_MS =
            "1704067200000,42283.58,42554.57,42261.02,42475.23,1271.68,"
                    + "1704070799999,53870235.4,45123,612.3,25937112.1,0";

    // The same instant expressed in MICROSECONDS (2025+ archive format).
    private static final String ROW_US =
            "1704067200000000,42283.58,42554.57,42261.02,42475.23,1271.68,"
                    + "1704070799999999,53870235.4,45123,612.3,25937112.1,0";

    @Test
    @DisplayName("parses a millisecond-timestamp row")
    void millisecondRow() {
        CandleBar bar = BinanceCsvParser.parseLine(ROW_MS);
        assertEquals(Instant.parse("2024-01-01T00:00:00Z"), bar.openTime());
        assertEquals(42283.58, bar.open());
        assertEquals(42554.57, bar.high());
        assertEquals(42261.02, bar.low());
        assertEquals(42475.23, bar.close());
        assertEquals(1271.68, bar.volume());
    }

    @Test
    @DisplayName("THE TIMESTAMP TRAP: microsecond rows (2025+ archives) normalise to the same instant")
    void microsecondRow() {
        CandleBar ms = BinanceCsvParser.parseLine(ROW_MS);
        CandleBar us = BinanceCsvParser.parseLine(ROW_US);
        assertEquals(ms.openTime(), us.openTime(),
                "ms and µs encodings of the same bar must parse identically");
    }

    @Test
    @DisplayName("unit detection boundary behaves")
    void unitDetection() {
        // Plain ms value stays ms.
        assertEquals(Instant.ofEpochMilli(1_704_067_200_000L),
                BinanceCsvParser.toInstant(1_704_067_200_000L));
        // µs value is divided down.
        assertEquals(Instant.ofEpochMilli(1_704_067_200_000L),
                BinanceCsvParser.toInstant(1_704_067_200_000_000L));
    }

    @Test
    @DisplayName("skips headers and blank lines")
    void skipsNoise() {
        String csv = "open_time,open,high,low,close,volume\n\n" + ROW_MS + "\n";
        List<CandleBar> bars = BinanceCsvParser.parseCsv(
                new ByteArrayInputStream(csv.getBytes(StandardCharsets.US_ASCII)));
        assertEquals(1, bars.size());
    }

    @Test
    @DisplayName("reads the CSV inside a ZIP (the archive shape)")
    void readsZip() throws IOException {
        ByteArrayOutputStream zipBytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(zipBytes)) {
            zip.putNextEntry(new ZipEntry("BTCUSDT-1h-2024-01.csv"));
            zip.write((ROW_MS + "\n").getBytes(StandardCharsets.US_ASCII));
            zip.closeEntry();
        }
        List<CandleBar> bars = BinanceCsvParser.parseZip(
                new ByteArrayInputStream(zipBytes.toByteArray()));
        assertEquals(1, bars.size());
        assertEquals(42475.23, bars.get(0).close());
    }
}