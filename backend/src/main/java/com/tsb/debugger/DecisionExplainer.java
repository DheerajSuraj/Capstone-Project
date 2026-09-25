package com.tsb.debugger;

import com.tsb.compiler.AstPrinter;
import com.tsb.compiler.CompiledStrategy;
import com.tsb.compiler.Expr;
import com.tsb.compiler.StrategyAst;
import com.tsb.execution.BacktestResult;
import com.tsb.execution.Backtester;
import com.tsb.execution.BarObserver;
import com.tsb.execution.ExchangeRules;
import com.tsb.execution.IndicatorBank;
import com.tsb.execution.Interpreter;
import com.tsb.marketdata.CandleSeries;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Module 3, the decision debugger: "why did (or didn't) it trade at this
 * bar?"
 *
 * <p><b>It never decides anything itself.</b> Every true/false in an
 * explanation is the {@link Interpreter}'s answer for that piece of the
 * condition, and every "filled / ignored / rejected" is what the
 * {@link Backtester} reported through a {@link BarObserver} while running.
 * The debugger only walks the condition, asks for each piece, and collects
 * the numbers. If it had its own logic, it could be wrong in ways the
 * backtest is not — this way, the only thing it can get wrong is the
 * wording.
 *
 * <p><b>It walks the whole tree.</b> The interpreter stops at the first false
 * part of an AND (that is how {@code &&} works). The debugger asks about every
 * part, so it can say "two of the three were false", not just "the first one
 * was".
 *
 * <p><b>Same bars, same values.</b> A debugger session is built from the same
 * compiled strategy and the same candles as the backtest (see
 * {@code BacktestService.prepare}). Recursive indicators like EMA and RSI
 * depend on where the series starts, so explaining a bar with a different
 * date range could show numbers the backtest never saw.
 *
 * <p>One instance per request. It runs the backtest once, then explains any
 * number of bars from that run.
 */
public final class DecisionExplainer {

    private final CompiledStrategy strategy;
    private final CandleSeries series;
    private final Interpreter interp;
    private final Map<String, StrategyAst.LetDecl> lets = new HashMap<>();
    private final RunRecorder recorder;
    private final BacktestResult result;
    private final int firstBar;
    /** False while measuring for statistics: the numbers, without the sentences. */
    private boolean withNotes = true;
    /** AstPrinter output per node — the same expressions are printed every bar. */
    private final Map<Expr, String> texts = new IdentityHashMap<>();

    public DecisionExplainer(CompiledStrategy strategy, CandleSeries series,
                             ExchangeRules rules) {
        this.strategy = strategy;
        this.series = series;
        this.recorder = new RunRecorder(strategy, series.size());
        this.result = new Backtester().run(strategy, series, rules, recorder);
        // Our own interpreter over the same bank: random access by bar is
        // correct (its let cache re-anchors per bar), just uncached.
        this.interp = new Interpreter(strategy.lets(), series,
                IndicatorBank.compute(strategy.indicators(), series));
        for (StrategyAst.LetDecl let : strategy.lets()) {
            lets.put(let.name(), let);
        }
        this.firstBar = Math.min(strategy.warmupBars(), series.size());
    }

    public BacktestResult result() {
        return result;
    }

    public RunRecorder recorder() {
        return recorder;
    }

    public CompiledStrategy strategy() {
        return strategy;
    }

    public CandleSeries series() {
        return series;
    }

    /** The index of the bar whose open time is {@code time}, or the bar that
     *  contains it; empty when it is outside the series. */
    public Optional<Integer> barAt(long timeMillis) {
        long[] t = series.openTimeMillis();
        if (t.length == 0 || timeMillis < t[0]) {
            return Optional.empty();
        }
        int lo = 0;
        int hi = t.length - 1;
        while (lo < hi) { // last index with t[idx] <= time
            int mid = (lo + hi + 1) >>> 1;
            if (t[mid] <= timeMillis) {
                lo = mid;
            } else {
                hi = mid - 1;
            }
        }
        return Optional.of(lo);
    }

    // ── One bar ─────────────────────────────────────────────────────────

