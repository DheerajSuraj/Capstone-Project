package com.tsb.compiler;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The parse tree of a strategy as plain maps and lists, ready to be sent as
 * JSON. The Blocks view builds its blocks from it, so a strategy written as
 * code (or opened from "My strategies") shows up as ITS blocks: code to
 * blocks, the other way round from the block generator.
 *
 * <p>Built from the real parser's tree, so the frontend never parses TSL
 * itself: one brain, two views. Only the shape is translated here; whether
 * the strategy makes sense is still the analyzer's job, and a tree is sent
 * for code with semantic errors too (the blocks show the code as it is, and
 * the errors are listed under them).
 *
 * <p>The shape is a public contract, mirrored in the frontend's
 * {@code api.ts} ({@code StrategyTree}). Every node has a {@code kind};
 * statements carry the lines they cover so comments can go on the right
 * block. Example:
 * <pre>{@code
 * { "name": "T", "line": 1, "endLine": 9,
 *   "config": [ { "key": "symbol", "value": { "kind": "name", "name": "BTCUSDT" },
 *                 "line": 2, "endLine": 2 }, ... ],
 *   "lets":   [ { "name": "rsi", "value": { "kind": "call", "name": "RSI",
 *                 "args": [ { "kind": "number", "value": 14.0 } ] }, ... } ],
 *   "rules":  [ { "name": "entry", "body": [ { "condition": {...},
 *                 "then": { "kind": "buy", "size": { "kind": "all" } },
 *                 "else": null, "line": 7, "endLine": 7 } ], ... } ],
 *   "comments": [ { "line": 1, "col": 1, "text": " buys dips", "ownLine": true } ] }
 * }</pre>
 */
public final class ParseTree {

    private ParseTree() {
    }

    public static Map<String, Object> of(StrategyAst.StrategyDecl s,
                                         List<Lexer.Comment> comments) {
        Map<String, Object> m = node();
        m.put("name", s.name());
        lines(m, s.span());
        m.put("config", s.config().stream().map(ParseTree::config).toList());
        m.put("lets", s.lets().stream().map(ParseTree::let).toList());
        m.put("rules", s.rules().stream().map(ParseTree::rule).toList());
        List<Map<String, Object>> notes = new ArrayList<>();
        for (Lexer.Comment c : comments) {
            Map<String, Object> n = node();
            n.put("line", c.line());
            n.put("col", c.col());
            n.put("text", c.text());
            n.put("ownLine", c.ownLine());
            notes.add(n);
        }
        m.put("comments", notes);
        return m;
    }

    private static Map<String, Object> config(StrategyAst.ConfigEntry c) {
        Map<String, Object> m = node();
        m.put("key", c.key());
        m.put("value", expr(c.value()));
        lines(m, c.span());
        return m;
    }

    private static Map<String, Object> let(StrategyAst.LetDecl l) {
        Map<String, Object> m = node();
        m.put("name", l.name());
        m.put("value", expr(l.value()));
        lines(m, l.span());
        return m;
    }

    private static Map<String, Object> rule(StrategyAst.RuleDecl r) {
        Map<String, Object> m = node();
        m.put("name", r.name());
        m.put("body", r.body().stream().map(ParseTree::ifStmt).toList());
        lines(m, r.span());
        return m;
    }

    private static Map<String, Object> ifStmt(StrategyAst.IfStmt s) {
        Map<String, Object> m = node();
        m.put("condition", expr(s.condition()));
        m.put("then", action(s.thenAction()));
        m.put("else", s.elseAction().map(ParseTree::action).orElse(null));
        lines(m, s.span());
        return m;
    }

    static Map<String, Object> action(StrategyAst.Action a) {
        Map<String, Object> m = node();
        switch (a) {
            case StrategyAst.Action.Buy b -> {
                m.put("kind", "buy");
                m.put("size", sizing(b.sizing()));
            }
            case StrategyAst.Action.Sell s -> {
                m.put("kind", "sell");
                m.put("size", sizing(s.sizing()));
            }
            case StrategyAst.Action.Set s -> {
                m.put("kind", "set");
                m.put("target", s.target().name());
                m.put("value", expr(s.value()));
            }
        }
        return m;
    }

    private static Map<String, Object> sizing(StrategyAst.Sizing s) {
        Map<String, Object> m = node();
        switch (s) {
            case StrategyAst.Sizing.All ignored -> m.put("kind", "all");
            case StrategyAst.Sizing.Quantity q -> {
                m.put("kind", "quantity");
                m.put("amount", expr(q.amount()));
            }
            case StrategyAst.Sizing.PercentOf p -> {
                m.put("kind", "percentOf");
                m.put("percent", expr(p.percent()));
                m.put("base", p.base().name());
            }
        }
        return m;
    }

    /** Exhaustive over the sealed {@link Expr}, like every other pass. */
    static Map<String, Object> expr(Expr e) {
        Map<String, Object> m = node();
        switch (e) {
            case Expr.NumberLit n -> {
                m.put("kind", "number");
                m.put("value", number(n.value()));
            }
            case Expr.PercentLit p -> {
                m.put("kind", "percent");
                m.put("value", number(p.value()));
            }
            case Expr.TimeframeLit t -> {
                m.put("kind", "timeframe");
                m.put("value", t.value());
            }
            case Expr.StringLit s -> {
                m.put("kind", "string");
                m.put("value", s.value());
            }
            case Expr.PriceRef p -> {
                m.put("kind", "price");
                m.put("field", p.field().name());
            }
            case Expr.VarRef v -> {
                m.put("kind", "name");
                m.put("name", v.name());
            }
            case Expr.Call c -> {
                m.put("kind", "call");
                m.put("name", c.name());
                m.put("args", c.args().stream().map(ParseTree::expr).toList());
            }
            case Expr.Lookback l -> {
                m.put("kind", "lookback");
                m.put("target", expr(l.target()));
                m.put("offset", l.offset());
            }
            case Expr.On o -> {
                m.put("kind", "on");
                m.put("target", expr(o.target()));
                m.put("timeframe", o.timeframe());
            }
            case Expr.Unary u -> {
                m.put("kind", "unary");
                m.put("op", u.op().name());
                m.put("operand", expr(u.operand()));
            }
            case Expr.Binary b -> {
                m.put("kind", "binary");
                m.put("op", b.op().name());
                m.put("left", expr(b.left()));
                m.put("right", expr(b.right()));
            }
        }
        return m;
    }

    /** A literal too big for a double (a 400-digit number) is Infinity,
     *  which JSON has no number for: sent as the text "Infinity" instead,
     *  so the frontend sees it is not a usable number. */
    private static Object number(double v) {
        return Double.isFinite(v) ? (Object) v : String.valueOf(v);
    }

    private static void lines(Map<String, Object> m, Span span) {
        m.put("line", span.startLine());
        m.put("endLine", span.endLine());
    }

    /** Insertion-ordered, so the JSON reads in the order written here. */
    private static Map<String, Object> node() {
        return new LinkedHashMap<>();
    }
}
