package com.tsb.compiler;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static com.tsb.compiler.TokenType.CLOSE;
import static com.tsb.compiler.TokenType.EOF;
import static com.tsb.compiler.TokenType.GT;
import static com.tsb.compiler.TokenType.IDENT;
import static com.tsb.compiler.TokenType.LE;
import static com.tsb.compiler.TokenType.LT;
import static com.tsb.compiler.TokenType.NUMBER;
import static com.tsb.compiler.TokenType.PERCENT;
import static com.tsb.compiler.TokenType.STRING;
import static com.tsb.compiler.TokenType.THEN;
import static com.tsb.compiler.TokenType.TIMEFRAME;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Token")
class TokenTest {

    private static final Span SPAN = Span.of(1, 5, 7);

    @Nested
    @DisplayName("construction")
    class Construction {

        @Test
        @DisplayName("literal tokens carry lexeme AND pre-parsed value")
        void literalCarriesParsedValue() {
            Token number = new Token(NUMBER, "14", 14.0, SPAN);
            assertEquals("14", number.lexeme());
            assertEquals(14.0, number.numberValue());

            // PERCENT: lexeme keeps the % sign, value is the number as written
            // (25.0 not 0.25 — the /100 conversion is semantic analysis' job).
            Token percent = new Token(PERCENT, "25%", 25.0, SPAN);
            assertEquals("25%", percent.lexeme());
            assertEquals(25.0, percent.numberValue());

            // STRING: lexeme keeps the quotes, value drops them.
            Token string = new Token(STRING, "\"My Strategy\"", "My Strategy", SPAN);
            assertEquals("My Strategy", string.stringValue());

            Token timeframe = new Token(TIMEFRAME, "1h", "1h", SPAN);
            assertEquals("1h", timeframe.stringValue());
        }

        @Test
        @DisplayName("of() builds non-literal tokens with a null value")
        void ofFactoryForNonLiterals() {
            Token then = Token.of(THEN, "THEN", SPAN);
            assertNull(then.value());
            assertEquals(THEN, then.type());
        }

        @Test
        @DisplayName("rejects null type, lexeme, or span")
        void rejectsNulls() {
            assertThrows(NullPointerException.class, () -> new Token(null, "x", null, SPAN));
            assertThrows(NullPointerException.class, () -> new Token(IDENT, null, null, SPAN));
            assertThrows(NullPointerException.class, () -> new Token(IDENT, "x", null, null));
        }

        @Test
        @DisplayName("EOF is representable with an empty lexeme and zero-width span")
        void eofToken() {
            // Zero-width span just past the last character: "unexpected end of
            // input" errors still point at a real position.
            Token eof = Token.of(EOF, "", new Span(3, 21, 3, 21, null));
            assertTrue(eof.is(EOF));
        }
    }

    @Nested
    @DisplayName("predicates")
    class Predicates {

        @Test
        @DisplayName("is() matches exactly one type")
        void isMatchesType() {
            Token close = Token.of(CLOSE, "CLOSE", SPAN);
            assertTrue(close.is(CLOSE));
            assertFalse(close.is(IDENT));
        }

        @Test
        @DisplayName("isAny() matches operator sets — how the parser checks classes of token")
        void isAnyMatchesSets() {
            Token lt = Token.of(LT, "<", SPAN);
            assertTrue(lt.isAny(LT, GT, LE));
            assertFalse(lt.isAny(GT, LE));
        }
    }

    @Nested
    @DisplayName("typed value accessors fail loudly on the wrong token kind")
    class TypedAccessors {

        @Test
        @DisplayName("numberValue() on a non-number throws with position info")
        void numberValueOnWrongKind() {
            Token ident = Token.of(IDENT, "rsi", SPAN);
            IllegalStateException ex =
                    assertThrows(IllegalStateException.class, ident::numberValue);
            assertTrue(ex.getMessage().contains("rsi"), "message should echo the lexeme");
            assertTrue(ex.getMessage().contains("1:5"), "message should include the position");
        }

        @Test
        @DisplayName("stringValue() on a number throws")
        void stringValueOnWrongKind() {
            Token number = new Token(NUMBER, "14", 14.0, SPAN);
            assertThrows(IllegalStateException.class, number::stringValue);
        }
    }

    @Test
    @DisplayName("toString is compact and debuggable")
    void toStringFormat() {
        Token t = new Token(NUMBER, "14", 14.0, Span.of(1, 5, 7));
        assertEquals("NUMBER('14')@1:5-1:7", t.toString());
    }
}