    public Explanation.Bar explain(int bar) {
        if (bar < 0 || bar >= series.size()) {
            throw new IllegalArgumentException("bar " + bar + " outside 0.."
                    + (series.size() - 1));
        }
        boolean warmup = bar < firstBar;
        boolean lastBar = bar == series.size() - 1;
        Boolean inPosition = recorder.evaluated(bar) ? recorder.inPosition(bar) : null;

        List<Explanation.Statement> statements = new ArrayList<>();
        List<StrategyAst.RuleDecl> rules = strategy.rules();
        for (int r = 0; r < rules.size(); r++) {
            List<StrategyAst.IfStmt> body = rules.get(r).body();
            for (int k = 0; k < body.size(); k++) {
                statements.add(explainStatement(r, k, bar, warmup, lastBar));
            }
        }

        StringBuilder summary = new StringBuilder();
        if (warmup) {
            summary.append("This bar is inside the warm-up (the first ")
                    .append(strategy.warmupBars())
                    .append(" bars), so the rules were not checked yet: the "
                            + "indicators don't have enough history to be "
                            + "trusted.");
        } else {
            summary.append(Boolean.TRUE.equals(inPosition)
                    ? "A position was open at this close."
                    : "No position was open at this close.");
            for (Explanation.Statement s : statements) {
                summary.append(' ').append(s.sentence());
            }
        }

        return new Explanation.Bar(bar, series.openTimeMillis()[bar],
                series.open()[bar], series.high()[bar], series.low()[bar],
                series.close()[bar], strategy.warmupBars(), warmup, lastBar,
                inPosition, List.copyOf(statements), summary.toString());
    }

    private Explanation.Statement explainStatement(int r, int k, int bar,
                                                   boolean warmup,
                                                   boolean lastBar) {
        StrategyAst.RuleDecl rule = strategy.rules().get(r);
        StrategyAst.IfStmt stmt = rule.body().get(k);
        ConditionNode condition = explainCondition(stmt.condition(), bar);

        Explanation.Branch taken = condition.passed()
                ? Explanation.Branch.THEN
                : stmt.elseAction().isPresent()
                        ? Explanation.Branch.ELSE
                        : Explanation.Branch.NONE;
        StrategyAst.Action action = switch (taken) {
            case THEN -> stmt.thenAction();
            case ELSE -> stmt.elseAction().orElseThrow();
            case NONE -> null;
        };

        Explanation.Outcome outcome;
        Double fillPrice = null;
        Long fillTime = null;
        if (warmup) {
            outcome = Explanation.Outcome.WARMUP;
        } else if (action == null) {
            outcome = Explanation.Outcome.NO_ACTION;
        } else if (action instanceof StrategyAst.Action.Set) {
            outcome = Explanation.Outcome.SETTING_APPLIED;
        } else if (lastBar) {
            outcome = Explanation.Outcome.NOT_FILLED_LAST_BAR;
        } else {
            BarObserver.FillOutcome reported = recorder.outcome(r, k, bar)
                    .orElseThrow(() -> new IllegalStateException(
                            "engine and debugger disagree: the condition at bar "
                                    + bar + " asked for " + printAction(action)
                                    + " but the engine placed no order"));
            outcome = switch (reported) {
                case FILLED -> Explanation.Outcome.FILLED;
                case IGNORED_ALREADY_LONG -> Explanation.Outcome.IGNORED_ALREADY_LONG;
                case IGNORED_NOTHING_TO_SELL -> Explanation.Outcome.IGNORED_NOTHING_TO_SELL;
                case REJECTED_TOO_SMALL -> Explanation.Outcome.REJECTED_TOO_SMALL;
            };
            if (outcome == Explanation.Outcome.FILLED) {
                fillPrice = series.open()[bar + 1];
                fillTime = series.openTimeMillis()[bar + 1];
            }
        }

        Explanation.ClosestChange closest = closestChange(condition);
        String thenText = printAction(stmt.thenAction());
        String elseText = stmt.elseAction().map(DecisionExplainer::printAction)
                .orElse(null);
        String sentence = sentence(rule.name(), condition, taken,
                action == null ? thenText : printAction(action), outcome,
                fillPrice, closest);

        return new Explanation.Statement(r, rule.name(), k, stmt.span(),
                condition, taken, thenText, elseText, outcome, fillPrice,
                fillTime, closest, sentence);
    }

    // ── The condition tree ──────────────────────────────────────────────

    /**
     * Same tree as {@link #explainCondition}, without the explanatory
     * sentences — for the statistics, which walk every bar and never show
     * them. Verdicts and distances are identical.
     */
    public ConditionNode measureCondition(Expr e, int bar) {
        withNotes = false;
        try {
            return explainCondition(e, bar);
        } finally {
            withNotes = true;
        }
    }

