package com.tsb.paper;

import com.tsb.marketdata.LivePriceFeed;
import com.tsb.marketdata.SymbolRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Every 2 seconds: has the live price reached any open limit order? If so,
 * fill it at its limit price.
 *
 * <p>It checks the last price, not the candle's high/low, so a spike shorter
 * than the polling interval can be missed. That errs on the side of NOT
 * filling, the same "assume the worse outcome" rule the backtester uses.
 */
@Component
public class PaperOrderMatcher {

    private static final Logger log = LoggerFactory.getLogger(PaperOrderMatcher.class);

    private final PaperTradingService service;
    private final LivePriceFeed prices;
    private final SymbolRepository symbols;

    public PaperOrderMatcher(PaperTradingService service, LivePriceFeed prices,
                             SymbolRepository symbols) {
        this.service = service;
        this.prices = prices;
        this.symbols = symbols;
    }

    @Scheduled(initialDelayString = "PT5S", fixedDelayString = "PT2S")
    public void match() {
        Map<String, LivePriceFeed.Quote> quotes = prices.freshQuotes();
        if (quotes.isEmpty()) {
            return;
        }
        Map<Long, String> tickers = new java.util.HashMap<>();
        symbols.findAll().forEach(s -> tickers.put(s.getId(), s.getTicker()));
        for (PaperOrder o : service.openOrders()) {
            LivePriceFeed.Quote q = quotes.get(tickers.get(o.getSymbolId()));
            if (q == null) {
                continue;
            }
            BigDecimal market = BigDecimal.valueOf(q.price());
            if (PaperTradingService.crossed(o, market)) {
                try {
                    service.fillLimit(o.getId(), o.getUserId(), market);
                } catch (Exception e) {
                    log.warn("could not fill paper order {}: {}", o.getId(), e.getMessage());
                }
            }
        }
    }
}
