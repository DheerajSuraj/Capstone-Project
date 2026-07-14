package com.tsb.compiler;

import java.util.Objects;

/**
 * One compiler message: an error or warning, where it happened, and a stable
 * machine-readable code. This is the single "currency" for problems across
 * the whole pipeline — the lexer, parser, and semantic analyzer all emit
 * Diagnostics into a shared list rather than throwing exceptions, so a
 * single compile can report every problem in the source at once instead of
 * dying on the first.
 *
 * <p>The {@code code} (e.g. {@code "LEX001"}) is stable and machine-facing:
 * the frontend can key special handling off it, tests can assert on it
 * without coupling to message wording, and the report can include an error
 * catalogue. The {@code message} is human-facing and free to be improved at
 * any time without breaking anything.
 *
 * <p>The {@link Span} is what lets the UI point at the exact source range —
 * and, via {@code span.blockId()}, the exact Blockly block — that caused
 * the problem.
 *
 * @param severity ERROR blocks execution; WARNING does not
 * @param code     stable identifier, LEXnnn / PARnnn / SEMnnn by phase
 * @param message  human-readable explanation, ideally with a suggestion
 * @param span     where in the source the problem sits
 */
public record Diagnostic(
        Severity severity,
        String code,
        String message,
        Span span
) {

    /** How serious a diagnostic is. */
    public enum Severity { ERROR, WARNING }

    public Diagnostic {
        Objects.requireNonNull(severity, "severity must not be null");
        Objects.requireNonNull(code, "code must not be null");
        Objects.requireNonNull(message, "message must not be null");
        Objects.requireNonNull(span, "span must not be null — every diagnostic points somewhere");
    }

    /** Factory for the common case. */
    public static Diagnostic error(String code, String message, Span span) {
        return new Diagnostic(Severity.ERROR, code, message, span);
    }

    public static Diagnostic warning(String code, String message, Span span) {
        return new Diagnostic(Severity.WARNING, code, message, span);
    }

    public boolean isError() {
        return severity == Severity.ERROR;
    }

    /** e.g. {@code ERROR[LEX001] 3:7-3:8: unexpected character '@'} */
    @Override
    public String toString() {
        return severity + "[" + code + "] " + span + ": " + message;
    }
}