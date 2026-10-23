package com.tsb.debugger;

import com.tsb.compiler.AstPrinter;
import com.tsb.compiler.CompiledStrategy;
import com.tsb.compiler.Expr;
import com.tsb.compiler.StrategyAst;
import com.tsb.compiler.Timeframe;
import com.tsb.execution.BacktestResult;
import com.tsb.execution.Backtester;
import com.tsb.execution.Benchmark;
import com.tsb.execution.BarObserver;
import com.tsb.execution.ExchangeRules;
import com.tsb.execution.IndicatorBank;
import com.tsb.execution.Interpreter;
import com.tsb.execution.Trade;
import com.tsb.marketdata.CandleSeries;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.math.MathContext;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

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
    /** The interpreter's indicator arrays — also says which bigger candle
     *  each small candle could see, for {@code ... ON 4h}. */
    private final Map<String, double[]> bank;
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
        this.bank = IndicatorBank.compute(strategy, series);
        this.interp = new Interpreter(strategy, series, bank);
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

        double close = series.close()[bar];
        Double change = bar > 0 && series.close()[bar - 1] != 0
                ? (close / series.close()[bar - 1] - 1) * 100 : null;
        return new Explanation.Bar(bar, series.openTimeMillis()[bar],
                series.open()[bar], series.high()[bar], series.low()[bar],
                close, strategy.warmupBars(), warmup, lastBar,
                inPosition, List.copyOf(statements), summary.toString(),
                series.volume()[bar], change, book(bar), fills(bar));
    }

    // ── The account at one close ────────────────────────────────────────

    /**
     * Cash, coins and net asset value at this close, straight from the run's
     * own curves, plus what the open position and the protective orders
     * looked like. Nothing here is re-simulated.
     */
    Explanation.Book book(int bar) {
        double[] equity = result.equityCurve();
        double nav = equity[bar];
        double cash = result.cashCurve()[bar];
        double qty = result.positionCurve()[bar];
        double close = series.close()[bar];
        double value = qty * close;

        Trade open = null;   // the position held at this close, if any
        double realized = 0;
        int closed = 0;
        for (Trade t : result.trades()) {
            if (t.exitBar() <= bar) {
                realized += t.pnl();
                closed++;
            } else if (qty > 0 && t.entryBar() <= bar) {
                open = t;
            }
        }
        double peak = 0;
        for (int i = 0; i <= bar; i++) {
            peak = Math.max(peak, equity[i]);
        }

        Double entry = open == null ? null : open.entryPrice();
        Double stop = null;
        Double target = null;
        Double trail = null;
        if (entry != null && recorder.evaluated(bar)) {
            double s = recorder.stopPct(bar);
            double tp = recorder.takeProfitPct(bar);
            double tr = recorder.trailingPct(bar);
            stop = s > 0 ? entry * (1 - s / 100) : null;
            target = tp > 0 ? entry * (1 + tp / 100) : null;
            trail = tr > 0 ? recorder.peakSinceEntry(bar) * (1 - tr / 100) : null;
        }
        double capital = result.initialCapital();
        return new Explanation.Book(capital, nav, cash, qty, value,
                nav > 0 ? value / nav * 100 : 0,
                entry, open == null ? null : open.entryTime().toEpochMilli(),
                entry == null ? null : qty * (close - entry),
                entry == null || entry == 0 ? null : (close / entry - 1) * 100,
                peak, peak > 0 ? (peak - nav) / peak * 100 : 0,
                (nav / capital - 1) * 100,
                Benchmark.returnPctAt(series, strategy.warmupBars(), strategy.feePercent(), bar),
                realized, closed, stop, target, trail);
    }

    /**
     * Every buy and sell during this candle, in the order they happened:
     * orders from the last close fill at the open (sells first, as the engine
     * runs them), then a stop or target inside the candle, then — on the last
     * candle — the position valued out at the close.
     */
    List<Explanation.Fill> fills(int bar) {
        double fee = strategy.feePercent();
        double open = series.open()[bar];
        List<Explanation.Fill> atOpen = new ArrayList<>();
        List<Explanation.Fill> buys = new ArrayList<>();
        List<Explanation.Fill> later = new ArrayList<>();
        double bought = 0;
        double boughtAt = 0;
        for (Trade t : result.trades()) {
            if (t.entryBar() == bar) {
                // A position sold in parts is several trades with one entry.
                bought += t.qty();
                boughtAt = t.entryPrice();
            }
            if (t.exitBar() != bar) {
                continue;
            }
            Explanation.Fill f = new Explanation.Fill("SELL",
                    switch (t.exitReason()) {
                        case SIGNAL -> "OPEN";
                        case END_OF_DATA -> "CLOSE";
                        default -> t.exitPrice() == open ? "OPEN" : "INSIDE";
                    },
                    t.exitPrice(), t.qty(), t.qty() * t.exitPrice() * fee,
                    t.exitReason().name(), t.pnl());
            if (t.exitReason() == Trade.ExitReason.SIGNAL) {
                atOpen.add(f);
            } else {
                later.add(f);
            }
        }
        if (bought > 0) {
            buys.add(new Explanation.Fill("BUY", "OPEN", boughtAt, bought,
                    bought * boughtAt * fee, "SIGNAL", null));
        }
        List<Explanation.Fill> all = new ArrayList<>(atOpen);
        all.addAll(buys);
        // A stop on the very candle the position opened comes after the buy.
        later.sort(java.util.Comparator.comparing(f -> f.when().equals("CLOSE")));
        all.addAll(later);
        return List.copyOf(all);
    }

    private Explanation.Statement explainStatement(int r, int k, int bar,
                                                   boolean warmup,
                                                   boolean lastBar) {
        StrategyAst.RuleDecl rule = strategy.rules().get(r);
        StrategyAst.IfStmt stmt = rule.body().get(k);
        ConditionNode condition = explainCondition(stmt.condition(), bar);

        // As in the engine: ELSE only on a definite false, never on no answer.
        Explanation.Branch taken = condition.passed()
                ? Explanation.Branch.THEN
                : !condition.unknown() && stmt.elseAction().isPresent()
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
                case REJECTED_NOT_ENOUGH_CASH -> Explanation.Outcome.REJECTED_NOT_ENOUGH_CASH;
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

    /** Lets already drawn in full in the tree being built: a let used
     *  twice is drawn once, so {@code let b = a AND a, c = b AND b, ...}
     *  cannot blow the tree (and the response) up exponentially. */
    private final Set<String> shownLets = new HashSet<>();

    /** Evaluates every part of {@code e} at {@code bar}, without stopping
     *  early. */
    public ConditionNode explainCondition(Expr e, int bar) {
        shownLets.clear();
        treeDepth = 0;
        return node(e, bar);
    }

    /**
     * How deep the drawn tree may go before a named condition is shown by
     * its answer alone. A chain of lets can nest hundreds of levels (each
     * adds two), and a reply that deep is more than the JSON writer will
     * nest; one expression on its own nests at most 100 (the parser's limit).
     */
    static final int MAX_TREE_DEPTH = 100;
    private int treeDepth;

    private ConditionNode node(Expr e, int bar) {
        treeDepth++;
        try {
            return nodeAt(e, bar);
        } finally {
            treeDepth--;
        }
    }

    private ConditionNode nodeAt(Expr e, int bar) {
        return switch (e) {
            case Expr.Binary b when b.op() == Expr.BinaryOp.AND
                    || b.op() == Expr.BinaryOp.OR -> junction(b, bar);
            case Expr.Binary b -> compare(b, bar);
            case Expr.Unary u when u.op() == Expr.UnaryOp.NOT -> {
                ConditionNode child = node(u.operand(), bar);
                byte answer = interp.tri(e, bar);
                boolean passed = answer == Interpreter.TRUE;
                boolean unknown = answer == Interpreter.UNKNOWN;
                yield new ConditionNode(ConditionNode.Kind.NOT, text(e), e.span(),
                        passed, unknown, null, null, null,
                        null, null, null, null,
                        unknown ? "The inner condition had no answer on this candle "
                                + "(missing data), so NOT has none either: not true."
                                : passed ? "The inner condition was false, so NOT made it true."
                                : "The inner condition was true, so NOT made it false.",
                        List.of(child));
            }
            case Expr.Call c when c.name().equals("CROSSOVER") -> cross(c, bar, true);
            case Expr.Call c when c.name().equals("CROSSUNDER") -> cross(c, bar, false);
            case Expr.VarRef v -> {
                byte answer = interp.tri(e, bar);
                boolean passed = answer == Interpreter.TRUE;
                boolean unknown = answer == Interpreter.UNKNOWN;
                String said = unknown ? "had no answer yet (missing data)."
                        : passed ? "true." : "false.";
                if (treeDepth > MAX_TREE_DEPTH) {
                    yield new ConditionNode(ConditionNode.Kind.LET, v.name(), e.span(),
                            passed, unknown, null, null, null, null, null,
                            null, null, "Named condition '" + v.name() + "' was "
                            + said + " It builds on too many other named conditions "
                            + "to draw all of its parts here.",
                            List.of());
                }
                if (!shownLets.add(v.name())) {
                    // Drawn in full where it first appears; just the answer here.
                    yield new ConditionNode(ConditionNode.Kind.LET, v.name(), e.span(),
                            passed, unknown, null, null, null, null, null,
                            null, null, "Named condition '" + v.name() + "' was "
                            + said + " Its parts are shown where it first appears.",
                            List.of());
                }
                ConditionNode body = node(lets.get(v.name()).value(), bar);
                yield new ConditionNode(ConditionNode.Kind.LET, v.name(), e.span(),
                        passed, unknown, null, null, null, null, null,
                        null, null, "Named condition '" + v.name() + "' was "
                        + said, List.of(body));
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
        for (Expr part : parts) {
            ConditionNode child = node(part, bar);
            children.add(child);
            if (child.passed()) {
                trueCount++;
            }
        }
        byte answer = interp.tri(b, bar);
        boolean passed = answer == Interpreter.TRUE;
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
                text(b), b.span(), passed, answer == Interpreter.UNKNOWN, null, null,
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
        // Same rule as the engine: NaN (no data yet) and Infinity (divided
        // by zero) cannot be compared, and a distance to them means nothing.
        boolean unknown = !Double.isFinite(l) || !Double.isFinite(r);
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
            boolean leftBad = !Double.isFinite(l);
            double bad = leftBad ? l : r;
            String which = leftBad ? leftText : text(b.right());
            note = Double.isNaN(bad) ? undefinedNote(which)
                    : which + " divided by zero on this candle (no sensible value), "
                    + "so this cannot be true.";
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
                distance, relative, note, List.of(),
                withNotes ? timeframeNote(bar, b.left(), b.right()) : null);
    }

    // ── Bigger timeframes ───────────────────────────────────────────────

    private static final DateTimeFormatter DAY_TIME =
            DateTimeFormatter.ofPattern("d MMM HH:mm", Locale.ENGLISH).withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH).withZone(ZoneOffset.UTC);

    /**
     * For a leaf that reads {@code ... ON 4h}: which finished 4h candle its
     * value came from at this bar — so the reader can check that it is never
     * the 4h candle still forming. Null when the leaf reads no bigger
     * timeframe.
     */
    private String timeframeNote(int bar, Expr... sides) {
        List<Expr.On> ons = new ArrayList<>();
        for (Expr side : sides) {
            collectOns(side, ons);
        }
        if (ons.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        Set<String> seen = new LinkedHashSet<>();
        for (Expr.On o : ons) {
            if (!seen.add(o.timeframe())) {
                continue; // one sentence per timeframe
            }
            double[] when = bank.get(IndicatorBank.visibleCandleKey(o.timeframe()));
            double open = when == null || bar >= when.length ? Double.NaN : when[bar];
            if (sb.length() > 0) {
                sb.append(' ');
            }
            if (Double.isNaN(open)) {
                sb.append("No ").append(o.timeframe())
                        .append(" candle had finished yet at this candle.");
            } else {
                long from = (long) open;
                long to = from + Timeframe.millis(o.timeframe()).orElse(0);
                sb.append(o.timeframe()).append(" values come from the last finished ")
                        .append(o.timeframe()).append(" candle (")
                        .append(DAY_TIME.format(Instant.ofEpochMilli(from))).append("–")
                        .append(TIME.format(Instant.ofEpochMilli(to)))
                        .append(" UTC), never the one still forming.");
            }
        }
        return sb.toString();
    }

    /** The {@code ON}s inside each let, worked out once per let: walking
     *  through the lets afresh for every leaf took 2^n steps for
     *  {@code let b = a + a, c = b + b, ...}. */
    private final Map<String, List<Expr.On>> letOns = new HashMap<>();

    /** Every {@code ON} inside an expression, looking through named values. */
    private void collectOns(Expr e, List<Expr.On> out) {
        switch (e) {
            case Expr.On o -> out.add(o);
            case Expr.Binary b -> {
                collectOns(b.left(), out);
                collectOns(b.right(), out);
            }
            case Expr.Unary u -> collectOns(u.operand(), out);
            case Expr.Lookback l -> collectOns(l.target(), out);
            case Expr.Call c -> c.args().forEach(a -> collectOns(a, out));
            case Expr.VarRef v -> {
                List<Expr.On> known = letOns.get(v.name());
                if (known == null) {
                    // Not computeIfAbsent: filling one entry fills others.
                    known = new ArrayList<>();
                    StrategyAst.LetDecl let = lets.get(v.name());
                    if (let != null) {
                        List<Expr.On> found = new ArrayList<>();
                        collectOns(let.value(), found);
                        // One per timeframe is all timeframeNote needs.
                        Set<String> seen = new HashSet<>();
                        for (Expr.On o : found) {
                            if (seen.add(o.timeframe())) {
                                known.add(o);
                            }
                        }
                    }
                    letOns.put(v.name(), known);
                }
                out.addAll(known);
            }
            default -> {
                // literals and prices read no bigger timeframe
            }
        }
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
        boolean unknown = !Double.isFinite(l) || !Double.isFinite(r)
                || !Double.isFinite(pl) || !Double.isFinite(pr);

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
                relative, note, List.of(),
                withNotes ? timeframeNote(bar, a, b) : null);
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
            // A let drawn elsewhere (or too deep to draw) shows only its
            // answer: no leaves of its own to flip.
            case LET -> n.children().isEmpty() ? n.passed()
                    : structural(n.children().get(0), flipped);
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
            case NO_ACTION -> cond.unknown()
                    ? where + ": no action. Part of the condition had no value on this "
                    + "candle, so it was neither true nor false (THEN needs true, ELSE "
                    + "needs false)."
                    : where + ": no " + action + ", the condition was false."
                    + (closest != null ? " " + closest.note()
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
            case REJECTED_NOT_ENOUGH_CASH -> where + ": asked to " + action + because
                    + ", but that amount cost more than the cash available, so it "
                    + "was refused (no buying on credit).";
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
