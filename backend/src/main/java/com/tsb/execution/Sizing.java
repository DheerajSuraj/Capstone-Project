package com.tsb.execution;

import com.tsb.compiler.ConstFold;
import com.tsb.compiler.Expr;
import com.tsb.compiler.StrategyAst;

import java.time.Instant;

/**
 * Order sizing and fee arithmetic, shared by the {@link Backtester} and the
 * competition engine so a BUY of "25% OF EQUITY" means exactly the same
 * quantity in both. (Moved out of Backtester unchanged.)
 */
public final class Sizing {

    private Sizing() {
    }

    /**
     * How much to buy with this sizing.
     *
     * <p>{@code BUY ALL} and {@code BUY qty = 25% OF EQUITY} spend a budget
     * and leave room for the fee inside it. {@code BUY qty = 0.5} asks for
     * exactly 0.5 of the coin — the fee is paid on top, as on an exchange —
     * and the engine checks it can be afforded ({@link #affordable}).
     * Buying happens only when flat, so equity is cash at that moment.
     */
    public static double desiredBuyQty(StrategyAst.Sizing sizing, double cash,
                                       double price, double fee) {
        return switch (sizing) {
            case StrategyAst.Sizing.All ignored -> cash / (price * (1 + fee));
            case StrategyAst.Sizing.PercentOf p ->
                    cash * ((Expr.PercentLit) p.percent()).value() / 100.0
                            / (price * (1 + fee));
            case StrategyAst.Sizing.Quantity q -> ConstFold.fold(q.amount()).orElse(0.0);
        };
    }

    /** Whether {@code qty} at {@code price}, plus the fee, fits in the cash.
     *  A spot exchange refuses an order it can't cover; the engines do too
     *  (before this, a fixed {@code BUY qty = 2} on 10,000 USDT bought
     *  120,000 USDT of BTC and left cash at minus 110,000). */
    public static boolean affordable(double qty, double cash, double price, double fee) {
        return qty * price * (1 + fee) <= cash * (1 + 1e-12);
    }

    /**
     * How much of a held quantity to sell with this sizing.
     *
     * @param equity what the account is worth at {@code price} (cash plus
     *               the position) — for {@code SELL qty = 25% OF EQUITY},
     *               which sells coins worth a quarter of the account
     */
    public static double desiredSellQty(StrategyAst.Sizing sizing, double held,
                                        double equity, double price) {
        return switch (sizing) {
            case StrategyAst.Sizing.All ignored -> held;
            case StrategyAst.Sizing.PercentOf p -> {
                double pct = ((Expr.PercentLit) p.percent()).value() / 100.0;
                yield p.base() == StrategyAst.Sizing.Base.POSITION
                        ? held * pct
                        : Math.min(held, equity * pct / price);
            }
            case StrategyAst.Sizing.Quantity q ->
                    ConstFold.fold(q.amount()).orElse(0.0);
        };
    }

    /** Cash received for selling, after the fee. */
    public static double exitProceeds(double qty, double price, double fee) {
        double notional = qty * price;
        return notional - notional * fee;
    }

    /** A closed round trip: PnL is net of the entry and exit fees. */
    public static Trade trade(int entryBar, int exitBar, long entryTime,
                              long exitTime, double qty, double entryPrice,
                              double exitPrice, double entryFees, double fee,
                              Trade.ExitReason reason) {
        double exitFee = qty * exitPrice * fee;
        double pnl = qty * (exitPrice - entryPrice) - entryFees - exitFee;
        return new Trade(entryBar, exitBar,
                Instant.ofEpochMilli(entryTime), Instant.ofEpochMilli(exitTime),
                qty, entryPrice, exitPrice, entryFees + exitFee, pnl, reason);
    }
}
