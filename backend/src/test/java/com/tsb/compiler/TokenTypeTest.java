package com.tsb.compiler;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the language-design decisions encoded in {@link TokenType#KEYWORDS}.
 * If any of these fail after a change, the change altered the language, not
 * just the code — stop and think before "fixing" the test.
 */
@DisplayName("TokenType keyword table")
class TokenTypeTest {

    @Test
    @DisplayName("structural keywords are lowercase")
    void structuralKeywordsLowercase() {
        assertEquals(TokenType.STRATEGY, TokenType.KEYWORDS.get("strategy"));
        assertEquals(TokenType.LET, TokenType.KEYWORDS.get("let"));
        assertEquals(TokenType.RULE, TokenType.KEYWORDS.get("rule"));
        assertEquals(TokenType.QTY, TokenType.KEYWORDS.get("qty"));
    }

    @Test
    @DisplayName("statement and logic keywords are uppercase")
    void statementKeywordsUppercase() {
        assertEquals(TokenType.IF, TokenType.KEYWORDS.get("IF"));
        assertEquals(TokenType.BUY, TokenType.KEYWORDS.get("BUY"));
        assertEquals(TokenType.SELL, TokenType.KEYWORDS.get("SELL"));
        assertEquals(TokenType.AND, TokenType.KEYWORDS.get("AND"));
        assertEquals(TokenType.NOT, TokenType.KEYWORDS.get("NOT"));
        assertEquals(TokenType.STOPLOSS, TokenType.KEYWORDS.get("STOPLOSS"));
    }

    @Test
    @DisplayName("price references are keywords (closed set, typed as Series)")
    void priceReferencesAreKeywords() {
        assertEquals(TokenType.OPEN, TokenType.KEYWORDS.get("OPEN"));
        assertEquals(TokenType.HIGH, TokenType.KEYWORDS.get("HIGH"));
        assertEquals(TokenType.LOW, TokenType.KEYWORDS.get("LOW"));
        assertEquals(TokenType.CLOSE, TokenType.KEYWORDS.get("CLOSE"));
        assertEquals(TokenType.VOLUME, TokenType.KEYWORDS.get("VOLUME"));
    }

    @Test
    @DisplayName("keywords are case-sensitive: wrong-case forms are identifiers")
    void keywordsAreCaseSensitive() {
        assertFalse(TokenType.KEYWORDS.containsKey("If"));
        assertFalse(TokenType.KEYWORDS.containsKey("buy"));
        assertFalse(TokenType.KEYWORDS.containsKey("Strategy"));
        assertFalse(TokenType.KEYWORDS.containsKey("close"));
    }

    @Test
    @DisplayName("DESIGN DECISION: indicator and function names are NOT keywords")
    void indicatorNamesAreNotKeywords() {
        // Indicators are resolved in semantic analysis, not the lexer, so new
        // indicators never require lexer changes and errors can suggest
        // near-misses ("did you mean 'RSI'?"). See TokenType javadoc.
        assertFalse(TokenType.KEYWORDS.containsKey("RSI"));
        assertFalse(TokenType.KEYWORDS.containsKey("SMA"));
        assertFalse(TokenType.KEYWORDS.containsKey("EMA"));
        assertFalse(TokenType.KEYWORDS.containsKey("MACD"));
        assertFalse(TokenType.KEYWORDS.containsKey("VWAP"));
        assertFalse(TokenType.KEYWORDS.containsKey("CROSSOVER"));
        assertFalse(TokenType.KEYWORDS.containsKey("CROSSUNDER"));
    }

    @Test
    @DisplayName("keyword table is immutable")
    void keywordTableIsImmutable() {
        assertThrows(UnsupportedOperationException.class,
                () -> TokenType.KEYWORDS.put("HACK", TokenType.IF));
    }

    @Test
    @DisplayName("every keyword maps to a distinct token type")
    void keywordsMapToDistinctTypes() {
        long distinctTypes = TokenType.KEYWORDS.values().stream().distinct().count();
        assertEquals(TokenType.KEYWORDS.size(), distinctTypes,
                "two keywords must never share a token type");
        assertTrue(TokenType.KEYWORDS.size() >= 25, "expected the full keyword set");
    }
}