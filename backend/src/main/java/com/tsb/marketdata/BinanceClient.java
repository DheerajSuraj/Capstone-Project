package com.tsb.marketdata;

import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;

/**
 * The ONLY class in the platform that talks to Binance. Both data paths
 * live here so the network surface is one file:
 *
 * <ul>
 *   <li><b>Monthly archives</b> (data.binance.vision) — free static ZIPs,
 *       no API key, no rate limits worth mentioning. Used for the bulk
 *       historical backfill.</li>
 *   <li><b>Public REST klines</b> (api.binance.com/api/v3/klines) — no key
 *       needed for market data. Used by the incremental sync to fetch bars
 *       closed since the newest one we hold. Max 1000 bars per call, so the
 *       client pages.</li>
 * </ul>
 *
 * <p>Everything here runs OFFLINE relative to user requests: only the
 * backfill runner and the scheduled sync call it. The roadmap invariant —
 * nothing in the request path touches an external network — is preserved
 * because no controller can reach this class.
 */
@Component
public class BinanceClient {

    private static final String ARCHIVE_BASE =
            "https://data.binance.vision/data/spot/monthly/klines";
    private static final String API_BASE = "https://api.binance.com";
    private static final int PAGE_LIMIT = 1000;

    private final RestClient archiveClient;
    private final RestClient apiClient;

    public BinanceClient() {
        this.archiveClient = RestClient.create(ARCHIVE_BASE);
        this.apiClient = RestClient.create(API_BASE);
    }

    /** Visible-for-testing constructor: inject stub RestClients. */
    BinanceClient(RestClient archiveClient, RestClient apiClient) {
        this.archiveClient = archiveClient;
        this.apiClient = apiClient;
    }

    /** e.g. {@code BTCUSDT-1h-2024-01.zip} — also the ingestion_log key. */
    public static String archiveFileName(String ticker, String timeframe,
                                         YearMonth month) {
        return "%s-%s-%d-%02d.zip".formatted(
                ticker, timeframe, month.getYear(), month.getMonthValue());
    }

    /**
     * Downloads one monthly archive and parses it. Returns an empty list on
     * 404 — Binance simply has no file for months before a symbol listed,
     * which is normal during backfill, not an error.
     */
    public List<CandleBar> fetchMonthlyArchive(String ticker, String timeframe,
                                               YearMonth month) {
        String path = "/%s/%s/%s".formatted(ticker, timeframe,
                archiveFileName(ticker, timeframe, month));
        byte[] zip = archiveClient.get().uri(path)
                .exchange((request, response) -> {
                    if (response.getStatusCode().value() == 404) {
                        return null;
                    }
                    if (!response.getStatusCode().is2xxSuccessful()) {
                        throw new IllegalStateException("archive fetch failed: "
                                + response.getStatusCode() + " for " + path);
                    }
                    return response.getBody().readAllBytes();
                });
        return zip == null ? List.of()
                : BinanceCsvParser.parseZip(new java.io.ByteArrayInputStream(zip));
    }

    /**
     * Fetches klines from the public REST API, paging until {@code until}.
     * Binance returns each kline as a JSON array (times as numbers, prices
     * as strings); Spring's converter gives us List&lt;List&lt;Object&gt;&gt;
     * and we normalise. The FORMING bar is excluded by the caller via the
     * closed-bar cutoff — this method just fetches.
     */
    public List<CandleBar> fetchKlines(String ticker, String timeframe,
                                       Instant from, Instant until) {
        List<CandleBar> all = new ArrayList<>();
        Instant cursor = from;
        while (cursor.isBefore(until)) {
            // Lambdas may only capture effectively-final locals, and 'cursor'
            // advances each iteration — so snapshot it per page.
            final long startMillis = cursor.toEpochMilli();
            List<List<Object>> rows = apiClient.get()
                    .uri(uri -> uri.path("/api/v3/klines")
                            .queryParam("symbol", ticker)
                            .queryParam("interval", timeframe)
                            .queryParam("startTime", startMillis)
                            .queryParam("limit", PAGE_LIMIT)
                            .build())
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {
                    });
            if (rows == null || rows.isEmpty()) {
                break;
            }
            for (List<Object> row : rows) {
                CandleBar bar = new CandleBar(
                        BinanceCsvParser.toInstant(((Number) row.get(0)).longValue()),
                        Double.parseDouble(String.valueOf(row.get(1))),
                        Double.parseDouble(String.valueOf(row.get(2))),
                        Double.parseDouble(String.valueOf(row.get(3))),
                        Double.parseDouble(String.valueOf(row.get(4))),
                        Double.parseDouble(String.valueOf(row.get(5))));
                all.add(bar);
            }
            Instant last = all.get(all.size() - 1).openTime();
            if (!last.isAfter(cursor)) {
                break; // safety: no forward progress -> stop, never spin
            }
            cursor = last.plusMillis(1);
            if (rows.size() < PAGE_LIMIT) {
                break; // final page
            }
        }
        return all;
    }
}