    /** Evaluates every part of {@code e} at {@code bar}, without stopping
     *  early. */
    public ConditionNode explainCondition(Expr e, int bar) {
        return switch (e) {
            case Expr.Binary b when b.op() == Expr.BinaryOp.AND
                    || b.op() == Expr.BinaryOp.OR -> junction(b, bar);
            case Expr.Binary b -> compare(b, bar);
            case Expr.Unary u when u.op() == Expr.UnaryOp.NOT -> {
                ConditionNode child = explainCondition(u.operand(), bar);
                boolean passed = interp.bool(e, bar);
                yield new ConditionNode(ConditionNode.Kind.NOT, text(e), e.span(),
                        passed, !passed && child.unknown(), null, null, null,
                        null, null, null, null,
                        passed ? "The inner condition was false, so NOT made it true."
                                : "The inner condition was true, so NOT made it false.",
                        List.of(child));
            }
            case Expr.Call c when c.name().equals("CROSSOVER") -> cross(c, bar, true);
            case Expr.Call c when c.name().equals("CROSSUNDER") -> cross(c, bar, false);
            case Expr.VarRef v -> {
                ConditionNode body = explainCondition(lets.get(v.name()).value(), bar);
                boolean passed = interp.bool(e, bar);
                yield new ConditionNode(ConditionNode.Kind.LET, v.name(), e.span(),
                        passed, body.unknown(), null, null, null, null, null,
                        null, null, "Named condition '" + v.name() + "' was "
                        + (passed ? "true." : "false."), List.of(body));
            }
            default -> throw new IllegalStateException(
                    "not a condition — the analyzer should have rejected: " + text(e));
        };
    }

    private ConditionNode junction(Expr.Binary b, int bar) {
        List<Expr> parts = new ArrayList<>();
        flatten(b, b.op(), parts);
        List<ConditionNode> children = new ArrayList<>(parts.size());
        int trueCount = 0;
        boolean anyUnknown = false;
        for (Expr part : parts) {
            ConditionNode child = explainCondition(part, bar);
            children.add(child);
            if (child.passed()) {
                trueCount++;
            }
            anyUnknown |= child.unknown();
        }
        boolean passed = interp.bool(b, bar);
        boolean and = b.op() == Expr.BinaryOp.AND;
        int n = parts.size();
        String note;
        if (and) {
            note = passed ? "All " + n + " parts were true."
                    : (n - trueCount) + " of " + n + " parts were false (all must be true).";
        } else {
            note = passed ? trueCount + " of " + n + " parts were true (one is enough)."
                    : "None of the " + n + " parts were true.";
        }
        return new ConditionNode(and ? ConditionNode.Kind.AND : ConditionNode.Kind.OR,
                text(b), b.span(), passed, !passed && anyUnknown, null, null,
                null, null, null, null, null, note, List.copyOf(children));
    }

    /** a AND b AND c parses as ((a AND b) AND c); show it as one node. */
    private static void flatten(Expr e, Expr.BinaryOp op, List<Expr> out) {
        if (e instanceof Expr.Binary b && b.op() == op) {
            flatten(b.left(), op, out);
            flatten(b.right(), op, out);
        } else {
            out.add(e);
        }
    }

    private ConditionNode compare(Expr.Binary b, int bar) {
        double l = interp.num(b.left(), bar);
        double r = interp.num(b.right(), bar);
        boolean passed = interp.bool(b, bar);
        boolean unknown = Double.isNaN(l) || Double.isNaN(r);
        String op = symbol(b.op());

        Double distance = null;
        Double relative = null;
        String note;
        if (!unknown) {
            distance = Math.abs(l - r);
            relative = relative(distance, l, r);
        }
        String leftText = text(b.left());
        if (!withNotes) {
            note = null;
        } else if (unknown) {
            note = undefinedNote(Double.isNaN(l) ? leftText : text(b.right()));
        } else {
            double d = distance;
            String rightText = b.right() instanceof Expr.NumberLit
                    ? fmt(r) : text(b.right()) + " (" + fmt(r) + ")";
            String word = opWord(b.op());
            note = passed
                    ? leftText + " = " + fmt(l) + ", " + word + " " + rightText
                            + marginText(b.op(), d, relative)
                    : leftText + " = " + fmt(l) + ", needed to be " + word + " "
                            + rightText + ": off by " + fmt(d) + pct(relative) + ".";
        }
        return new ConditionNode(ConditionNode.Kind.COMPARE, text(b), b.span(),
                passed, unknown, finite(l), finite(r), op, null, null,
                distance, relative, note, List.of());
    }

    private static String marginText(Expr.BinaryOp op, double d, Double rel) {
        return op == Expr.BinaryOp.EQ ? "."
                : " by " + fmt(d) + pct(rel) + ".";
    }

