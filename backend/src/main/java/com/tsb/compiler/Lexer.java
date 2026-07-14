package com.tsb.compiler;


import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The TSL lexer: one linear left-to-right pass over the source characters,
 * producing a token stream plus any diagnostics. O(n) in source length —
 * each character is examined a constant number of times.
 *
 * <p><b>Maximal munch:</b> at each position the lexer consumes the longest
 * character sequence that forms a valid token. {@code <=} is one LE token,
 * not LT then ASSIGN; {@code 25%} is one PERCENT; {@code 1h} one TIMEFRAME.
 *
 * <p><b>Error recovery, not exceptions:</b> a bad character produces a
 * {@link Diagnostic}, is skipped, and scanning continues — so one compile
 * reports every lexical problem in the file, not just the first. The parser
 * downstream applies the same philosophy.
 *
 * <p><b>Positions:</b> the lexer is the only component that sees raw
 * characters, so it is the single origin of every {@link Span} in the
 * system. Lines and columns are 1-based; a token's span end column is
 * exclusive (see {@link Span}).
 *
 * <p>Usage: {@code new Lexer(source).scan()} — a Lexer instance is
 * single-use, one per compile.
 */
public final class Lexer {

    /**
     * Everything one scan produces. Tokens always end with exactly one EOF
     * token (even for empty or all-error input), so the parser can peek
     * freely without null checks. If {@code diagnostics} contains any ERROR
     * the token stream is best-effort and compilation must not proceed past
     * parsing for execution purposes.
     */
    public record LexResult(List<Token> tokens, List<Diagnostic> diagnostics) {
        public boolean hasErrors() {
            return diagnostics.stream().anyMatch(Diagnostic::isError);
        }
    }

    private final String source;

    private int pos = 0;    // 0-based char offset into source (internal only)
    private int line = 1;   // 1-based, what Spans expose
    private int col = 1;    // 1-based, what Spans expose

    // Where the token currently being scanned started.
    private int startLine = 1;
    private int startCol = 1;

    private final List<Token> tokens = new ArrayList<>();
    private final List<Diagnostic> diagnostics = new ArrayList<>();

    public Lexer(String source) {
        this.source = Objects.requireNonNull(source, "source must not be null");
    }

    /** Runs the scan. Call exactly once per Lexer instance. */
    public LexResult scan() {
        while (true) {
            skipWhitespaceAndComments();
            if (atEnd()) {
                break;
            }
            startLine = line;
            startCol = col;
            scanToken();
        }
        // EOF gets a zero-width span just past the last character, so
        // "unexpected end of input" errors still point at a real position.
        tokens.add(new Token(TokenType.EOF, "", null,
                new Span(line, col, line, col, null)));
        return new LexResult(List.copyOf(tokens), List.copyOf(diagnostics));
    }

    // ── Dispatch ────────────────────────────────────────────────────────

    private void scanToken() {
        char c = advance();
        switch (c) {
            // Single-character punctuation — no lookahead needed.
            case '(' -> add(TokenType.LPAREN, "(");
            case ')' -> add(TokenType.RPAREN, ")");
            case '{' -> add(TokenType.LBRACE, "{");
            case '}' -> add(TokenType.RBRACE, "}");
            case '[' -> add(TokenType.LBRACKET, "[");
            case ']' -> add(TokenType.RBRACKET, "]");
            case ',' -> add(TokenType.COMMA, ",");
            case '+' -> add(TokenType.PLUS, "+");
            case '-' -> add(TokenType.MINUS, "-");
            case '*' -> add(TokenType.STAR, "*");
            // '//' comments are consumed in skipWhitespaceAndComments, so a
            // slash reaching here is always the division operator.
            case '/' -> add(TokenType.SLASH, "/");

            // One-character lookahead: maximal munch on the '=' suffix.
            case '<' -> {
                if (match('=')) add(TokenType.LE, "<=");
                else add(TokenType.LT, "<");
            }
            case '>' -> {
                if (match('=')) add(TokenType.GE, ">=");
                else add(TokenType.GT, ">");
            }
            case '=' -> {
                if (match('=')) add(TokenType.EQ_EQ, "==");
                else add(TokenType.ASSIGN, "=");
            }
            case '!' -> {
                if (match('=')) {
                    add(TokenType.BANG_EQ, "!=");
                } else {
                    // '!' alone is not a TSL operator (negation is the NOT
                    // keyword). Report, skip, continue.
                    error("LEX002",
                            "unexpected character '!' — did you mean '!=' ? (logical negation is 'NOT')");
                }
            }

            case '"' -> string();

            default -> {
                if (isDigit(c)) {
                    number();
                } else if (isLetter(c)) {
                    identifierOrKeyword();
                } else {
                    // Unknown character: diagnose with the exact char, skip
                    // it (advance() already consumed it), keep scanning.
                    error("LEX001", "unexpected character '" + c + "'");
                }
            }
        }
    }

    // ── Compound tokens ─────────────────────────────────────────────────

