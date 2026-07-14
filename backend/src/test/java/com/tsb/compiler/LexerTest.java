package com.tsb.compiler;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.tsb.compiler.TokenType.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Lexer")
class LexerTest {

    // ── Helpers ─────────────────────────────────────────────────────────

    /** Scans source that must produce NO diagnostics; returns its tokens. */
    private static List<Token> lexClean(String source) {
        Lexer.LexResult result = new Lexer(source).scan();
        assertEquals(List.of(), result.diagnostics(),
                "expected a clean scan for: " + source);
        return result.tokens();
    }

    /** Just the token types, EOF included — the usual assertion shape. */
    private static List<TokenType> types(String source) {
        return lexClean(source).stream().map(Token::type).toList();
    }

    // ── The basics ──────────────────────────────────────────────────────

    @Nested
    @DisplayName("fundamentals")
    class Fundamentals {

        @Test
        @DisplayName("empty input yields exactly one EOF token")
        void emptyInput() {
            assertEquals(List.of(EOF), types(""));
        }

        @Test
        @DisplayName("whitespace-only input yields exactly one EOF token")
        void whitespaceOnly() {
            assertEquals(List.of(EOF), types("   \t  \r\n  \n"));
        }

        @Test
        @DisplayName("EOF is always the final token")
        void eofAlwaysLast() {
            List<Token> tokens = lexClean("BUY");
            assertEquals(EOF, tokens.get(tokens.size() - 1).type());
        }

        @Test
        @DisplayName("// comments are skipped entirely")
        void commentsSkipped() {
            assertEquals(List.of(BUY, EOF),
                    types("// entry signal\nBUY // all in"));
        }

        @Test
        @DisplayName("the flagship line: IF RSI(14) < 30 THEN BUY")
        void flagshipLine() {
            assertEquals(
                    List.of(IF, IDENT, LPAREN, NUMBER, RPAREN, LT, NUMBER,
                            THEN, BUY, EOF),
                    types("IF RSI(14) < 30 THEN BUY"));
        }
    }

    // ── Keywords vs identifiers ─────────────────────────────────────────

    @Nested
    @DisplayName("keywords and identifiers")
    class KeywordsAndIdents {

        @Test
        @DisplayName("keywords are recognised; indicator names stay IDENT")
        void keywordsVsIndicators() {
            List<Token> tokens = lexClean("IF CLOSE AND RSI SMA myVar");
            assertEquals(
                    List.of(IF, CLOSE, AND, IDENT, IDENT, IDENT, EOF),
                    tokens.stream().map(Token::type).toList());
            assertEquals("RSI", tokens.get(3).lexeme());
        }

        @Test
        @DisplayName("keywords are case-sensitive: 'buy' and 'If' are identifiers")
        void caseSensitivity() {
            assertEquals(List.of(IDENT, IDENT, BUY, EOF), types("buy If BUY"));
        }

        @Test
        @DisplayName("identifiers may contain digits and underscores after the first letter")
        void identifierShapes() {
            List<Token> tokens = lexClean("fast_ma sma200 x1_y2");
            assertEquals(List.of(IDENT, IDENT, IDENT, EOF),
                    tokens.stream().map(Token::type).toList());
            assertEquals("fast_ma", tokens.get(0).lexeme());
            assertEquals("sma200", tokens.get(1).lexeme());
        }
    }

    // ── Literals ────────────────────────────────────────────────────────

    @Nested
    @DisplayName("literals")
    class Literals {

        @Test
        @DisplayName("integers and decimals parse to their double value")
        void numbers() {
            List<Token> tokens = lexClean("14 0.5 10000");
            assertEquals(14.0, tokens.get(0).numberValue());
            assertEquals(0.5, tokens.get(1).numberValue());
            assertEquals(10000.0, tokens.get(2).numberValue());
        }

        @Test
        @DisplayName("percent is ONE token; value is the number as written (25% -> 25.0)")
        void percent() {
            List<Token> tokens = lexClean("25% 0.1%");
            assertEquals(PERCENT, tokens.get(0).type());
            assertEquals("25%", tokens.get(0).lexeme());
            assertEquals(25.0, tokens.get(0).numberValue());
            assertEquals(0.1, tokens.get(1).numberValue());
        }

        @Test
        @DisplayName("timeframes 15m / 1h / 1d are single tokens")
        void timeframes() {
            List<Token> tokens = lexClean("15m 1h 1d");
            assertEquals(List.of(TIMEFRAME, TIMEFRAME, TIMEFRAME, EOF),
                    tokens.stream().map(Token::type).toList());
            assertEquals("1h", tokens.get(1).stringValue());
        }

        @Test
        @DisplayName("strings: lexeme keeps quotes, value drops them")
        void strings() {
            Token s = lexClean("\"RSI Mean Reversion\"").get(0);
            assertEquals(STRING, s.type());
            assertEquals("\"RSI Mean Reversion\"", s.lexeme());
            assertEquals("RSI Mean Reversion", s.stringValue());
        }
    }

    // ── Operators & maximal munch ───────────────────────────────────────

    @Nested
    @DisplayName("operators (maximal munch)")
    class Operators {

        @Test
        @DisplayName("two-char operators win over their one-char prefixes")
        void twoCharOperators() {
            assertEquals(
                    List.of(LE, GE, EQ_EQ, BANG_EQ, LT, GT, ASSIGN, EOF),
                    types("<= >= == != < > ="));
        }

        @Test
        @DisplayName("'=' (assign) and '==' (compare) are distinct tokens")
        void assignVsEquals() {
            assertEquals(List.of(IDENT, ASSIGN, NUMBER, EOF), types("fee = 5"));
            assertEquals(List.of(CLOSE, EQ_EQ, NUMBER, EOF), types("CLOSE == 5"));
        }

