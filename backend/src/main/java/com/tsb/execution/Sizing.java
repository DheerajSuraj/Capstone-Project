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

    /** How much to buy with this sizing, leaving room for the fee. */
    public static double desiredBuyQty(StrategyAst.Sizing sizing, double cash,
                                       double price, double fee) {
        double budget = switch (sizing) {
            case StrategyAst.Sizing.All ignored -> cash;
            case StrategyAst.Sizing.PercentOf p ->
                    cash * ((Expr.PercentLit) p.percent()).value() / 100.0;
            case StrategyAst.Sizing.Quantity q -> {
                double amount = ConstFold.fold(q.amount()).orElse(0.0);
                yield amount * price; // fixed base-asset qty -> its notional
            }
        };
        // Budget covers notional + fee: qty*price*(1+fee) <= budget.
        return budget / (price * (1 + fee));
    }

    /** How much of a held quantity to sell with this sizing. */
    public static double desiredSellQty(StrategyAst.Sizing sizing, double held) {
        return switch (sizing) {
            case StrategyAst.Sizing.All ignored -> held;
            case StrategyAst.Sizing.PercentOf p ->
                    held * ((Expr.PercentLit) p.percent()).value() / 100.0;
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
