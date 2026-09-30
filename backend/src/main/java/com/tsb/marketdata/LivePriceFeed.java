package com.tsb.marketdata;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
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

    /** Visible for tests and for the matcher's unit tests. */
    public void put(String ticker, double price, Instant at) {
        quotes.put(ticker, new Quote(price, at));
    }

    @Scheduled(initialDelayString = "PT3S", fixedDelayString = "PT2S")
    public void poll() {
        if (!enabled) {
            return;
        }
        for (String ticker : TICKERS) {
            try {
                double price = binance.fetchLastPrice(ticker);
                if (Double.isFinite(price) && price > 0) {
                    put(ticker, price, Instant.now());
                }
            } catch (Exception e) {
                // A blip leaves the old quote in place until it goes stale;
                // after MAX_AGE, market orders are refused rather than filled
                // at an old price.
                log.debug("price poll {} failed: {}", ticker, e.getMessage());
            }
        }
    }
}
