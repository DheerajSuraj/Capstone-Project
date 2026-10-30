package com.tsb.compiler;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The parse tree the Blocks view builds its blocks from, and the comments
 * the lexer now keeps for it.
 */
@DisplayName("ParseTree (code -> blocks)")
class ParseTreeTest {

    private static final String SOURCE = """
            // Dip buyer: buys when RSI is low.
            strategy "Dips" {
                symbol = BTCUSDT
                timeframe = 1h
                capital = 10000
                fee = 0.1%

                let rsi = RSI(14)   // momentum
                let trend = EMA(CLOSE, 50)[1] ON 4h

                rule entry {
                    // only in an uptrend
                    IF rsi < 30 AND NOT (CLOSE < trend) THEN BUY qty = 25% OF EQUITY
                    ELSE SELL qty = 0.5
                }

                rule exit {
                    IF CROSSOVER(rsi, 70) OR CLOSE > -5 * 2 THEN SELL ALL
                    IF CLOSE > 0 THEN SET STOPLOSS = 4%
                }
            }
            """;

    private static Map<String, Object> tree(String source) {
        Lexer.LexResult lexed = new Lexer(source).scan();
        assertEquals(List.of(), lexed.diagnostics());
        Parser.ParseResult parsed = new Parser(lexed.tokens()).parse();
        assertEquals(List.of(), parsed.diagnostics());
        return ParseTree.of(parsed.strategy().orElseThrow(), lexed.comments());
    }

    /** Walks maps (by key) and lists (by index): at(tree, "rules", 0, "name"). */
    private static Object at(Object node, Object... path) {
        Object cur = node;
        for (Object step : path) {
            if (step instanceof Integer i) {
                cur = ((List<?>) cur).get(i);
            } else {
                cur = ((Map<?, ?>) cur).get(step);
            }
        }
        return cur;
    }

    @Test
    @DisplayName("strategy name, config entries and their lines")
    void config() {
        Map<String, Object> t = tree(SOURCE);
        assertEquals("Dips", t.get("name"));
        assertEquals(2, t.get("line"));
        assertEquals(21, t.get("endLine"));
        assertEquals(4, ((List<?>) t.get("config")).size());
        assertEquals("symbol", at(t, "config", 0, "key"));
        assertEquals(Map.of("kind", "name", "name", "BTCUSDT"), at(t, "config", 0, "value"));
        assertEquals(Map.of("kind", "timeframe", "value", "1h"), at(t, "config", 1, "value"));
        assertEquals(Map.of("kind", "number", "value", 10000.0), at(t, "config", 2, "value"));
        assertEquals(Map.of("kind", "percent", "value", 0.1), at(t, "config", 3, "value"));
        assertEquals(5, at(t, "config", 2, "line"));
    }

    @Test
    @DisplayName("lets: calls, lookback inside ON, in source order")
    void lets() {
        Map<String, Object> t = tree(SOURCE);
        assertEquals("rsi", at(t, "lets", 0, "name"));
        assertEquals("call", at(t, "lets", 0, "value", "kind"));
        assertEquals("RSI", at(t, "lets", 0, "value", "name"));
        assertEquals(14.0, at(t, "lets", 0, "value", "args", 0, "value"));

        // EMA(CLOSE, 50)[1] ON 4h: the lookback is on the 4h series
        assertEquals("on", at(t, "lets", 1, "value", "kind"));
        assertEquals("4h", at(t, "lets", 1, "value", "timeframe"));
        assertEquals("lookback", at(t, "lets", 1, "value", "target", "kind"));
        assertEquals(1, at(t, "lets", 1, "value", "target", "offset"));
        assertEquals("EMA", at(t, "lets", 1, "value", "target", "target", "name"));
        assertEquals(Map.of("kind", "price", "field", "CLOSE"),
                at(t, "lets", 1, "value", "target", "target", "args", 0));
        assertEquals(9, at(t, "lets", 1, "line"));
    }

