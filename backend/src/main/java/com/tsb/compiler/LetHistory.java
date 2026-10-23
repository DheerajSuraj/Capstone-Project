package com.tsb.compiler;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * How far back each named value (let) is read: the largest lookback at
 * which any rule, or any let declared after it, reads it — added up through
 * chains of lets. In
 * <pre>
 *   let m = MACD_LINE(12, 26)
 *   let rising = m > m[3]
 *   rule r { IF rising[1] THEN BUY ALL }
 * </pre>
 * {@code rising} is read one candle back and {@code m} up to four
 * (1 + 3).
 *
 * <p>The interpreter keeps exactly that many past values of each let, so a
 * let read at several past candles is worked out once per candle instead of
 * again for every read. Without that, {@code let a2 = a1[1] + a1[2]},
 * {@code let a3 = a2[1] + a2[2]}, ... doubled the work with every let: 20
 * such lines took minutes per backtest, 30 would never finish.
 */
public final class LetHistory {

    private LetHistory() {
    }

    /**
     * Lookback per let; lets that nothing reads are absent.
     *
     * @param lets  in declaration order (a let only reads lets above it)
     * @param rules the rules whose conditions read the lets
     */
    public static Map<String, Integer> of(List<StrategyAst.LetDecl> lets,
                                          List<StrategyAst.RuleDecl> rules) {
        Map<String, Integer> history = new HashMap<>();
        for (StrategyAst.RuleDecl rule : rules) {
            for (StrategyAst.IfStmt stmt : rule.body()) {
                reads(stmt.condition(), 0, history);
            }
        }
        // Walk backwards: every reader of a let is declared below it, so a
        // let's own history is complete before its reads are added.
        for (int k = lets.size() - 1; k >= 0; k--) {
            StrategyAst.LetDecl let = lets.get(k);
            Integer own = history.get(let.name());
            if (own != null) {
                reads(let.value(), own, history);
            }
        }
        return history;
    }

    /** Values kept in total: each let keeps its lookback plus the current one. */
    public static long slots(Map<String, Integer> history) {
        long total = 0;
        for (int h : history.values()) {
            total += h + 1L;
        }
        return total;
    }

    private static void reads(Expr e, int base, Map<String, Integer> history) {
        switch (e) {
            case Expr.VarRef v -> history.merge(v.name(), base, Math::max);
            case Expr.Lookback l -> reads(l.target(),
                    (int) Math.min(Integer.MAX_VALUE, (long) base + l.offset()), history);
            case Expr.Binary b -> {
                reads(b.left(), base, history);
                reads(b.right(), base, history);
            }
            case Expr.Unary u -> reads(u.operand(), base, history);
            case Expr.Call c -> {
                // A crossover compares this candle with the one before, so
                // it reads its sides one candle further back too.
                boolean cross = c.name().equals("CROSSOVER") || c.name().equals("CROSSUNDER");
                int at = cross ? (int) Math.min(Integer.MAX_VALUE, base + 1L) : base;
                for (Expr arg : c.args()) {
                    reads(arg, at, history);
                }
            }
            case Expr.On o -> reads(o.target(), base, history);
            case Expr.NumberLit ignored -> {
            }
            case Expr.PercentLit ignored -> {
            }
            case Expr.TimeframeLit ignored -> {
            }
            case Expr.StringLit ignored -> {
            }
            case Expr.PriceRef ignored -> {
            }
        }
    }
}
