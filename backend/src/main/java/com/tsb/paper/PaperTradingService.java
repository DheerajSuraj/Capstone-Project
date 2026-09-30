package com.tsb.paper;

import com.tsb.marketdata.LivePriceFeed;
import com.tsb.marketdata.Symbol;
import com.tsb.marketdata.SymbolRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Paper trading: fake money, real prices, long AND short — TradingView-style.
 *
 * <p><b>Positions.</b> One net position per symbol. BUY while flat opens a
 * long, SELL while flat opens a short; an order against the position closes
 * it (and flips it if bigger). The arithmetic is in {@link PaperMath}.
 *
 * <p><b>Prices.</b> Every fill uses {@link LivePriceFeed}, the server's own
 * copy of Binance's last price, refreshed every 2 seconds in the background.
 * A request never carries a price that gets used, and a price more than 30
 * seconds old is refused rather than traded on.
 *
 * <p><b>Orders.</b> Market orders fill immediately. A limit buy fills when
 * the price falls to it, a limit sell when it rises to it, at the limit price
 * ({@link PaperOrderMatcher}). A limit already better than the market fills
 * at once, at the market price.
 *
 * <p><b>Limits.</b> 1× leverage: the part of an order that opens or adds to
 * a position must fit the account's {@link PaperMath#buyingPower buying
 * power}. Closing is always allowed — a position can always be exited, even
 * below the exchange's minimum order size.
 *
 * <p><b>Consistency.</b> Every change to a wallet locks its row first
 * ({@code SELECT ... FOR UPDATE}), so a double click, or a click racing the
 * matcher, cannot use the same money twice.
 */
@Service
public class PaperTradingService {

    private final PaperAccountRepository accounts;
    private final PaperPositionRepository positions;
    private final PaperOrderRepository orders;
    private final SymbolRepository symbols;
    private final LivePriceFeed prices;

    public PaperTradingService(PaperAccountRepository accounts,
                               PaperPositionRepository positions,
                               PaperOrderRepository orders, SymbolRepository symbols,
                               LivePriceFeed prices) {
        this.accounts = accounts;
        this.positions = positions;
        this.orders = orders;
        this.symbols = symbols;
        this.prices = prices;
    }

    public record PlaceCommand(String symbol, PaperOrder.Side side, PaperOrder.Type type,
                               BigDecimal qty, BigDecimal quoteAmount, BigDecimal limitPrice) {
    }

    // ── Placing ─────────────────────────────────────────────────────────

    @Transactional
    public PaperOrder place(long userId, PlaceCommand c) {
        PaperAccount account = lockOrCreate(userId);
        Symbol symbol = tradable(c.symbol());
        BigDecimal market = livePrice(symbol.getTicker());
        boolean buy = c.side() == PaperOrder.Side.BUY;

        boolean limit = c.type() == PaperOrder.Type.LIMIT;
        BigDecimal limitPrice = null;
        if (limit) {
            if (c.limitPrice() == null || c.limitPrice().signum() <= 0) {
                throw bad("A limit order needs a limit price above zero.");
            }
            limitPrice = c.limitPrice().setScale(PaperMath.MONEY, RoundingMode.HALF_EVEN);
        }
        boolean marketable = !limit || (buy ? limitPrice.compareTo(market) >= 0
                : limitPrice.compareTo(market) <= 0);
        BigDecimal refPrice = marketable ? market : limitPrice;

        BigDecimal step = symbol.getStepSize();
        BigDecimal qty;
        if (c.qty() != null && c.qty().signum() > 0) {
            qty = PaperMath.roundQty(c.qty(), step);
        } else if (c.quoteAmount() != null && c.quoteAmount().signum() > 0) {
            qty = PaperMath.qtyForCash(c.quoteAmount(), refPrice, step);
        } else {
            throw bad("Enter an amount to trade.");
        }
        if (qty.signum() <= 0) {
            throw bad("That amount is smaller than the smallest tradable unit ("
                    + plain(step) + " " + symbol.getBaseAsset() + ").");
        }
        BigDecimal value = PaperMath.notional(qty, refPrice);
        if (value.compareTo(symbol.getMinNotional()) < 0) {
            throw bad("Orders must be worth at least " + plain(symbol.getMinNotional()) + " "
                    + symbol.getQuoteAsset() + ". " + symbol.getBaseAsset() + " is traded in steps of "
                    + plain(step) + ", so this came to " + money(value) + ".");
        }

        // Only the part that opens or grows a position needs buying power.
        PaperMath.Book book = book(userId, symbol.getId());
        BigDecimal opening = openingPart(book, buy, qty);
        if (opening.signum() > 0) {
            BigDecimal need = PaperMath.notional(opening, refPrice);
            need = need.add(PaperMath.fee(need));
            BigDecimal power = buyingPower(account, openOrders(userId), null);
            if (need.compareTo(power) > 0) {
                throw bad("Not enough buying power: this needs " + money(need) + " USDT and you have "
                        + money(power) + ". With 1× leverage, everything you hold — long and short — "
                        + "can be worth at most your account's equity.");
            }
        }

        PaperOrder order = orders.save(new PaperOrder(userId, symbol.getId(), c.side(), c.type(),
                qty, limitPrice));
        if (marketable) {
            fill(account, order, market, false);
        }
        return order;
    }