    @Test
    @DisplayName("rules: IF with ELSE, every action and sizing shape")
    void rules() {
        Map<String, Object> t = tree(SOURCE);
        assertEquals("entry", at(t, "rules", 0, "name"));
        Object entry = at(t, "rules", 0, "body", 0);
        assertEquals(13, at(entry, "line"));
        assertEquals(14, at(entry, "endLine"));
        assertEquals("buy", at(entry, "then", "kind"));
        assertEquals("percentOf", at(entry, "then", "size", "kind"));
        assertEquals("EQUITY", at(entry, "then", "size", "base"));
        assertEquals(Map.of("kind", "percent", "value", 25.0), at(entry, "then", "size", "percent"));
        assertEquals("sell", at(entry, "else", "kind"));
        assertEquals("quantity", at(entry, "else", "size", "kind"));
        assertEquals(Map.of("kind", "number", "value", 0.5), at(entry, "else", "size", "amount"));

        Object exit0 = at(t, "rules", 1, "body", 0);
        assertEquals(Map.of("kind", "all"), at(exit0, "then", "size"));
        assertTrue(((Map<?, ?>) exit0).containsKey("else"));
        assertNull(at(exit0, "else"));

        Object exit1 = at(t, "rules", 1, "body", 1);
        assertEquals("set", at(exit1, "then", "kind"));
        assertEquals("STOPLOSS", at(exit1, "then", "target"));
        assertEquals(Map.of("kind", "percent", "value", 4.0), at(exit1, "then", "value"));
    }

    @Test
    @DisplayName("expressions keep the parser's precedence: no parentheses, just the tree")
    void expressions() {
        Map<String, Object> t = tree(SOURCE);
        // rsi < 30 AND NOT (CLOSE < trend)
        Object cond = at(t, "rules", 0, "body", 0, "condition");
        assertEquals("binary", at(cond, "kind"));
        assertEquals("AND", at(cond, "op"));
        assertEquals("LT", at(cond, "left", "op"));
        assertEquals(Map.of("kind", "name", "name", "rsi"), at(cond, "left", "left"));
        assertEquals("unary", at(cond, "right", "kind"));
        assertEquals("NOT", at(cond, "right", "op"));
        assertEquals("LT", at(cond, "right", "operand", "op"));

        // CROSSOVER(rsi, 70) OR CLOSE > -5 * 2   ->   OR( CROSSOVER, GT(CLOSE, MUL(NEG 5, 2)) )
        Object cond2 = at(t, "rules", 1, "body", 0, "condition");
        assertEquals("OR", at(cond2, "op"));
        assertEquals("CROSSOVER", at(cond2, "left", "name"));
        assertEquals("GT", at(cond2, "right", "op"));
        assertEquals("MUL", at(cond2, "right", "right", "op"));
        assertEquals("NEG", at(cond2, "right", "right", "left", "op"));
        assertEquals(5.0, at(cond2, "right", "right", "left", "operand", "value"));
    }

    @Test
    @DisplayName("comments: own-line or end-of-line, text after the slashes, positions")
    void comments() {
        Map<String, Object> t = tree(SOURCE);
        List<?> notes = (List<?>) t.get("comments");
        assertEquals(3, notes.size());
        assertEquals(Map.of("line", 1, "col", 1,
                "text", " Dip buyer: buys when RSI is low.", "ownLine", true), notes.get(0));
        assertEquals(8, at(notes, 1, "line"));
        assertEquals(" momentum", at(notes, 1, "text"));
        assertEquals(false, at(notes, 1, "ownLine"));
        assertEquals(12, at(notes, 2, "line"));
        assertEquals(9, at(notes, 2, "col"));
        assertEquals(true, at(notes, 2, "ownLine"));
    }

    @Test
    @DisplayName("keeping comments changes nothing the parser sees")
    void commentsDoNotChangeTokens() {
        String plain = SOURCE.replaceAll("//[^\\n]*", "");
        List<TokenType> with = new Lexer(SOURCE).scan().tokens().stream().map(Token::type).toList();
        List<TokenType> without = new Lexer(plain).scan().tokens().stream().map(Token::type).toList();
        assertEquals(without, with);
        assertEquals(List.of(), new Lexer(plain).scan().comments());
    }

    @Test
    @DisplayName("a comment's trailing spaces and Windows line ends are dropped")
    void commentTextIsTrimmed() {
        List<Lexer.Comment> cs = new Lexer("//  two  \r\nstrategy \"x\" { } // end\t").scan().comments();
        assertEquals(List.of(new Lexer.Comment(1, 1, "  two", true),
                new Lexer.Comment(2, 18, " end", false)), cs);
    }

    @Test
    @DisplayName("a number too big for a double is sent as text, not as a broken JSON number")
    void hugeNumber() {
        String digits = "9".repeat(400);
        Map<String, Object> t = tree("strategy \"x\" { capital = " + digits + " }");
        assertEquals(Map.of("kind", "number", "value", "Infinity"), at(t, "config", 0, "value"));
        assertFalse(at(t, "config", 0, "value", "value") instanceof Double);
    }
}