    private ConditionNode cross(Expr.Call c, int bar, boolean over) {
        Expr a = c.args().get(0);
        Expr b = c.args().get(1);
        double l = interp.num(a, bar);
        double r = interp.num(b, bar);
        double pl = bar > 0 ? interp.num(a, bar - 1) : Double.NaN;
        double pr = bar > 0 ? interp.num(b, bar - 1) : Double.NaN;
        boolean passed = interp.bool(c, bar);
        boolean unknown = Double.isNaN(l) || Double.isNaN(r)
                || Double.isNaN(pl) || Double.isNaN(pr);

        String an = text(a);
        String bn = text(b);
        String dir = over ? "above" : "below";
        String opposite = over ? "below" : "above";
        // "now on the far side" for a crossover means above; for a crossunder, below.
        boolean farSideNow = over ? l > r : l < r;
        boolean farSideBefore = over ? pl > pr : pl < pr;

        Double distance = null;
        Double relative = null;
        String note;
        if (unknown) {
            note = bar == 0
                    ? "No previous bar to compare with, so no crossing can be seen."
                    : undefinedNote(an + " or " + bn);
        } else if (passed) {
            distance = Math.abs(l - r);
            relative = relative(distance, l, r);
            note = !withNotes ? null
                    : an + " crossed " + dir + " " + bn + " on this bar ("
                    + fmt(pl) + " vs " + fmt(pr) + " before, " + fmt(l) + " vs "
                    + fmt(r) + " now).";
        } else if (farSideNow) {
            // Already on the far side at the previous bar too: a crossing
            // only counts on the bar it happens. No single move NOW flips it.
            note = an + " was already " + dir + " " + bn + " on the previous bar;"
                    + " a crossing only counts on the bar where it happens.";
        } else if (farSideBefore) {
            note = an + " moved " + opposite + " " + bn + " on this bar, the "
                    + "opposite of a cross " + dir + ".";
        } else {
            distance = Math.abs(l - r);
            relative = relative(distance, l, r);
            note = !withNotes ? null
                    : an + " = " + fmt(l) + " stayed " + opposite + " " + bn + " = "
                    + fmt(r) + ": it needed to move " + fmt(distance)
                    + pct(relative) + " to cross.";
        }
        return new ConditionNode(over ? ConditionNode.Kind.CROSSOVER
                : ConditionNode.Kind.CROSSUNDER, text(c), c.span(), passed, unknown,
                finite(l), finite(r), null, finite(pl), finite(pr), distance,
                relative, note, List.of());
    }

    // ── Counterfactual: the single closest change ───────────────────────

    /**
     * Tries flipping each leaf on its own, holding the rest where they were,
     * and keeps the flips that change the whole condition's answer. Of those,
     * the smallest relative move wins.
     */
    public static Explanation.ClosestChange closestChange(ConditionNode root) {
        if (structural(root, null) != root.passed()) {
            // Cannot happen if the interpreter is the one semantics — but if
            // it ever did, a counterfactual built on the wrong tree would lie.
            return null;
        }
        List<ConditionNode> leaves = new ArrayList<>();
        collectLeaves(root, leaves);
        ConditionNode best = null;
        for (ConditionNode leaf : leaves) {
            if (leaf.unknown() || leaf.distance() == null) {
                continue;
            }
            if (structural(root, leaf) == root.passed()) {
                continue; // flipping this one alone changes nothing
            }
            if (best == null || closer(leaf, best)) {
                best = leaf;
            }
        }
        if (best == null) {
            return null;
        }
        String note = best.note() == null ? null
                : root.passed() ? "Closest to NOT firing: " + best.note()
                : "Closest to firing: " + best.note();
        return new Explanation.ClosestChange(best.text(), best.span(),
                best.distance(), best.relativeDistance(), note);
    }

    private static boolean closer(ConditionNode a, ConditionNode b) {
        if (a.relativeDistance() == null) {
            return false;
        }
        return b.relativeDistance() == null
                || a.relativeDistance() < b.relativeDistance();
    }

    /** Whether inverting this one leaf, alone, changes the whole answer. */
    public static boolean flips(ConditionNode root, ConditionNode leaf) {
        return structural(root, leaf) != root.passed();
    }

    /** The tree's answer from its leaves, with {@code flipped} inverted. */
    static boolean structural(ConditionNode n, ConditionNode flipped) {
        if (n == flipped) {
            return !n.passed();
        }
        return switch (n.kind()) {
            case AND -> n.children().stream().allMatch(c -> structural(c, flipped));
            case OR -> n.children().stream().anyMatch(c -> structural(c, flipped));
            case NOT -> !structural(n.children().get(0), flipped);
            case LET -> structural(n.children().get(0), flipped);
            case COMPARE, CROSSOVER, CROSSUNDER -> n.passed();
        };
    }

