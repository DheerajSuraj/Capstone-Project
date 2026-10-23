package com.tsb.execution;

import com.tsb.compiler.CompiledStrategy;
import com.tsb.compiler.Expr;
import com.tsb.compiler.LetHistory;
import com.tsb.compiler.Registry;
import com.tsb.compiler.StrategyAst;
import com.tsb.marketdata.CandleSeries;

import java.util.Arrays;
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
 * <p><b>Missing data is load-bearing:</b> during indicator warm-up
 * ({@code RSI(14) < 30} vs NaN), before lookback depth exists, and after a
 * division by zero, a comparison has no answer. Conditions are evaluated
 * with three values — true, false, unknown — and only a definite true
 * trades, so strategies are structurally inert until their data exists.
 * Unknown survives NOT (NOT of "can't tell" is still "can't tell"), which
 * plain Java booleans could not express: there, NOT turned a NaN
 * comparison's false into a buy.
 *
 * <p>Let caching: each {@code let} is evaluated at most once per bar,
 * however many times rules and other lets read it — at that bar or, through
 * a lookback like {@code m[3]}, at earlier ones. Each let keeps exactly as
 * many past values as anything reads back ({@link LetHistory}). (It used to
 * keep only the current bar, so {@code a2 = a1[1] + a1[2]},
 * {@code a3 = a2[1] + a2[2]}, ... re-evaluated everything below for every
 * read: twice the work per line, minutes per backtest at 20 lines.)
 */
public final class Interpreter {

    private final CandleSeries series;
    private final Map<String, double[]> bank;
    private final Map<String, StrategyAst.LetDecl> lets = new HashMap<>();
    /** How many past values each let keeps, from {@link LetHistory}. */
    private final Map<String, Integer> history;
    /** Each let's recent values, made on first use. */
    private final Map<String, Ring> rings = new HashMap<>();

    /** For a compiled strategy: its lets, sized by what its rules read. */
    public Interpreter(CompiledStrategy strategy, CandleSeries series,
                       Map<String, double[]> bank) {
        this(strategy.lets(), strategy.rules(), series, bank);
    }

    /** Lets only (tests): history comes from what the lets read of each
     *  other; a rule's own lookback on a let is then simply worked out
     *  again, which is correct, just not cached. */
    public Interpreter(List<StrategyAst.LetDecl> letDecls,
                       CandleSeries series, Map<String, double[]> bank) {
        this(letDecls, List.of(), series, bank);
    }

    public Interpreter(List<StrategyAst.LetDecl> letDecls,
                       List<StrategyAst.RuleDecl> rules,
                       CandleSeries series, Map<String, double[]> bank) {
        this.series = series;
        this.bank = bank;
        for (StrategyAst.LetDecl let : letDecls) {
            lets.put(let.name(), let);
        }
        this.history = LetHistory.of(letDecls, rules);
    }

    // ── Boolean evaluation ──────────────────────────────────────────────

    /** Three answers a condition can give. UNKNOWN is "the data isn't
     *  there": an indicator still warming up, a lookback before the first
     *  candle, a division by zero. */
    public static final byte FALSE = 0;
    public static final byte TRUE = 1;
    public static final byte UNKNOWN = 2;

    /** Evaluates a condition at bar i. The analyzer guarantees the
     *  expression IS boolean-typed; anything else here is a compiler bug
     *  and throws loudly rather than mis-trading quietly.
     *
     *  <p>Only a definite TRUE trades. Unknown answers false — and stays
     *  unknown through NOT, so {@code NOT (CLOSE > MACD_SIGNAL(12, 26, 9))}
     *  does not buy during the warm-up just because the inner part could
     *  not be answered yet. */
    public boolean bool(Expr e, int i) {
        return tri(e, i) == TRUE;
    }