    /**
     * Numbers and everything that starts like one, by maximal munch:
     * {@code 14} NUMBER, {@code 0.5} NUMBER, {@code 25%} PERCENT,
     * {@code 1h} TIMEFRAME. A stray letter suffix ({@code 3x}) is a
     * diagnostic, consumed entirely so scanning resumes cleanly after it.
     */
    private void number() {
        boolean isDecimal = false;
        while (isDigit(peek())) {
            advance();
        }
        if (peek() == '.' && isDigit(peekNext())) {
            isDecimal = true;
            advance(); // the '.'
            while (isDigit(peek())) {
                advance();
            }
        }

        if (peek() == '%') {
            advance();
            String lexeme = lexemeSoFar();
            double value = Double.parseDouble(lexeme.substring(0, lexeme.length() - 1));
            // PERCENT carries the number AS WRITTEN (25% -> 25.0). The /100
            // conversion is owned by semantic analysis, in one place only.
            tokens.add(new Token(TokenType.PERCENT, lexeme, value, currentSpan()));
            return;
        }

        if (isLetter(peek())) {
            char suffix = peek();
            boolean timeframeSuffix = suffix == 'm' || suffix == 'h' || suffix == 'd';
            // A timeframe is digits + one suffix letter and nothing more:
            // '1h' yes; '1.5h', '1hr', '3x' no.
            if (!isDecimal && timeframeSuffix && !isAlphaNumeric(peekAt(1))) {
                advance();
                String lexeme = lexemeSoFar();
                tokens.add(new Token(TokenType.TIMEFRAME, lexeme, lexeme, currentSpan()));
                return;
            }
            // Bad suffix: consume the whole alphanumeric tail so we report
            // ONE error for '3xyz', not an error plus a bogus IDENT token.
            while (isAlphaNumeric(peek())) {
                advance();
            }
            error("LEX003", "invalid numeric literal '" + lexemeSoFar()
                    + "' (timeframes are digits plus m/h/d, e.g. 15m, 1h, 1d)");
            return;
        }

        String lexeme = lexemeSoFar();
        tokens.add(new Token(TokenType.NUMBER, lexeme,
                Double.parseDouble(lexeme), currentSpan()));
    }

    /**
     * Identifier-shaped words: scan by maximal munch, then classify via the
     * keyword table. Grammar: an IDENT starts with a letter and continues
     * with letters, digits, or underscores. Indicator names (RSI, SMA, ...)
     * deliberately come out as IDENT here — semantic analysis resolves them.
     */
    private void identifierOrKeyword() {
        while (isAlphaNumeric(peek())) {
            advance();
        }
        String lexeme = lexemeSoFar();
        TokenType keyword = TokenType.KEYWORDS.get(lexeme);
        add(keyword != null ? keyword : TokenType.IDENT, lexeme);
    }

    /**
     * Double-quoted string, single line, no escape sequences (TSL strings
     * only ever hold names like a strategy title — escapes are complexity
     * the language doesn't need). Unterminated strings are diagnosed and
     * scanning resumes at the newline/EOF, so later lines still lex.
     */
    private void string() {
        StringBuilder value = new StringBuilder();
        while (!atEnd() && peek() != '"' && peek() != '\n') {
            value.append(advance());
        }
        if (atEnd() || peek() == '\n') {
            error("LEX004", "unterminated string literal");
            return;
        }
        advance(); // closing quote
        // Lexeme keeps the quotes (echo what the user wrote); value drops them.
        tokens.add(new Token(TokenType.STRING, lexemeSoFar(),
                value.toString(), currentSpan()));
    }

    // ── Whitespace & comments ───────────────────────────────────────────

    /**
     * Consumes spaces, tabs, carriage returns, newlines, and '//' line
     * comments. This is the ONLY place newlines are consumed, and therefore
     * the only place the line counter moves — keeping position bookkeeping
     * in exactly one spot.
     */
    private void skipWhitespaceAndComments() {
        while (!atEnd()) {
            char c = peek();
            switch (c) {
                case ' ', '\t', '\r' -> advance();
                case '\n' -> {
                    pos++;
                    line++;
                    col = 1;
                }
                case '/' -> {
                    if (peekAt(1) == '/') {
                        while (!atEnd() && peek() != '\n') {
                            advance();
                        }
                    } else {
                        return; // a real SLASH token; let scanToken have it
                    }
                }
                default -> {
                    return;
                }
            }
        }
    }

    // ── Character machinery ─────────────────────────────────────────────

    private boolean atEnd() {
        return pos >= source.length();
    }

    /** Consumes and returns the current character, moving the column. */
    private char advance() {
        char c = source.charAt(pos);
        pos++;
        col++;
        return c;
    }

    /** Current character without consuming; NUL sentinel at end of input. */
    private char peek() {
        return pos < source.length() ? source.charAt(pos) : '\0';
    }

    private char peekNext() {
        return peekAt(1);
    }

    private char peekAt(int ahead) {
        int i = pos + ahead;
        return i < source.length() ? source.charAt(i) : '\0';
    }

    /** Conditional consume: eats the next char only if it matches. */
    private boolean match(char expected) {
        if (!atEnd() && source.charAt(pos) == expected) {
            advance();
            return true;
        }
        return false;
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    private static boolean isLetter(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
    }

    private static boolean isAlphaNumeric(char c) {
        return isLetter(c) || isDigit(c) || c == '_';
    }

    // ── Token & diagnostic emission ─────────────────────────────────────

    /** The raw source text from the current token's start to here. */
    private String lexemeSoFar() {
        // startCol/col are 1-based columns on the SAME line for every token
        // (no TSL token spans lines), so the offset math is direct.
        int startOffset = pos - (col - startCol);
        return source.substring(startOffset, pos);
    }

    private Span currentSpan() {
        return new Span(startLine, startCol, line, col, null);
    }

    private void add(TokenType type, String lexeme) {
        tokens.add(Token.of(type, lexeme, currentSpan()));
    }

    private void error(String code, String message) {
        diagnostics.add(Diagnostic.error(code, message, currentSpan()));
    }
}