    /** Leaves in a fixed (pre-order) order — the same order every bar. */
    public static void collectLeaves(ConditionNode n, List<ConditionNode> out) {
        if (n.isLeaf()) {
            out.add(n);
        } else {
            for (ConditionNode c : n.children()) {
                collectLeaves(c, out);
            }
        }
    }

    // ── Wording ─────────────────────────────────────────────────────────

    private static String sentence(String ruleName, ConditionNode cond,
                                   Explanation.Branch taken, String action,
                                   Explanation.Outcome outcome, Double fillPrice,
                                   Explanation.ClosestChange closest) {
        String where = "Rule '" + ruleName + "'";
        String because = taken == Explanation.Branch.ELSE
                ? " (condition false, so ELSE)" : "";
        return switch (outcome) {
            case WARMUP -> where + ": not checked (warm-up).";
            case NO_ACTION -> where + ": no " + action + ", the condition was false."
                    + (closest != null ? " " + closest.note()
                    : cond.unknown() ? " Part of it was still undefined at this bar."
                    : " No single change would have been enough: " + cond.note());
            case FILLED -> where + ": " + action + because
                    + " at this close, filled at the next open (" + fmt(fillPrice) + ").";
            case IGNORED_ALREADY_LONG -> where + ": asked to " + action + because
                    + ", but a position was already open, so it was ignored "
                    + "(one position at a time).";
            case IGNORED_NOTHING_TO_SELL -> where + ": asked to " + action + because
                    + ", but there was no position to sell.";
            case REJECTED_TOO_SMALL -> where + ": asked to " + action + because
                    + ", but the order was below the exchange's minimum size, so "
                    + "it was skipped.";
            case NOT_FILLED_LAST_BAR -> where + ": asked to " + action + because
                    + ", but this is the last bar, so there is no next open to "
                    + "fill at.";
            case SETTING_APPLIED -> where + ": " + action + because + " took effect.";
        };
    }

    static String printAction(StrategyAst.Action a) {
        return switch (a) {
            case StrategyAst.Action.Buy b -> "BUY " + printSizing(b.sizing());
            case StrategyAst.Action.Sell s -> "SELL " + printSizing(s.sizing());
            case StrategyAst.Action.Set s -> "SET " + s.target() + " = "
                    + AstPrinter.print(s.value());
        };
    }

    private static String printSizing(StrategyAst.Sizing s) {
        return switch (s) {
            case StrategyAst.Sizing.All ignored -> "ALL";
            case StrategyAst.Sizing.Quantity q -> "qty = " + AstPrinter.print(q.amount());
            case StrategyAst.Sizing.PercentOf p -> "qty = " + AstPrinter.print(p.percent())
                    + " OF " + p.base();
        };
    }

    /** TSL as a person would write it — see {@link ConditionText}. */
    private String text(Expr e) {
        return texts.computeIfAbsent(e, ConditionText::print);
    }

    private static String undefinedNote(String what) {
        return "Not enough history yet: " + what + " has no value at this bar, "
                + "so this cannot be true.";
    }

    private static String symbol(Expr.BinaryOp op) {
        return switch (op) {
            case LT -> "<";
            case GT -> ">";
            case LE -> "<=";
            case GE -> ">=";
            case EQ -> "==";
            case NEQ -> "!=";
            default -> throw new IllegalStateException("not a comparison: " + op);
        };
    }

    private static String opWord(Expr.BinaryOp op) {
        return switch (op) {
            case LT -> "below";
            case GT -> "above";
            case LE -> "at or below";
            case GE -> "at or above";
            case EQ -> "equal to";
            case NEQ -> "different from";
            default -> throw new IllegalStateException("not a comparison: " + op);
        };
    }

    /** Distance as a share of the larger side; null when both sides are 0
     *  and they differ (no meaningful scale). */
    static Double relative(double d, double l, double r) {
        double scale = Math.max(Math.abs(l), Math.abs(r));
        if (scale == 0) {
            return d == 0 ? 0.0 : null;
        }
        return d / scale;
    }

    private static Double finite(double v) {
        return Double.isFinite(v) ? v : null;
    }

    static String fmt(Double v) {
        if (v == null || !Double.isFinite(v)) {
            return "—";
        }
        if (v == 0) {
            return "0";
        }
        return new BigDecimal(v).round(new MathContext(6)).stripTrailingZeros()
                .toPlainString();
    }

    private static String pct(Double rel) {
        return rel == null ? "" : String.format(Locale.ROOT, " (%.2f%%)", rel * 100);
    }
}
