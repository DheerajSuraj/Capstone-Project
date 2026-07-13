package com.tsb.compiler;

/**
 * A source-code position: the location a {@link Token} or AST node was read
 * from. Every token and every AST node in the TSL compiler carries a Span so
 * that any diagnostic can point back at the exact place in the source that
 * caused it.
 *
 * <p><b>Line and column are 1-based</b> (line 1, column 1 is the first
 * character), because that is what humans and text editors expect — an error
 * that says "line 0" reads as a bug. Internally the lexer tracks a 0-based
 * character offset for array indexing, but what surfaces in a Span is the
 * human-facing 1-based coordinate.
 *
 * <p>A Span covers a range: it starts at ({@code startLine}, {@code startCol})
 * and ends at ({@code endLine}, {@code endCol}). A single-character token has a
 * start and end one column apart; a multi-line expression node can span from
 * one line to another. The end is <b>exclusive</b> of the character after the
 * token, matching the half-open interval convention used almost everywhere in
 * text tooling (e.g. an editor selection).
 *
 * <p>The {@code blockId} threads a Blockly block identifier through the whole
 * pipeline. When the visual builder generates DSL source, each fragment records
 * which block produced it; that id rides along in the Span so a semantic error
 * discovered in the backend can tell the frontend which block to highlight in
 * red. For plain hand-written {@code .tsl} text (no visual blocks), it is
 * simply {@code null}.
 *
 * <p>Being a {@code record} makes this immutable and gives us correct
 * {@code equals}/{@code hashCode} (used heavily in tests) and a readable
 * {@code toString} at no cost.
 *
 * @param startLine 1-based line where the span begins
 * @param startCol  1-based column where the span begins
 * @param endLine   1-based line where the span ends
 * @param endCol    1-based column just past where the span ends (exclusive)
 * @param blockId   originating Blockly block id, or {@code null} for text source
 */
public record Span(
        int startLine,
        int startCol,
        int endLine,
        int endCol,
        String blockId
) {

    /**
     * Compact canonical constructor — validates the invariants that must hold
     * for every Span. Failing loudly here (at construction) turns an
     * impossible position into an immediate, obvious error instead of a
     * confusing downstream symptom.
     */
    public Span {
        if (startLine < 1 || startCol < 1 || endLine < 1 || endCol < 1) {
            throw new IllegalArgumentException(
                    "Span coordinates are 1-based and must be >= 1, got "
                            + "(" + startLine + ":" + startCol + " -> "
                            + endLine + ":" + endCol + ")");
        }
        if (endLine < startLine
                || (endLine == startLine && endCol < startCol)) {
            throw new IllegalArgumentException(
                    "Span end must not precede its start, got "
                            + "(" + startLine + ":" + startCol + " -> "
                            + endLine + ":" + endCol + ")");
        }
    }

    /**
     * Convenience factory for a span on a single line, with no block id.
     * The common case in the lexer: a token that lives entirely on one line.
     *
     * @param line     1-based line
     * @param startCol 1-based starting column
     * @param endCol   1-based column just past the token (exclusive)
     */
    public static Span of(int line, int startCol, int endCol) {
        return new Span(line, startCol, line, endCol, null);
    }

    /**
     * Returns a copy of this span tagged with the given Blockly block id.
     * Used when the source was generated from the visual builder and we want
     * errors to map back to a specific block. Immutable-friendly: returns a
     * new Span rather than mutating this one.
     *
     * @param blockId the originating block id
     * @return a new Span identical to this one but carrying {@code blockId}
     */
    public Span withBlockId(String blockId) {
        return new Span(startLine, startCol, endLine, endCol, blockId);
    }

    /**
     * Produces a new span stretching from the start of this span to the end of
     * {@code other}. Used by the parser to build a node's span from its
     * children — e.g. a binary expression's span runs from the start of its
     * left operand to the end of its right operand.
     *
     * <p>The resulting span keeps <i>this</i> span's {@code blockId}, since the
     * left/earlier fragment is treated as the node's origin for highlighting.
     *
     * @param other the span whose end becomes the merged span's end
     * @return a span covering both
     */
    public Span merge(Span other) {
        return new Span(startLine, startCol, other.endLine, other.endCol, blockId);
    }

    /**
     * Human-readable form for diagnostics and logs, e.g. {@code "7:12-7:19"}.
     * The block id is intentionally omitted here — it is machine-facing routing
     * metadata, not something a user reading an error message needs to see.
     */
    @Override
    public String toString() {
        return startLine + ":" + startCol + "-" + endLine + ":" + endCol;
    }
}