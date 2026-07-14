package com.tsb.compiler;

import java.util.Map;

/**
 * Every category of token the TSL lexer can produce. The lexer's whole job is
 * to turn a stream of characters into a stream of ({@code TokenType}, text,
 * {@link Span}) triples; the parser then branches on these types.
 *
 * <p><b>Design note — what is deliberately NOT here:</b> indicator names
 * ({@code RSI}, {@code SMA}, ...) and built-in function names
 * ({@code CROSSOVER}, {@code MAX}, ...) are lexed as plain {@link #IDENT} and
 * resolved during semantic analysis, not in the lexer. This keeps the lexer
 * stable when indicators are added, and lets the analyzer produce far better
 * errors ("unknown indicator 'RSII' — did you mean 'RSI'?") than a lexer ever
 * could. The language grammar is unchanged; only the phase that enforces it
 * differs.
 *
 * <p><b>Case sensitivity:</b> TSL keywords are case-sensitive, exactly as they
 * appear in the grammar — structural keywords are lowercase ({@code strategy},
 * {@code let}, {@code rule}, {@code qty}), statement/logic keywords are
 * uppercase ({@code IF}, {@code BUY}, {@code AND}). {@code If} or {@code buy}
 * is just an identifier, and the parser's error message will make the mistake
 * obvious.
 */
public enum TokenType {

    // ── Literals ────────────────────────────────────────────────────────
    /** Numeric literal: {@code 30}, {@code 0.5}, {@code 10000}. */
    NUMBER,
    /** Percent literal, lexed as ONE token: {@code 25%}, {@code 0.1%}. */
    PERCENT,
    /** Double-quoted string: {@code "RSI Mean Reversion"}. */
    STRING,
    /** Timeframe literal: {@code 1h}, {@code 4h}, {@code 1d}, {@code 15m}. */
    TIMEFRAME,
    /** Identifier: user variables and (until semantic analysis) indicator
     *  and function names — {@code rsi}, {@code trend}, {@code RSI}. */
    IDENT,

    // ── Structural keywords (lowercase in the grammar) ──────────────────
    STRATEGY,       // strategy
    LET,            // let
    RULE,           // rule
    QTY,            // qty

    // ── Statement keywords (uppercase in the grammar) ───────────────────
    IF,
    THEN,
    ELSE,
    BUY,
    SELL,
    SET,
    ALL,
    OF,
    EQUITY,
    POSITION,
    STOPLOSS,
    TAKEPROFIT,
    TRAILING,

    // ── Price references (closed set; typed as Series) ──────────────────
    OPEN,
    HIGH,
    LOW,
    CLOSE,
    VOLUME,

    // ── Logical operators (keywords, not symbols) ───────────────────────
    AND,
    OR,
    NOT,

    // ── Comparison operators ────────────────────────────────────────────
    LT,             // <
    GT,             // >
    LE,             // <=
    GE,             // >=
    EQ_EQ,          // ==   (comparison — distinct from ASSIGN)
    BANG_EQ,        // !=

    // ── Arithmetic operators ────────────────────────────────────────────
    PLUS,           // +
    MINUS,          // -    (also unary negation; parser decides)
    STAR,           // *
    SLASH,          // /

    // ── Assignment & punctuation ────────────────────────────────────────
    ASSIGN,         // =    (config entries, let, SET ... =)
    LPAREN,         // (
    RPAREN,         // )
    LBRACE,         // {
    RBRACE,         // }
    LBRACKET,       // [    (lookback: RSI(14)[1])
    RBRACKET,       // ]
    COMMA,          // ,

    // ── Control ─────────────────────────────────────────────────────────
    /** End of input. The lexer always emits exactly one EOF as the final
     *  token, so the parser can peek without null checks. */
    EOF;

    /**
     * Reserved words → token types. The lexer scans an identifier-shaped word
     * first, then consults this table: if present it is a keyword, otherwise
     * it is an {@link #IDENT}. This "maximal munch then classify" approach is
     * the standard way hand-written lexers handle keywords.
     *
     * <p>{@link Map#of} would cap out at 10 entries; {@code Map.ofEntries}
     * has no such limit and the result is immutable.
     */
    public static final Map<String, TokenType> KEYWORDS = Map.ofEntries(
            Map.entry("strategy", STRATEGY),
            Map.entry("let", LET),
            Map.entry("rule", RULE),
            Map.entry("qty", QTY),
            Map.entry("IF", IF),
            Map.entry("THEN", THEN),
            Map.entry("ELSE", ELSE),
            Map.entry("BUY", BUY),
            Map.entry("SELL", SELL),
            Map.entry("SET", SET),
            Map.entry("ALL", ALL),
            Map.entry("OF", OF),
            Map.entry("EQUITY", EQUITY),
            Map.entry("POSITION", POSITION),
            Map.entry("STOPLOSS", STOPLOSS),
            Map.entry("TAKEPROFIT", TAKEPROFIT),
            Map.entry("TRAILING", TRAILING),
            Map.entry("OPEN", OPEN),
            Map.entry("HIGH", HIGH),
            Map.entry("LOW", LOW),
            Map.entry("CLOSE", CLOSE),
            Map.entry("VOLUME", VOLUME),
            Map.entry("AND", AND),
            Map.entry("OR", OR),
            Map.entry("NOT", NOT)
    );
}