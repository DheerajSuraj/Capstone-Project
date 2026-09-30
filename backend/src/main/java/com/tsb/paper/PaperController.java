package com.tsb.paper;

import com.tsb.auth.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;

/**
 * /api/paper — the wallet, orders, cancel, reset. Every call acts on the
 * signed-in user's own account; there is no user id in any request.
 */
@RestController
@RequestMapping("/api/paper")
public class PaperController {

    private final PaperTradingService service;
    private final CurrentUser currentUser;

    public PaperController(PaperTradingService service, CurrentUser currentUser) {
        this.service = service;
        this.currentUser = currentUser;
    }

    @GetMapping("/account")
    public PaperTradingService.AccountView account() {
        return service.view(currentUser.requireId());
    }

    /**
     * Note what is NOT here: a price. A market order fills at the server's
     * live price; a limit order carries only the price it is willing to
     * accept.
     */
    public record OrderRequest(
            @NotBlank String symbol,
            @NotNull PaperOrder.Side side,
            @NotNull PaperOrder.Type type,
            BigDecimal qty,
            BigDecimal quoteAmount,
            BigDecimal limitPrice
    ) {
    }

    public record OrderResult(PaperTradingService.OrderView order,
                              PaperTradingService.AccountView account) {
    }

    @PostMapping("/orders")
    public OrderResult place(@RequestBody @Valid OrderRequest r) {
        long me = currentUser.requireId();
        PaperOrder o = service.place(me, new PaperTradingService.PlaceCommand(
                r.symbol(), r.side(), r.type(), r.qty(), r.quoteAmount(), r.limitPrice()));
        return new OrderResult(service.orderView(o), service.view(me));
    }

    /** Close a whole position at market — the ✕ on the chart and the Close button. */
    @PostMapping("/positions/{symbol}/close")
    public OrderResult close(@PathVariable String symbol) {
        long me = currentUser.requireId();
        PaperOrder o = service.close(me, symbol);
        return new OrderResult(service.orderView(o), service.view(me));
    }

    @PostMapping("/orders/{id}/cancel")
    public PaperTradingService.AccountView cancel(@PathVariable long id) {
        long me = currentUser.requireId();
        service.cancel(me, id);
        return service.view(me);
    }

    @PostMapping("/reset")
    public PaperTradingService.AccountView reset() {
        long me = currentUser.requireId();
        service.reset(me);
        return service.view(me);
    }
}