    /**
     * The condition's three-valued answer (Kleene logic): AND is false if
     * any part is false, OR is true if any part is true, otherwise unknown
     * wins over the rest; NOT swaps true and false and keeps unknown.
     */
    public byte tri(Expr e, int i) {
        return switch (e) {
            case Expr.Binary b -> switch (b.op()) {
                case AND -> {
                    byte l = tri(b.left(), i);
                    if (l == FALSE) {
                        yield FALSE; // short-circuit, as before
                    }
                    byte r = tri(b.right(), i);
                    yield r == FALSE ? FALSE : (l == UNKNOWN || r == UNKNOWN) ? UNKNOWN : TRUE;
                }
                case OR -> {
                    byte l = tri(b.left(), i);
                    if (l == TRUE) {
                        yield TRUE;
                    }
                    byte r = tri(b.right(), i);
                    yield r == TRUE ? TRUE : (l == UNKNOWN || r == UNKNOWN) ? UNKNOWN : FALSE;
                }
                case LT, GT, LE, GE, EQ, NEQ -> {
                    double l = num(b.left(), i);
                    double r = num(b.right(), i);
                    // NaN (no data) and Infinity (a division by zero) have
                    // no honest answer to "is it bigger?".
                    if (!Double.isFinite(l) || !Double.isFinite(r)) {
                        yield UNKNOWN;
                    }
                    boolean v = switch (b.op()) {
                        case LT -> l < r;
                        case GT -> l > r;
                        case LE -> l <= r;
                        case GE -> l >= r;
                        case EQ -> l == r;
                        default -> l != r; // NEQ
                    };
                    yield v ? TRUE : FALSE;
                }
                default -> throw compilerBug(e);
            };
            case Expr.Unary u when u.op() == Expr.UnaryOp.NOT -> {
                byte t = tri(u.operand(), i);
                yield t == UNKNOWN ? UNKNOWN : t == TRUE ? FALSE : TRUE;
            }
            case Expr.Call c -> boolCall(c, i);
            case Expr.VarRef v -> letBool(v.name(), i);
            default -> throw compilerBug(e);
        };
    }

    private byte boolCall(Expr.Call c, int i) {
        return switch (c.name()) {
            // CROSSOVER: above now AND not above one bar ago — the read of
            // i-1 is why the registry charged crossovers one extra warm-up
            // bar at compile time. At i == 0 there is no previous bar: false.
            case "CROSSOVER", "CROSSUNDER" -> {
                if (i <= 0) {
                    yield FALSE;
                }
                double a = num(c.args().get(0), i);
                double b = num(c.args().get(1), i);
                double pa = num(c.args().get(0), i - 1);
                double pb = num(c.args().get(1), i - 1);
                if (!Double.isFinite(a) || !Double.isFinite(b)
                        || !Double.isFinite(pa) || !Double.isFinite(pb)) {
                    yield UNKNOWN;
                }
                boolean crossed = c.name().equals("CROSSOVER")
                        ? a > b && pa <= pb
                        : a < b && pa >= pb;
                yield crossed ? TRUE : FALSE;
            }
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
            // A bigger timeframe: already lined up per candle by the bank,
            // using only bigger candles that had closed (see Resampler).
            case Expr.On o -> {
                double[] values = bank.get(IndicatorBank.instanceFor(o).key());
                if (values == null) {
                    throw new IllegalStateException("indicator missing from bank: "
                            + IndicatorBank.instanceFor(o).key()
                            + " — manifest and rules disagree?");
                }
                yield values[i];
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

    /**
     * A let's values at its most recent bars: bar {@code b} lives in slot
     * {@code b % size}, and {@code bar[slot]} says which bar is really
     * there. Everything read at one bar lies within {@code size}
     * consecutive bars, so those reads never evict each other; the engine
     * walks forward, so last bar's values are still there for this one.
     * Random access (the debugger) stays correct — a slot holding another
     * bar is simply a miss.
     */
    private static final class Ring {
        final int[] bar;
        final double[] value;

        Ring(int size) {
            bar = new int[size];
            value = new double[size];
            Arrays.fill(bar, -1);
        }
    }

    private Ring ring(String name) {
        Ring r = rings.get(name);
        if (r == null) {
            long wanted = history.getOrDefault(name, 0) + 1L;
            r = new Ring((int) Math.max(1, Math.min(wanted, series.size())));
            rings.put(name, r);
        }
        return r;
    }

    private double letNum(String name, int i) {
        Ring r = ring(name);
        int slot = i % r.bar.length;
        if (r.bar[slot] == i) {
            return r.value[slot];
        }
        // A let only reads lets declared above it, so working this one out
        // never writes to its own ring.
        double value = num(letValue(name), i);
        r.bar[slot] = i;
        r.value[slot] = value;
        return value;
    }

    private byte letBool(String name, int i) {
        Ring r = ring(name);
        int slot = i % r.bar.length;
        if (r.bar[slot] == i) {
            return (byte) r.value[slot];
        }
        byte value = tri(letValue(name), i);
        r.bar[slot] = i;
        r.value[slot] = value;
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