    /** Close a whole position at market: the ✕ on the chart and the table. */
    @Transactional
    public PaperOrder close(long userId, String ticker) {
        PaperAccount account = lockOrCreate(userId);
        Symbol symbol = tradable(ticker);
        PaperMath.Book book = book(userId, symbol.getId());
        if (book.flat()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "There is no open " + symbol.getTicker() + " position to close.");
        }
        BigDecimal market = livePrice(symbol.getTicker());
        PaperOrder order = orders.save(new PaperOrder(userId, symbol.getId(),
                book.side() == PaperMath.Side.LONG ? PaperOrder.Side.SELL : PaperOrder.Side.BUY,
                PaperOrder.Type.MARKET, book.qty(), null));
        fill(account, order, market, false);
        return order;
    }

    // ── Filling ─────────────────────────────────────────────────────────

    /** Applies a fill to the wallet and the position. The account is already locked. */
    private void fill(PaperAccount account, PaperOrder order, BigDecimal price, boolean recheckPower) {
        Long userId = account.getUserId();
        boolean buy = order.side() == PaperOrder.Side.BUY;
        Optional<PaperPosition> pos = positions.findByUserIdAndSymbolId(userId, order.getSymbolId());
        PaperMath.Book before = pos.map(PaperPosition::book).orElse(PaperMath.Book.FLAT);

        if (recheckPower) {
            BigDecimal opening = openingPart(before, buy, order.getQty());
            if (opening.signum() > 0) {
                BigDecimal need = PaperMath.notional(opening, price);
                need = need.add(PaperMath.fee(need));
                if (need.compareTo(buyingPower(account, openOrders(userId), order.getId())) > 0) {
                    order.rejected("Not enough buying power when the price was reached.");
                    orders.save(order);
                    return;
                }
            }
        }

        PaperMath.Fill f = PaperMath.fill(before, buy, order.getQty(), price);
        account.apply(f.cashDelta(), f.fee(), f.realizedPnl());
        if (f.after().flat()) {
            pos.ifPresent(positions::delete);
        } else if (pos.isPresent()) {
            pos.get().set(f.after());
            positions.save(pos.get());
        } else {
            positions.save(new PaperPosition(userId, order.getSymbolId(), f.after()));
        }
        order.filled(price, f.fee(), f.closedQty().signum() > 0 ? f.realizedPnl() : null);
        orders.save(order);
        accounts.save(account);
    }

    /**
     * Called by the matcher when a live price has reached an open limit
     * order. Locks the wallet FIRST, then reads the order: a cancel also
     * takes this lock, so what we read is the order's settled state.
     */
    @Transactional
    public void fillLimit(long orderId, long userId, BigDecimal marketPrice) {
        PaperAccount account = accounts.lock(userId).orElse(null);
        PaperOrder order = orders.findById(orderId).orElse(null);
        if (account == null || order == null || !order.getUserId().equals(userId)
                || order.status() != PaperOrder.Status.OPEN || !crossed(order, marketPrice)) {
            return;
        }
        // At the limit: the price the trader asked for, not a luckier one.
        fill(account, order, order.getLimitPrice(), true);
    }

    static boolean crossed(PaperOrder o, BigDecimal market) {
        return o.side() == PaperOrder.Side.BUY
                ? market.compareTo(o.getLimitPrice()) <= 0
                : market.compareTo(o.getLimitPrice()) >= 0;
    }

    public List<PaperOrder> openOrders() {
        return orders.findByStatus(PaperOrder.Status.OPEN.name());
    }

    // ── Cancel & reset ──────────────────────────────────────────────────

    @Transactional
    public void cancel(long userId, long orderId) {
        lockOrCreate(userId);
        PaperOrder order = orders.findById(orderId)
                .filter(o -> o.getUserId().equals(userId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such order."));
        if (order.status() != PaperOrder.Status.OPEN) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "That order is already " + order.status().name().toLowerCase(Locale.ROOT) + ".");
        }
        order.cancelled();
        orders.save(order);
    }

    /** Start again with the starting cash: cancel open orders, drop positions. */
    @Transactional
    public void reset(long userId) {
        PaperAccount account = lockOrCreate(userId);
        for (PaperOrder o : openOrders(userId)) {
            o.cancelled();
            orders.save(o);
        }
        positions.deleteByUserId(userId);
        account.reset();
        accounts.save(account);
    }

    // ── Reading ─────────────────────────────────────────────────────────

    public record PositionView(String symbol, String baseAsset, String side, BigDecimal qty,
                               BigDecimal avgPrice, BigDecimal entryFees, Double price,
                               double value, double unrealizedPnl, double unrealizedPct,
                               boolean priceLive) {
    }

    public record OrderView(long id, String symbol, String side, String type,
                            BigDecimal qty, BigDecimal limitPrice, String status,
                            BigDecimal fillPrice, BigDecimal fee, BigDecimal realizedPnl,
                            String rejectReason, String createdAt, String filledAt) {
    }

    /**
     * @param buyingPower what more can be opened now (1× leverage)
     * @param reserved    promised to open limit orders
     */
    public record AccountView(BigDecimal startingCash, BigDecimal cash, double equity,
                              double buyingPower, double reserved, BigDecimal realizedPnl,
                              double unrealizedPnl, double totalPnl, double returnPct,
                              BigDecimal feesPaid, int resets, String resetAt,
                              List<PositionView> positions, List<OrderView> openOrders,
                              List<OrderView> history, Map<String, Double> prices,
                              String serverTime) {
    }

    @Transactional
    public AccountView view(long userId) {
        PaperAccount a = accounts.findById(userId)
                .orElseGet(() -> accounts.save(new PaperAccount(userId, PaperMath.STARTING_CASH)));
        Map<Long, Symbol> bySymbol = symbolsById();
        Map<String, Double> live = new TreeMap<>();
        prices.freshQuotes().forEach((k, q) -> live.put(k, q.price()));

        List<PositionView> pos = new ArrayList<>();
        BigDecimal signed = BigDecimal.ZERO;
        BigDecimal unrealized = BigDecimal.ZERO;
        for (PaperPosition p : positions.findByUserIdOrderBySymbolIdAsc(userId)) {
            Symbol s = bySymbol.get(p.getSymbolId());
            PaperMath.Book b = p.book();
            Double price = s == null ? null : live.get(s.getTicker());
            // No live price: value it at its entry price, and say so, rather than guess.
            BigDecimal mark = price != null ? BigDecimal.valueOf(price) : b.avgPrice();
            BigDecimal pnl = PaperMath.unrealized(b, mark);
            signed = signed.add(PaperMath.signedValue(b, mark));
            unrealized = unrealized.add(pnl);
            BigDecimal entryValue = PaperMath.notional(b.qty(), b.avgPrice());
            pos.add(new PositionView(s == null ? "?" : s.getTicker(),
                    s == null ? "?" : s.getBaseAsset(), b.side().name(), b.qty(), b.avgPrice(),
                    b.entryFees(), price, PaperMath.notional(b.qty(), mark).doubleValue(),
                    pnl.doubleValue(),
                    entryValue.signum() == 0 ? 0 : pnl.doubleValue() / entryValue.doubleValue() * 100,
                    price != null));
        }

        List<PaperOrder> open = openOrders(userId);
        BigDecimal equity = a.getCash().add(signed);
        BigDecimal reserved = reserved(open, null);
        BigDecimal power = PaperMath.buyingPower(equity, gross(userId, bySymbol, live), reserved);
        List<OrderView> history = orders
                .findByUserIdAndCreatedAtGreaterThanEqualOrderByCreatedAtDesc(userId,
                        a.getResetAt(), PageRequest.of(0, 50))
                .stream().filter(o -> o.status() != PaperOrder.Status.OPEN)
                .map(o -> orderView(o, bySymbol)).toList();

        double start = a.getStartingCash().doubleValue();
        return new AccountView(a.getStartingCash(), a.getCash(), equity.doubleValue(),
                power.doubleValue(), reserved.doubleValue(), a.getRealizedPnl(),
                unrealized.doubleValue(), equity.doubleValue() - start,
                (equity.doubleValue() / start - 1) * 100, a.getFeesPaid(), a.getResets(),
                a.getResetAt().toString(), pos,
                open.stream().map(o -> orderView(o, bySymbol)).toList(), history, live,
                java.time.Instant.now().toString());
    }

    public OrderView orderView(PaperOrder o) {
        return orderView(o, symbolsById());
    }

    private static OrderView orderView(PaperOrder o, Map<Long, Symbol> bySymbol) {
        Symbol s = bySymbol.get(o.getSymbolId());
        return new OrderView(o.getId(), s == null ? "?" : s.getTicker(), o.side().name(),
                o.type().name(), o.getQty(), o.getLimitPrice(), o.status().name(),
                o.getFillPrice(), o.getFee(), o.getRealizedPnl(), o.getRejectReason(),
                o.getCreatedAt().toString(),
                o.getFilledAt() == null ? null : o.getFilledAt().toString());
    }

    // ── Buying power ────────────────────────────────────────────────────

    /** Buying power now, optionally ignoring one order's own reservation. */
    private BigDecimal buyingPower(PaperAccount a, List<PaperOrder> open, Long excludeOrder) {
        Map<Long, Symbol> bySymbol = symbolsById();
        Map<String, Double> live = new TreeMap<>();
        prices.freshQuotes().forEach((k, q) -> live.put(k, q.price()));
        BigDecimal signed = BigDecimal.ZERO;
        for (PaperPosition p : positions.findByUserIdOrderBySymbolIdAsc(a.getUserId())) {
            signed = signed.add(PaperMath.signedValue(p.book(), mark(p, bySymbol, live)));
        }
        BigDecimal equity = a.getCash().add(signed);
        return PaperMath.buyingPower(equity, gross(a.getUserId(), bySymbol, live),
                reserved(open, excludeOrder));
    }

    /** Everything held, long and short, at today's price. */
    private BigDecimal gross(long userId, Map<Long, Symbol> bySymbol, Map<String, Double> live) {
        BigDecimal g = BigDecimal.ZERO;
        for (PaperPosition p : positions.findByUserIdOrderBySymbolIdAsc(userId)) {
            g = g.add(PaperMath.notional(p.book().qty(), mark(p, bySymbol, live)));
        }
        return g;
    }

    private static BigDecimal mark(PaperPosition p, Map<Long, Symbol> bySymbol, Map<String, Double> live) {
        Symbol s = bySymbol.get(p.getSymbolId());
        Double price = s == null ? null : live.get(s.getTicker());
        return price != null ? BigDecimal.valueOf(price) : p.book().avgPrice();
    }

    /** What open limit orders have promised (value + fee at their limit). */
    static BigDecimal reserved(List<PaperOrder> open, Long exclude) {
        BigDecimal r = BigDecimal.ZERO;
        for (PaperOrder o : open) {
            if (o.getLimitPrice() != null && !o.getId().equals(exclude)) {
                BigDecimal v = PaperMath.notional(o.getQty(), o.getLimitPrice());
                r = r.add(v).add(PaperMath.fee(v));
            }
        }
        return r;
    }

    /** How much of an order opens or grows a position (the rest closes one). */
    static BigDecimal openingPart(PaperMath.Book book, boolean buy, BigDecimal qty) {
        PaperMath.Side side = buy ? PaperMath.Side.LONG : PaperMath.Side.SHORT;
        if (book.flat() || book.side() == side) {
            return qty;
        }
        return qty.subtract(book.qty()).max(BigDecimal.ZERO);
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    private PaperMath.Book book(long userId, long symbolId) {
        return positions.findByUserIdAndSymbolId(userId, symbolId).map(PaperPosition::book)
                .orElse(PaperMath.Book.FLAT);
    }

    private List<PaperOrder> openOrders(long userId) {
        return orders.findByUserIdAndStatusOrderByCreatedAtDesc(userId, PaperOrder.Status.OPEN.name());
    }

    private Map<Long, Symbol> symbolsById() {
        return symbols.findAll().stream().collect(Collectors.toMap(Symbol::getId, Function.identity()));
    }

    private PaperAccount lockOrCreate(long userId) {
        return accounts.lock(userId).orElseGet(() -> {
            accounts.saveAndFlush(new PaperAccount(userId, PaperMath.STARTING_CASH));
            return accounts.lock(userId).orElseThrow();
        });
    }

    private Symbol tradable(String ticker) {
        if (ticker == null || !LivePriceFeed.TICKERS.contains(ticker)) {
            throw bad("Paper trading supports " + String.join(", ", LivePriceFeed.TICKERS) + ".");
        }
        return symbols.findByTicker(ticker).orElseThrow(() -> bad("Unknown symbol " + ticker + "."));
    }

    private BigDecimal livePrice(String ticker) {
        return prices.fresh(ticker).map(q -> BigDecimal.valueOf(q.price()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                        "No live price for " + ticker + " right now, so nothing was traded. "
                                + "Try again in a few seconds."));
    }

    private static String money(BigDecimal v) {
        return v.setScale(2, RoundingMode.HALF_EVEN).toPlainString();
    }

    private static String plain(BigDecimal v) {
        return v.stripTrailingZeros().toPlainString();
    }

    private static ResponseStatusException bad(String m) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, m);
    }
}
