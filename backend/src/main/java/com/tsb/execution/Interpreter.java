package com.tsb.execution;

import com.tsb.compiler.Expr;
import com.tsb.compiler.Registry;
import com.tsb.compiler.StrategyAst;
import com.tsb.marketdata.CandleSeries;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Phase 2 of the execution model: evaluates expressions at a single bar
 * index, in O(nodes) — every leaf is an array index or a constant, because
 * {@link IndicatorBank} already did all indicator maths.
 *
 * <p><b>No-look-ahead, layer three:</b> {@code CLOSE[k]} evaluates as
 * {@code close[i - k]}, and k is structurally non-negative (parser layer
 * one, AST constructor layer two). This interpreter cannot EXPRESS a read
 * of bar i+1. An out-of-range past read (i-k &lt; 0) yields NaN.
 *
 * <p><b>NaN is load-bearing:</b> Java defines every comparison with NaN as
 * false. So during indicator warm-up ({@code RSI(14) < 30} vs NaN) and
 * before lookback depth exists, conditions are false and strategies are
 * structurally inert — no special-case code, the IEEE-754 rules and the
 * NaN warm-up prefix compose into the safety property.
 *
 * <p>Per-bar let caching: each {@code let} is evaluated at most once per
 * bar regardless of how many times rules reference it, preserving the
 * O(nodes-per-bar) bound even for heavily reused lets.
 */
public final class Interpreter {

    private final CandleSeries series;
    private final Map<String, double[]> bank;
    private final Map<String, StrategyAst.LetDecl> lets = new HashMap<>();

    private int cachedBar = -1;
    private final Map<String, Double> numCache = new HashMap<>();
    private final Map<String, Boolean> boolCache = new HashMap<>();

    public Interpreter(List<StrategyAst.LetDecl> letDecls,
                       CandleSeries series, Map<String, double[]> bank) {
        this.series = series;
        this.bank = bank;
        for (StrategyAst.LetDecl let : letDecls) {
            lets.put(let.name(), let);
        }
    }

    // ── Boolean evaluation ──────────────────────────────────────────────

    /** Evaluates a condition at bar i. The analyzer guarantees the
     *  expression IS boolean-typed; anything else here is a compiler bug
     *  and throws loudly rather than mis-trading quietly. */
    public boolean bool(Expr e, int i) {
        return switch (e) {
            case Expr.Binary b -> switch (b.op()) {
                case AND -> bool(b.left(), i) && bool(b.right(), i);
                case OR -> bool(b.left(), i) || bool(b.right(), i);
                case LT -> num(b.left(), i) < num(b.right(), i);
                case GT -> num(b.left(), i) > num(b.right(), i);
                case LE -> num(b.left(), i) <= num(b.right(), i);
                case GE -> num(b.left(), i) >= num(b.right(), i);
                case EQ -> num(b.left(), i) == num(b.right(), i);
                case NEQ -> {
                    double l = num(b.left(), i);
                    double r = num(b.right(), i);
                    // NaN must NOT satisfy != (NaN != x is true in Java):
                    // unknown data answers every question with "false".
                    yield !Double.isNaN(l) && !Double.isNaN(r) && l != r;
                }
                default -> throw compilerBug(e);
            };
            case Expr.Unary u when u.op() == Expr.UnaryOp.NOT ->
                    !bool(u.operand(), i);
            case Expr.Call c -> boolCall(c, i);
            case Expr.VarRef v -> letBool(v.name(), i);
            default -> throw compilerBug(e);
        };
    }

    private boolean boolCall(Expr.Call c, int i) {
        return switch (c.name()) {
            // CROSSOVER: above now AND not above one bar ago — the read of
            // i-1 is why the registry charged crossovers one extra warm-up
            // bar at compile time. At i == 0 there is no previous bar: false.
            case "CROSSOVER" -> i > 0
                    && num(c.args().get(0), i) > num(c.args().get(1), i)
                    && num(c.args().get(0), i - 1) <= num(c.args().get(1), i - 1);
            case "CROSSUNDER" -> i > 0
                    && num(c.args().get(0), i) < num(c.args().get(1), i)
                    && num(c.args().get(0), i - 1) >= num(c.args().get(1), i - 1);
            default -> throw compilerBug(c);
        };
    }

