package com.tsb.compiler;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Semantic analysis: the pass that turns "it parses" into "it is a valid
 * strategy". Walks the AST exactly once and performs, together:
 *
 * <ul>
 *   <li><b>Config validation</b> — known keys only, right value kind per key,
 *       required keys present, no duplicates.</li>
 *   <li><b>Name resolution</b> — every VarRef must be a previously declared
 *       let; every Call must exist in the {@link Registry}. Unknown names
 *       get Levenshtein-based "did you mean ...?" suggestions.</li>
 *   <li><b>Type checking</b> — NUMBER/BOOL discipline over expressions; IF
 *       conditions must be BOOL; config-only literal kinds (percent,
 *       timeframe, string) are rejected in rule expressions with tailored
 *       messages (the payoff of the parser's superset principle).</li>
 *   <li><b>Constness</b> — indicator parameters are constant-folded; a
 *       non-constant period is an error because indicators are precomputed
 *       before any bar runs.</li>
 *   <li><b>Manifest & warm-up</b> — collects the deduplicated indicator
 *       instances (the engine's precompute work order) and computes the
 *       strategy's warm-up bar count.</li>
 * </ul>
 *
 * <p>Same error philosophy as lexer and parser: diagnostics accumulate, the
 * walk continues, one compile reports everything. There is no exception
 * control flow here at all — unlike the parser, a semantic error never makes
 * the remaining tree unwalkable, so we just keep going.
 *
 * <p>Single-use: one Analyzer per StrategyDecl.
 */
public final class Analyzer {

    /** Result: a CompiledStrategy iff there were no errors. */
    public record AnalysisResult(
            Optional<CompiledStrategy> strategy,
            List<Diagnostic> diagnostics
    ) {
        public boolean hasErrors() {
            return diagnostics.stream().anyMatch(Diagnostic::isError);
        }
    }

    /** TSL's types. NUMBER covers everything numeric-per-bar (prices,
     *  indicator outputs, literals) — see the report's type-system section
     *  for why bar-varying and constant numbers share one TYPE while
     *  constness is tracked separately by folding. */
    enum Type { NUMBER, BOOL, PERCENT, TIMEFRAME, STRING, INVALID }

    private final StrategyAst.StrategyDecl ast;
    private final List<Diagnostic> diagnostics = new ArrayList<>();

    /** The symbol table: let name -> its declaration. Insertion order IS
     *  evaluation order; a flat single scope is a deliberate language
     *  simplification (no nesting, no functions -> no scope stack). */
    private final Map<String, StrategyAst.LetDecl> scope = new HashMap<>();

    /** Memoized warm-up per let, since lets reference other lets. */
    private final Map<String, Integer> letWarmup = new HashMap<>();

    /** The indicator manifest, deduplicated and in first-use order. */
    private final Set<CompiledStrategy.IndicatorInstance> manifest =
            new LinkedHashSet<>();

    public Analyzer(StrategyAst.StrategyDecl ast) {
        this.ast = Objects.requireNonNull(ast);
    }

    // ═════════════════════════ Entry point ═════════════════════════════

    public AnalysisResult analyze() {
        Config config = analyzeConfig();

        for (StrategyAst.LetDecl let : ast.lets()) {
            analyzeLet(let);
        }

        int warmup = 0;
        for (StrategyAst.RuleDecl rule : ast.rules()) {
            warmup = Math.max(warmup, analyzeRule(rule));
        }

        boolean hasErrors = diagnostics.stream().anyMatch(Diagnostic::isError);
        if (hasErrors || config == null) {
            return new AnalysisResult(Optional.empty(), List.copyOf(diagnostics));
        }
        return new AnalysisResult(Optional.of(new CompiledStrategy(
                ast.name(), config.symbol, config.timeframe, config.capital,
                config.feePercent, ast.lets(), ast.rules(),
                Set.copyOf(manifest), warmup)),
                List.copyOf(diagnostics));
    }

    // ═════════════════════════ Config ══════════════════════════════════

    private record Config(String symbol, String timeframe,
                          double capital, double feePercent) {
    }

    private static final Set<String> KNOWN_KEYS =
            Set.of("symbol", "timeframe", "capital", "fee");
    private static final Set<String> SUPPORTED_TIMEFRAMES =
            Set.of("1m", "5m", "15m", "1h", "4h", "1d");

    private Config analyzeConfig() {
        Map<String, StrategyAst.ConfigEntry> seen = new HashMap<>();
        for (StrategyAst.ConfigEntry entry : ast.config()) {
            if (!KNOWN_KEYS.contains(entry.key())) {
                error("SEM007", "unknown config key '" + entry.key() + "'"
                                + suggest(entry.key(), List.copyOf(KNOWN_KEYS)),
                        entry.span());
                continue;
            }
            if (seen.putIfAbsent(entry.key(), entry) != null) {
                error("SEM008", "config key '" + entry.key()
                        + "' is set more than once", entry.span());
            }
        }
        for (String required : List.of("symbol", "timeframe", "capital")) {
            if (!seen.containsKey(required)) {
                error("SEM009", "missing required config key '" + required
                        + "'", ast.span());
            }
        }

        String symbol = null;
        String timeframe = null;
        double capital = 0;
        double feePercent = 0;

        StrategyAst.ConfigEntry e;
        if ((e = seen.get("symbol")) != null) {
            // The symbol value is IDENT-shaped (BTCUSDT); whether it exists
            // in the database is checked at run submission, not here — the
            // compiler stays independent of the data layer.
            if (e.value() instanceof Expr.VarRef v) {
                symbol = v.name();
            } else {
                error("SEM012", "config 'symbol' expects a plain symbol name "
                        + "like BTCUSDT", e.value().span());
            }
        }
        if ((e = seen.get("timeframe")) != null) {
            if (e.value() instanceof Expr.TimeframeLit t
                    && SUPPORTED_TIMEFRAMES.contains(t.value())) {
                timeframe = t.value();
            } else {
                error("SEM012", "config 'timeframe' expects one of "
                        + String.join(", ", SUPPORTED_TIMEFRAMES.stream()
                        .sorted().toList()), e.value().span());
            }
        }
        if ((e = seen.get("capital")) != null) {
            Optional<Double> v = fold(e.value());
            if (v.isPresent() && v.get() > 0) {
                capital = v.get();
            } else {
                error("SEM012", "config 'capital' expects a positive number",
                        e.value().span());
            }
        }
        if ((e = seen.get("fee")) != null) {
            if (e.value() instanceof Expr.PercentLit p && p.value() >= 0) {
                // THE /100 conversion, in its one and only home.
                feePercent = p.value() / 100.0;
            } else {
                error("SEM012", "config 'fee' expects a percent like 0.1%",
                        e.value().span());
            }
        }

        return (symbol != null && timeframe != null && capital > 0)
                ? new Config(symbol, timeframe, capital, feePercent)
                : null;
    }

    // ═════════════════════════ Lets & rules ════════════════════════════

    private void analyzeLet(StrategyAst.LetDecl let) {
        // Type the value FIRST, with the current scope: a let can only see
        // lets declared above it (evaluation order = declaration order).
        Type t = typeOf(let.value());
        if (t != Type.NUMBER && t != Type.BOOL && t != Type.INVALID) {
            error("SEM010", "a let must hold a number or a condition; "
                    + friendly(t) + " values belong in config", let.value().span());
        }
        if (scope.putIfAbsent(let.name(), let) != null) {
            error("SEM005", "'" + let.name() + "' is declared more than once",
                    let.span());
        } else {
            letWarmup.put(let.name(), warmupOf(let.value()));
        }
    }

    /** Returns the rule's warm-up contribution. */
    private int analyzeRule(StrategyAst.RuleDecl rule) {
        int warmup = 0;
        for (StrategyAst.IfStmt stmt : rule.body()) {
            Type condType = typeOf(stmt.condition());
            if (condType != Type.BOOL && condType != Type.INVALID) {
                error("SEM011", "an IF condition must be true/false — this is "
                        + friendly(condType) + "; did you mean a comparison "
                        + "like '... > 0'?", stmt.condition().span());
            }
            warmup = Math.max(warmup, warmupOf(stmt.condition()));

            warmup = Math.max(warmup, analyzeAction(stmt.thenAction()));
            if (stmt.elseAction().isPresent()) {
                warmup = Math.max(warmup, analyzeAction(stmt.elseAction().get()));
            }
        }
        return warmup;
    }

    private int analyzeAction(StrategyAst.Action action) {
        return switch (action) {
            case StrategyAst.Action.Buy b -> analyzeSizing(b.sizing());
            case StrategyAst.Action.Sell s -> analyzeSizing(s.sizing());
            case StrategyAst.Action.Set s -> {
                if (!(s.value() instanceof Expr.PercentLit p) || p.value() <= 0) {
                    error("SEM012", "SET " + s.target()
                                    + " expects a positive percent like 5%",
                            s.value().span());
                }
                yield 0;
            }
        };
    }

    private int analyzeSizing(StrategyAst.Sizing sizing) {
        switch (sizing) {
            case StrategyAst.Sizing.All ignored -> {
                // nothing to check
            }
            case StrategyAst.Sizing.PercentOf p -> {
                if (!(p.percent() instanceof Expr.PercentLit lit)
                        || lit.value() <= 0 || lit.value() > 100) {
                    error("SEM012", "position size expects a percent between "
                                    + "0% and 100%, e.g. 'qty = 25% OF EQUITY'",
                            p.percent().span());
                }
            }
            case StrategyAst.Sizing.Quantity q -> {
                Optional<Double> v = fold(q.amount());
                if (typeOf(q.amount()) != Type.NUMBER
                        || v.isEmpty() || v.get() <= 0) {
                    error("SEM012", "'qty =' expects a positive constant "
                                    + "number (or use '25% OF EQUITY')",
                            q.amount().span());
                }
            }
        }
        return 0;
    }

    // ═════════════════════════ Type checking ═══════════════════════════

    /**
     * Types an expression, emitting diagnostics for violations. Returns
     * INVALID when a subtree already failed — and INVALID never triggers
     * follow-on errors, which is the same cascade-suppression idea the
     * parser uses: one mistake, one message.
     */
    private Type typeOf(Expr e) {
        return switch (e) {
            case Expr.NumberLit ignored -> Type.NUMBER;
            case Expr.PercentLit ignored -> Type.PERCENT;
            case Expr.TimeframeLit ignored -> Type.TIMEFRAME;
            case Expr.StringLit ignored -> Type.STRING;
            case Expr.PriceRef ignored -> Type.NUMBER;

            case Expr.VarRef v -> {
                StrategyAst.LetDecl let = scope.get(v.name());
                if (let == null) {
                    error("SEM001", "unknown name '" + v.name() + "'"
                            + suggest(v.name(), candidates()), v.span());
                    yield Type.INVALID;
                }
                yield typeOfQuiet(let.value());
            }

            case Expr.Call c -> typeOfCall(c);

            case Expr.Lookback l -> {
                Type target = typeOf(l.target());
                if (target != Type.NUMBER && target != Type.INVALID) {
                    error("SEM003", "lookback [n] applies to numeric series, "
                            + "not " + friendly(target), l.span());
                    yield Type.INVALID;
                }
                yield target;
            }

            case Expr.Unary u -> {
                Type operand = typeOf(u.operand());
                if (operand == Type.INVALID) {
                    yield Type.INVALID;
                }
                if (u.op() == Expr.UnaryOp.NOT) {
                    if (operand != Type.BOOL) {
                        error("SEM003", "NOT expects a condition, not "
                                + friendly(operand), u.span());
                        yield Type.INVALID;
                    }
                    yield Type.BOOL;
                }
                // NEG
                if (operand != Type.NUMBER) {
                    error("SEM003", "'-' expects a number, not "
                            + friendly(operand), u.span());
                    yield Type.INVALID;
                }
                yield Type.NUMBER;
            }

            case Expr.Binary b -> typeOfBinary(b);
        };
    }

    private Type typeOfBinary(Expr.Binary b) {
        Type left = typeOf(b.left());
        Type right = typeOf(b.right());
        if (left == Type.INVALID || right == Type.INVALID) {
            return Type.INVALID;
        }
        boolean arithmetic = switch (b.op()) {
            case ADD, SUB, MUL, DIV -> true;
            default -> false;
        };
        boolean comparison = switch (b.op()) {
            case LT, GT, LE, GE, EQ, NEQ -> true;
            default -> false;
        };

        if (arithmetic || comparison) {
            for (Type t : List.of(left, right)) {
                if (t == Type.PERCENT) {
                    error("SEM010", "percent values can't be used in "
                                    + "calculations — write 0.25 instead of 25%",
                            b.span());
                    return Type.INVALID;
                }
                if (t != Type.NUMBER) {
                    error("SEM003", "'" + symbolOf(b.op()) + "' expects numbers"
                            + " on both sides, found " + friendly(t), b.span());
                    return Type.INVALID;
                }
            }
            return arithmetic ? Type.NUMBER : Type.BOOL;
        }

        // AND / OR
        for (Type t : List.of(left, right)) {
            if (t != Type.BOOL) {
                error("SEM003", "'" + symbolOf(b.op()) + "' combines "
                                + "conditions; found " + friendly(t)
                                + " — did you mean a comparison like '... > 0'?",
                        b.span());
                return Type.INVALID;
            }
        }
        return Type.BOOL;
    }

    private Type typeOfCall(Expr.Call c) {
        Optional<Registry.Signature> found = Registry.lookup(c.name());
        if (found.isEmpty()) {
            error("SEM001", "unknown indicator or function '" + c.name() + "'"
                    + suggest(c.name(), candidates()), c.span());
            // Still type the args so THEIR errors surface too.
            c.args().forEach(this::typeOf);
            return Type.INVALID;
        }
        Registry.Signature sig = found.get();

        if (c.args().size() != sig.arity()) {
            error("SEM002", c.name() + " expects " + sig.arity()
                    + " argument(s): " + sig.describe() + " — got "
                    + c.args().size(), c.span());
            return Type.INVALID;
        }

        List<Double> constArgs = new ArrayList<>();
        Expr.PriceField source = null;
        boolean ok = true;

        for (int i = 0; i < sig.arity(); i++) {
            Registry.Param param = sig.params().get(i);
            Expr arg = c.args().get(i);
            switch (param.kind()) {
                case PRICE_SERIES -> {
                    if (arg instanceof Expr.PriceRef p) {
                        source = p.field();
                    } else {
                        error("SEM013", "for now, " + c.name() + "'s '"
                                        + param.name() + "' must be a price series "
                                        + "(OPEN/HIGH/LOW/CLOSE/VOLUME) — "
                                        + "indicator-of-indicator isn't supported yet",
                                arg.span());
                        ok = false;
                    }
                }
                case CONST_NUMBER -> {
                    Optional<Double> v = fold(arg);
                    if (v.isEmpty() || typeOf(arg) != Type.NUMBER) {
                        error("SEM004", c.name() + "'s '" + param.name()
                                        + "' must be a constant number — indicators "
                                        + "are precomputed before any bar runs",
                                arg.span());
                        ok = false;
                    } else if (param.positiveInt()
                            && (v.get() < 1 || v.get() != Math.floor(v.get()))) {
                        error("SEM012", c.name() + "'s '" + param.name()
                                + "' must be a positive whole number, got "
                                + AstPrinter.print(arg), arg.span());
                        ok = false;
                    } else {
                        constArgs.add(v.get());
                    }
                }
                case NUMERIC -> {
                    Type t = typeOf(arg);
                    if (t != Type.NUMBER && t != Type.INVALID) {
                        error("SEM003", c.name() + "'s '" + param.name()
                                        + "' expects a number, found " + friendly(t),
                                arg.span());
                        ok = false;
                    }
                }
            }
        }

        if (ok && sig.precomputed()) {
            manifest.add(new CompiledStrategy.IndicatorInstance(
                    c.name(), source, List.copyOf(constArgs)));
        }
        return ok ? (sig.returnsBool() ? Type.BOOL : Type.NUMBER) : Type.INVALID;
    }

    /** Types without emitting diagnostics — for re-typing an already
     *  validated let body when a VarRef reads it. */
    private Type typeOfQuiet(Expr e) {
        int before = diagnostics.size();
        Type t = typeOf(e);
        while (diagnostics.size() > before) {
            diagnostics.remove(diagnostics.size() - 1);
        }
        return t;
    }

    // ═════════════════════════ Constant folding ════════════════════════

    /** The value of a compile-time-constant numeric expression, if it is one.
     *  RSI(7 * 2) folds to 14; RSI(CLOSE) does not fold. */
    private Optional<Double> fold(Expr e) {
        return switch (e) {
            case Expr.NumberLit n -> Optional.of(n.value());
            case Expr.Unary u when u.op() == Expr.UnaryOp.NEG ->
                    fold(u.operand()).map(v -> -v);
            case Expr.Binary b -> fold(b.left()).flatMap(l ->
                    fold(b.right()).flatMap(r -> switch (b.op()) {
                        case ADD -> Optional.of(l + r);
                        case SUB -> Optional.of(l - r);
                        case MUL -> Optional.of(l * r);
                        case DIV -> r == 0 ? Optional.empty()
                                : Optional.of(l / r);
                        default -> Optional.empty();
                    }));
            default -> Optional.empty();
        };
    }

    // ═════════════════════════ Warm-up ═════════════════════════════════

    /** Bars needed before this expression's value is meaningful. */
    private int warmupOf(Expr e) {
        return switch (e) {
            case Expr.NumberLit ignored -> 0;
            case Expr.PercentLit ignored -> 0;
            case Expr.TimeframeLit ignored -> 0;
            case Expr.StringLit ignored -> 0;
            case Expr.PriceRef ignored -> 0;
            case Expr.VarRef v -> letWarmup.getOrDefault(v.name(), 0);
            case Expr.Lookback l -> warmupOf(l.target()) + l.offset();
            case Expr.Unary u -> warmupOf(u.operand());
            case Expr.Binary b -> Math.max(warmupOf(b.left()),
                    warmupOf(b.right()));
            case Expr.Call c -> {
                int args = c.args().stream().mapToInt(this::warmupOf)
                        .max().orElse(0);
                yield Registry.lookup(c.name()).map(sig -> {
                    if (sig.precomputed()) {
                        double[] consts = c.args().stream()
                                .map(this::fold)
                                .filter(Optional::isPresent)
                                .mapToDouble(Optional::get).toArray();
                        // Guard: on an errored call the consts may be
                        // incomplete; warm-up of 0 is fine since compilation
                        // already failed.
                        int own = consts.length == sig.params().stream()
                                .filter(p -> p.kind()
                                        == Registry.ParamKind.CONST_NUMBER)
                                .count()
                                ? sig.warmup().applyAsInt(consts) : 0;
                        return Math.max(own, args);
                    }
                    return args + sig.extraBars();
                }).orElse(args);
            }
        };
    }

    // ═════════════════════════ Helpers ═════════════════════════════════

    /** Names a VarRef or Call could legally be — for suggestions. */
    private List<String> candidates() {
        List<String> names = new ArrayList<>(Registry.allNames());
        names.addAll(scope.keySet());
        return names;
    }

    /** " — did you mean 'RSI'?" if something is within edit distance 2. */
    private static String suggest(String name, List<String> candidates) {
        String best = null;
        int bestDist = 3; // suggestions only within distance 2
        for (String c : candidates) {
            int d = levenshtein(name, c);
            if (d < bestDist) {
                bestDist = d;
                best = c;
            }
        }
        return best == null ? "" : " — did you mean '" + best + "'?";
    }

    /** Classic DP edit distance; inputs are short names, so O(n*m) is free. */
    private static int levenshtein(String a, String b) {
        int[] prev = new int[b.length() + 1];
        int[] curr = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            prev[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            curr[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                curr[j] = Math.min(Math.min(curr[j - 1] + 1, prev[j] + 1),
                        prev[j - 1] + cost);
            }
            int[] tmp = prev;
            prev = curr;
            curr = tmp;
        }
        return prev[b.length()];
    }

    private static String friendly(Type t) {
        return switch (t) {
            case NUMBER -> "a number";
            case BOOL -> "a condition";
            case PERCENT -> "a percent";
            case TIMEFRAME -> "a timeframe";
            case STRING -> "text";
            case INVALID -> "an invalid expression";
        };
    }

    private static String symbolOf(Expr.BinaryOp op) {
        return switch (op) {
            case ADD -> "+"; case SUB -> "-"; case MUL -> "*"; case DIV -> "/";
            case LT -> "<"; case GT -> ">"; case LE -> "<="; case GE -> ">=";
            case EQ -> "=="; case NEQ -> "!="; case AND -> "AND"; case OR -> "OR";
        };
    }

    private void error(String code, String message, Span span) {
        diagnostics.add(Diagnostic.error(code, message, span));
    }
}