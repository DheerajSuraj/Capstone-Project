package com.tsb.compiler;

import java.util.Objects;

/**
 * One token produced by the lexer: a {@link TokenType}, the exact source text
 * it came from (the <i>lexeme</i>), where it came from (its {@link Span}), and
 * — for literal tokens — the already-parsed value.
 *
 * <p><b>Why carry a parsed {@code value} as well as the raw lexeme?</b> So
 * that literal parsing happens exactly once, in the lexer. For a NUMBER token
 * the lexeme is {@code "14"} and the value is {@code 14.0}; for a PERCENT
 * token the lexeme is {@code "25%"} and the value is {@code 25.0}; for a
 * STRING the lexeme includes the quotes and the value does not. If the token
 * carried only text, the parser (and any later consumer) would re-parse it —
 * multiple implementations of the same conversion is the bug factory this
 * codebase consistently avoids. The raw lexeme is still kept because error
 * messages should echo exactly what the user wrote.
 *
 * <p>{@code value} is typed per {@link TokenType}:
 * <ul>
 *   <li>{@code NUMBER}, {@code PERCENT} → {@link Double} (for PERCENT, the
 *       number as written: {@code 25%} → {@code 25.0}, not {@code 0.25};
 *       the semantic analyzer owns the /100 conversion so the rule lives in
 *       one place)</li>
 *   <li>{@code STRING} → {@link String} without the surrounding quotes</li>
 *   <li>{@code TIMEFRAME} → {@link String}, the lexeme as written
 *       ({@code "1h"}); validated against the supported set later</li>
 *   <li>everything else → {@code null}</li>
 * </ul>
 *
 * <p>The EOF token has a zero-width span positioned just past the final
 * character, so "unexpected end of input" errors still point somewhere real.
 *
 * @param type   the lexical category
 * @param lexeme the exact source text (never null; empty only for EOF)
 * @param value  pre-parsed literal value, or null for non-literals
 * @param span   where in the source this token sits (never null)
 */
public record Token(
        TokenType type,
        String lexeme,
        Object value,
        Span span
) {

    /** Validates the invariants every token must satisfy. */
    public Token {
        Objects.requireNonNull(type, "token type must not be null");
        Objects.requireNonNull(lexeme, "lexeme must not be null (use \"\" for EOF)");
        Objects.requireNonNull(span, "span must not be null — every token knows where it came from");
    }

    /** Convenience factory for the (very common) non-literal token. */
    public static Token of(TokenType type, String lexeme, Span span) {
        return new Token(type, lexeme, null, span);
    }

    /** Readable predicate for the parser: {@code if (peek().is(THEN)) ...} */
    public boolean is(TokenType t) {
        return type == t;
    }

    /** True if this token is any of the given types. Used for operator sets,
     *  e.g. {@code isAny(LT, GT, LE, GE, EQ_EQ, BANG_EQ)}. */
    public boolean isAny(TokenType... types) {
        for (TokenType t : types) {
            if (type == t) {
                return true;
            }
        }
        return false;
    }

    /** The numeric value of a NUMBER or PERCENT token.
     *  @throws IllegalStateException if called on any other token type —
     *  failing loudly beats a silent ClassCastException three calls later. */
    public double numberValue() {
        if (!(value instanceof Double d)) {
            throw new IllegalStateException(
                    "numberValue() called on " + type + " token '" + lexeme + "' at " + span);
        }
        return d;
    }

    /** The string value of a STRING or TIMEFRAME token.
     *  @throws IllegalStateException if called on any other token type. */
    public String stringValue() {
        if (!(value instanceof String s)) {
            throw new IllegalStateException(
                    "stringValue() called on " + type + " token '" + lexeme + "' at " + span);
        }
        return s;
    }

    /** Compact debug form, e.g. {@code NUMBER('14')@1:5-1:7}. Tests and log
     *  output read much better with this than the default record toString. */
    @Override
    public String toString() {
        return type + "('" + lexeme + "')@" + span;
    }
}