    // ── Numeric evaluation ──────────────────────────────────────────────

    /** Evaluates a numeric expression at bar i. */
    public double num(Expr e, int i) {
        return switch (e) {
            case Expr.NumberLit n -> n.value();
            case Expr.PriceRef p -> IndicatorBank.column(series, p.field())[i];
            case Expr.VarRef v -> letNum(v.name(), i);
            case Expr.Call c -> numCall(c, i);
            case Expr.Lookback l -> {
                int j = i - l.offset();
                yield j < 0 ? Double.NaN : num(l.target(), j);
            }
            case Expr.Unary u when u.op() == Expr.UnaryOp.NEG ->
                    -num(u.operand(), i);
            case Expr.Binary b -> switch (b.op()) {
                case ADD -> num(b.left(), i) + num(b.right(), i);
                case SUB -> num(b.left(), i) - num(b.right(), i);
                case MUL -> num(b.left(), i) * num(b.right(), i);
                case DIV -> num(b.left(), i) / num(b.right(), i);
                default -> throw compilerBug(e);
            };
            default -> throw compilerBug(e);
        };
    }

    private double numCall(Expr.Call c, int i) {
        Optional<double[]> precomputed = IndicatorBank.instanceFor(c)
                .map(inst -> bank.get(inst.key()));
        if (precomputed.isPresent()) {
            double[] values = precomputed.orElseThrow();
            if (values == null) {
                throw new IllegalStateException("indicator missing from bank: "
                        + c.name() + " — manifest and rules disagree?");
            }
            return values[i];
        }
        return switch (c.name()) {
            case "MAX" -> Math.max(num(c.args().get(0), i), num(c.args().get(1), i));
            case "MIN" -> Math.min(num(c.args().get(0), i), num(c.args().get(1), i));
            case "ABS" -> Math.abs(num(c.args().get(0), i));
            default -> throw compilerBug(c);
        };
    }

    // ── Let caching ─────────────────────────────────────────────────────

    private double letNum(String name, int i) {
        touchBar(i);
        Double cached = numCache.get(name);
        if (cached != null) {
            return cached;
        }
        double value = num(letValue(name), i);
        numCache.put(name, value);
        return value;
    }

    private boolean letBool(String name, int i) {
        touchBar(i);
        Boolean cached = boolCache.get(name);
        if (cached != null) {
            return cached;
        }
        boolean value = bool(letValue(name), i);
        boolCache.put(name, value);
        return value;
    }

    private Expr letValue(String name) {
        StrategyAst.LetDecl let = lets.get(name);
        if (let == null) {
            throw new IllegalStateException("unknown let '" + name
                    + "' survived the analyzer");
        }
        return let.value();
    }

    /** Caches are per-bar; sequential walks (the engine) hit them, random
     *  access (the debugger, later) stays correct — just uncached. */
    private void touchBar(int i) {
        if (i != cachedBar) {
            cachedBar = i;
            numCache.clear();
            boolCache.clear();
        }
    }

    /** Whether a let holds a condition or a number — resolved structurally,
     *  mirroring the analyzer's typing. Used by callers that need to know
     *  how to evaluate a rule element. */
    public boolean isBoolExpr(Expr e) {
        return switch (e) {
            case Expr.Binary b -> switch (b.op()) {
                case LT, GT, LE, GE, EQ, NEQ, AND, OR -> true;
                default -> false;
            };
            case Expr.Unary u -> u.op() == Expr.UnaryOp.NOT;
            case Expr.Call c -> Registry.lookup(c.name())
                    .map(Registry.Signature::returnsBool).orElse(false);
            case Expr.VarRef v -> lets.containsKey(v.name())
                    && isBoolExpr(lets.get(v.name()).value());
            default -> false;
        };
    }

    private static IllegalStateException compilerBug(Expr e) {
        return new IllegalStateException(
                "expression should have been rejected by the analyzer: " + e
                        + " — this is a compiler bug, not a strategy bug");
    }
}