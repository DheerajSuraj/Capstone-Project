package com.tsb.forum;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@DisplayName("Forum text rules")
class ForumTextTest {

    @Test
    @DisplayName("control characters go, newlines and tabs stay, ends are trimmed")
    void clean() {
        assertEquals("line one\nline\ttwo", ForumService.clean("  line one\r\n\u0000line\ttwo\u0007  ", 100));
    }

    @Test
    @DisplayName("HTML is kept as typed — it is only ever shown as text")
    void htmlIsText() {
        assertEquals("<b>hi</b>", ForumService.clean("<b>hi</b>", 100));
    }

    @Test
    @DisplayName("too long is refused, not silently cut")
    void tooLong() {
        assertThrows(RuntimeException.class, () -> ForumService.clean("x".repeat(11), 10));
    }

    @Test
    @DisplayName("categories are checked and normalised")
    void categories() {
        assertEquals("HELP", ForumService.checkCategory(" help "));
        assertThrows(RuntimeException.class, () -> ForumService.checkCategory("memes"));
    }
}