        @Test
        @DisplayName("arithmetic, punctuation, and lookback brackets")
        void punctuation() {
            assertEquals(
                    List.of(IDENT, LPAREN, NUMBER, RPAREN, LBRACKET, NUMBER,
                            RBRACKET, PLUS, MINUS, STAR, SLASH, COMMA, LBRACE,
                            RBRACE, EOF),
                    types("RSI(14)[1] + - * / , { }"));
        }

        @Test
        @DisplayName("division is not eaten by the comment skipper")
        void slashVsComment() {
            assertEquals(List.of(CLOSE, SLASH, NUMBER, EOF), types("CLOSE / 2"));
        }
    }

    // ── Spans ───────────────────────────────────────────────────────────

    @Nested
    @DisplayName("spans")
    class Spans {

        @Test
        @DisplayName("a token's span has 1-based start and exclusive end column")
        void singleLineSpan() {
            // "IF RSI" -> IF at cols 1-3 (exclusive), RSI at cols 4-7
            List<Token> tokens = lexClean("IF RSI");
            assertEquals(Span.of(1, 1, 3), tokens.get(0).span());
            assertEquals(Span.of(1, 4, 7), tokens.get(1).span());
        }

        @Test
        @DisplayName("line numbers advance across newlines, columns reset")
        void multiLinePositions() {
            List<Token> tokens = lexClean("BUY\n  SELL");
            assertEquals(Span.of(1, 1, 4), tokens.get(0).span());
            assertEquals(Span.of(2, 3, 7), tokens.get(1).span());
        }

        @Test
        @DisplayName("EOF has a zero-width span just past the last character")
        void eofSpan() {
            List<Token> tokens = lexClean("BUY");
            Span eof = tokens.get(1).span();
            assertEquals(eof.startCol(), eof.endCol());
            assertEquals(4, eof.startCol());
        }
    }

    // ── Error recovery ──────────────────────────────────────────────────

    @Nested
    @DisplayName("error recovery — collect, skip, continue")
    class ErrorRecovery {

        @Test
        @DisplayName("an unknown character is diagnosed and scanning continues")
        void unknownCharacter() {
            Lexer.LexResult r = new Lexer("BUY @ SELL").scan();
            assertEquals(1, r.diagnostics().size());
            assertEquals("LEX001", r.diagnostics().get(0).code());
            assertTrue(r.diagnostics().get(0).message().contains("'@'"));
            // The tokens around the bad char still lexed.
            assertEquals(List.of(BUY, SELL, EOF),
                    r.tokens().stream().map(Token::type).toList());
        }

        @Test
        @DisplayName("multiple problems are ALL reported in one scan")
        void multipleErrors() {
            Lexer.LexResult r = new Lexer("@ BUY # SELL $").scan();
            assertEquals(3, r.diagnostics().size());
            assertTrue(r.hasErrors());
        }

        @Test
        @DisplayName("lone '!' suggests '!=' and mentions NOT")
        void loneBang() {
            Lexer.LexResult r = new Lexer("CLOSE ! 5").scan();
            assertEquals("LEX002", r.diagnostics().get(0).code());
            assertTrue(r.diagnostics().get(0).message().contains("NOT"));
        }

        @Test
        @DisplayName("bad numeric suffix is one error, consumed whole")
        void badNumericSuffix() {
            Lexer.LexResult r = new Lexer("3xyz BUY").scan();
            assertEquals(1, r.diagnostics().size());
            assertEquals("LEX003", r.diagnostics().get(0).code());
            // Recovery resumes cleanly: BUY still lexes, no bogus IDENT from 'xyz'.
            assertEquals(List.of(BUY, EOF),
                    r.tokens().stream().map(Token::type).toList());
        }

        @Test
        @DisplayName("unterminated string is diagnosed; the next line still lexes")
        void unterminatedString() {
            Lexer.LexResult r = new Lexer("\"oops\nBUY").scan();
            assertEquals("LEX004", r.diagnostics().get(0).code());
            assertEquals(List.of(BUY, EOF),
                    r.tokens().stream().map(Token::type).toList());
        }

        @Test
        @DisplayName("diagnostic spans point at the offending character")
        void diagnosticSpan() {
            Lexer.LexResult r = new Lexer("BUY @").scan();
            assertEquals(Span.of(1, 5, 6), r.diagnostics().get(0).span());
        }
    }

    // ── A whole strategy ────────────────────────────────────────────────

    @Test
    @DisplayName("scans a complete mini strategy without diagnostics")
    void wholeStrategy() {
        String source = """
                strategy "RSI Mean Reversion" {
                    symbol = BTCUSDT
                    timeframe = 1h
                    capital = 10000
                    fee = 0.1%

                    let rsi = RSI(14)

                    rule entry {
                        IF rsi < 30 AND CLOSE > SMA(CLOSE, 200)
                        THEN BUY qty = 25% OF EQUITY
                    }

                    rule exit {
                        IF rsi > 70 THEN SELL ALL
                    }
                }
                """;
        List<Token> tokens = lexClean(source);
        assertEquals(EOF, tokens.get(tokens.size() - 1).type());
        // Spot-checks rather than a brittle full-sequence assertion:
        assertTrue(tokens.stream().anyMatch(t -> t.is(STRATEGY)));
        assertTrue(tokens.stream().anyMatch(t -> t.is(PERCENT)
                && t.numberValue() == 0.1));
        assertTrue(tokens.stream().anyMatch(t -> t.is(TIMEFRAME)));
        assertTrue(tokens.stream().anyMatch(t -> t.is(STRING)
                && t.stringValue().equals("RSI Mean Reversion")));
        assertTrue(tokens.stream().filter(t -> t.is(RULE)).count() == 2);
    }
}