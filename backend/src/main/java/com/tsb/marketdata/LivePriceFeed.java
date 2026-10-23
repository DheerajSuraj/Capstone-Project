package com.tsb.marketdata;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The last traded price of each symbol, kept in memory, refreshed from
 * Binance every 2 seconds in the background.
 *
 * <p>Paper trading fills orders at this price. Two rules follow from that:
 * <ul>
 *   <li><b>The browser never supplies a price.</b> A client that could say
 *       "I bought at 20,000" could print money. The server's own quote is
 *       the only price an order can fill at.</li>
 *   <li><b>No request waits on Binance.</b> Controllers read this map; only
 *       the scheduled poll talks to the network. That keeps the platform
 *       rule that nothing in the request path makes an outbound call, and it
 *       means a slow Binance day slows nobody's click.</li>
 * </ul>
 *
 * <p>A quote older than {@link #MAX_AGE} is treated as missing: filling at a
 * stale price is worse than refusing the order.
 */
@Component
public class LivePriceFeed {

    private static final Logger log = LoggerFactory.getLogger(LivePriceFeed.class);

    /** Same symbols the candle sync keeps. */
    public static final List<String> TICKERS = IngestionScheduler.TICKERS;

    public static final Duration MAX_AGE = Duration.ofSeconds(30);

    /** A price and when we saw it. */
    public record Quote(double price, Instant at) {
        public boolean freshAt(Instant now) {
            return Duration.between(at, now).compareTo(MAX_AGE) <= 0;
        }
    }

    private final BinanceClient binance;
    private final boolean enabled;
    private final Map<String, Quote> quotes = new ConcurrentHashMap<>();

    public LivePriceFeed(BinanceClient binance,
                         @Value("${tsb.live.enabled:true}") boolean enabled) {
        this.binance = binance;
        this.enabled = enabled;
    }

    /** The latest quote if it is fresh enough to trade on. */
    public Optional<Quote> fresh(String ticker) {
        Quote q = quotes.get(ticker);
        return q != null && q.freshAt(Instant.now()) ? Optional.of(q) : Optional.empty();
    }

    /** Every fresh quote, for the order matcher. */
    public Map<String, Quote> freshQuotes() {
        Instant now = Instant.now();
        Map<String, Quote> out = new java.util.HashMap<>();
        quotes.forEach((k, v) -> {
            if (v.freshAt(now)) {
                out.put(k, v);
            }
        });
        return out;
    }

    /**
     * The last quote ever seen, however old — for VALUING positions (a stale
     * price is far closer to the truth than the entry price), never for
     * filling an order.
     */
    public Optional<Quote> last(String ticker) {
        return Optional.ofNullable(quotes.get(ticker));
    }

    /** Visible for tests and for the matcher's unit tests. */
    public void put(String ticker, double price, Instant at) {
        quotes.put(ticker, new Quote(price, at));
    }

    /** No polling before this moment: Binance asked us to slow down. */
    private volatile Instant pausedUntil = Instant.EPOCH;
    /** When each ticker's failure was last logged, so a long outage logs
     *  once a minute instead of every 2 seconds. */
    private final Map<String, Instant> lastWarned = new ConcurrentHashMap<>();

    @Scheduled(initialDelayString = "PT3S", fixedDelayString = "PT2S")
    public void poll() {
        if (!enabled || Instant.now().isBefore(pausedUntil)) {
            return;
        }
        for (String ticker : TICKERS) {
            try {
                double price = binance.fetchLastPrice(ticker);
                if (Double.isFinite(price) && price > 0) {
                    put(ticker, price, Instant.now());
                }
            } catch (HttpStatusCodeException e) {
                int code = e.getStatusCode().value();
                if (code == 429 || code == 418) {
                    // Too many requests (418 = IP banned for ignoring 429s).
                    // Keep calling and the ban grows; back off as told.
                    long wait = retryAfterSeconds(e);
                    pausedUntil = Instant.now().plusSeconds(wait);
                    log.warn("Binance asked us to slow down (HTTP {}): pausing live prices "
                            + "for {} s. Paper orders need a fresh price and will be refused "
                            + "until then.", code, wait);
                    return;
                }
                warn(ticker, e);
            } catch (Exception e) {
                // A blip leaves the old quote in place until it goes stale;
                // after MAX_AGE, market orders are refused rather than filled
                // at an old price.
                warn(ticker, e);
            }
        }
    }

    private void warn(String ticker, Exception e) {
        Instant now = Instant.now();
        Instant before = lastWarned.get(ticker);
        if (before == null || Duration.between(before, now).toSeconds() >= 60) {
            lastWarned.put(ticker, now);
            log.warn("live price for {} unavailable: {} (paper orders on {} are refused "
                    + "once the last price is {} s old)", ticker, e.toString(), ticker,
                    MAX_AGE.toSeconds());
        }
    }

    private static long retryAfterSeconds(HttpStatusCodeException e) {
        try {
            String h = e.getResponseHeaders() == null ? null
                    : e.getResponseHeaders().getFirst("Retry-After");
            if (h != null) {
                return Math.max(5, Math.min(3600, Long.parseLong(h.trim())));
            }
        } catch (NumberFormatException ignored) {
            // fall through to the default
        }
        return 60;
    }
}
