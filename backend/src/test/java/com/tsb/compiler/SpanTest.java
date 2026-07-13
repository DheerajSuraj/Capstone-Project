package com.tsb.compiler;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests for {@link Span}. These pin down the behaviour the rest of the
 * compiler depends on: valid construction, rejection of impossible positions,
 * the {@code of} / {@code withBlockId} / {@code merge} helpers, and the
 * human-readable {@code toString}.
 */
@DisplayName("Span")
class SpanTest {

    @Nested
    @DisplayName("construction and validation")
    class Construction {

        @Test
        @DisplayName("accepts a valid single-line span")
        void acceptsValidSpan() {
            Span s = new Span(7, 12, 7, 19, null);
            assertEquals(7, s.startLine());
            assertEquals(12, s.startCol());
            assertEquals(7, s.endLine());
            assertEquals(19, s.endCol());
            assertNull(s.blockId());
        }

        @Test
        @DisplayName("accepts a multi-line span")
        void acceptsMultiLineSpan() {
            Span s = new Span(3, 5, 6, 2, null);
            assertEquals(3, s.startLine());
            assertEquals(6, s.endLine());
        }

        @Test
        @DisplayName("rejects coordinates below 1 (they are 1-based)")
        void rejectsNonPositiveCoordinates() {
            assertThrows(IllegalArgumentException.class,
                    () -> new Span(0, 1, 1, 1, null));
            assertThrows(IllegalArgumentException.class,
                    () -> new Span(1, 0, 1, 1, null));
            assertThrows(IllegalArgumentException.class,
                    () -> new Span(1, 1, 0, 1, null));
            assertThrows(IllegalArgumentException.class,
                    () -> new Span(1, 1, 1, 0, null));
        }

        @Test
        @DisplayName("rejects an end that precedes its start on the same line")
        void rejectsEndBeforeStartSameLine() {
            assertThrows(IllegalArgumentException.class,
                    () -> new Span(5, 10, 5, 4, null));
        }

        @Test
        @DisplayName("rejects an end line before the start line")
        void rejectsEndLineBeforeStartLine() {
            assertThrows(IllegalArgumentException.class,
                    () -> new Span(5, 1, 4, 1, null));
        }

        @Test
        @DisplayName("allows a zero-width span (start == end)")
        void allowsZeroWidthSpan() {
            // A start-of-file or synthetic marker may have start == end.
            Span s = new Span(1, 1, 1, 1, null);
            assertEquals(s.startCol(), s.endCol());
        }
    }

    @Nested
    @DisplayName("of() factory")
    class OfFactory {

        @Test
        @DisplayName("builds a single-line span with no block id")
        void buildsSingleLineSpan() {
            Span s = Span.of(4, 3, 8);
            assertEquals(4, s.startLine());
            assertEquals(4, s.endLine());
            assertEquals(3, s.startCol());
            assertEquals(8, s.endCol());
            assertNull(s.blockId());
        }
    }

    @Nested
    @DisplayName("withBlockId()")
    class WithBlockId {

        @Test
        @DisplayName("returns a copy carrying the block id, leaving the original unchanged")
        void tagsBlockIdImmutably() {
            Span original = Span.of(2, 1, 5);
            Span tagged = original.withBlockId("blk_rsi_3f2a");

            assertEquals("blk_rsi_3f2a", tagged.blockId());
            assertNull(original.blockId(), "original must stay untouched (immutability)");
            // Positions are preserved.
            assertEquals(original.startLine(), tagged.startLine());
            assertEquals(original.endCol(), tagged.endCol());
        }
    }

    @Nested
    @DisplayName("merge()")
    class Merge {

        @Test
        @DisplayName("spans from this start to the other's end")
        void mergesRange() {
            // e.g. left operand "RSI(14)" at 1:1-1:8, right operand "30" at 1:12-1:14
            Span left = Span.of(1, 1, 8);
            Span right = Span.of(1, 12, 14);

            Span merged = left.merge(right);

            assertEquals(1, merged.startLine());
            assertEquals(1, merged.startCol());   // from left
            assertEquals(1, merged.endLine());
            assertEquals(14, merged.endCol());    // from right
        }

        @Test
        @DisplayName("merges across lines")
        void mergesAcrossLines() {
            Span start = Span.of(3, 5, 9);
            Span end = Span.of(6, 1, 4);

            Span merged = start.merge(end);

            assertEquals(3, merged.startLine());
            assertEquals(5, merged.startCol());
            assertEquals(6, merged.endLine());
            assertEquals(4, merged.endCol());
        }

        @Test
        @DisplayName("keeps this span's block id, not the other's")
        void keepsLeftBlockId() {
            Span left = Span.of(1, 1, 5).withBlockId("blk_left");
            Span right = Span.of(1, 6, 9).withBlockId("blk_right");

            Span merged = left.merge(right);

            assertEquals("blk_left", merged.blockId());
        }
    }

    @Nested
    @DisplayName("value semantics and formatting")
    class ValueSemantics {

        @Test
        @DisplayName("two spans with identical fields are equal")
        void equality() {
            assertEquals(new Span(1, 1, 1, 5, "b"), new Span(1, 1, 1, 5, "b"));
            assertNotEquals(new Span(1, 1, 1, 5, "b"), new Span(1, 1, 1, 6, "b"));
        }

        @Test
        @DisplayName("toString is compact and omits the block id")
        void toStringFormat() {
            assertEquals("7:12-7:19", new Span(7, 12, 7, 19, "blk_x").toString());
        }
    }
}