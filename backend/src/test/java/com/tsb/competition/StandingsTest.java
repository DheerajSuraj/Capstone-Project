package com.tsb.competition;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@DisplayName("Standings")
class StandingsTest {

    private static Standings.Row row(long entry, long user, EntryState.Status s,
                                     double equity, long submitted) {
        return new Standings.Row(entry, user, s, equity, submitted);
    }

    @Test
    @DisplayName("entries: still-in first by equity, then failed, then eliminated")
    void entryOrder() {
        Map<Long, Integer> ranks = Standings.rankEntries(List.of(
                row(1, 10, EntryState.Status.ELIMINATED, 5000, 1),
                row(2, 10, EntryState.Status.ACTIVE, 1100, 2),
                row(3, 20, EntryState.Status.ACTIVE, 1200, 3),
                row(4, 30, EntryState.Status.FAILED, 1300, 4)));
        assertEquals(List.of(3L, 2L, 4L, 1L), List.copyOf(ranks.keySet()));
        assertEquals(1, ranks.get(3L));
        assertEquals(Integer.valueOf(4), ranks.get(1L), "a big equity does not save an eliminated entry");
    }

    @Test
    @DisplayName("ties go to whoever submitted first")
    void tieBreak() {
        Map<Long, Integer> ranks = Standings.rankEntries(List.of(
                row(1, 10, EntryState.Status.ACTIVE, 1000, 20),
                row(2, 20, EntryState.Status.ACTIVE, 1000, 10)));
        assertEquals(1, ranks.get(2L));
    }

    @Test
    @DisplayName("traders: each user's entries add up into one cumulative score")
    void cumulative() {
        List<Standings.TraderScore> t = Standings.rankTraders(List.of(
                row(1, 10, EntryState.Status.PASSED, 1200, 1),   // +200
                row(2, 10, EntryState.Status.FAILED, 900, 2),    // −100
                row(3, 20, EntryState.Status.PASSED, 1150, 3)),  // +150
                1000);
        assertEquals(20L, t.get(0).userId()); // +150 beats +100
        assertEquals(150, t.get(0).cumulativePnl(), 1e-9);
        assertEquals(10L, t.get(1).userId());
        assertEquals(100, t.get(1).cumulativePnl(), 1e-9);
        assertEquals(2, t.get(1).entries());
        assertEquals(1, t.get(1).passed());
    }

    @Test
    @DisplayName("a rule set that makes no sense is refused")
    void ruleSetValidation() {
        assertThrows(IllegalArgumentException.class,
                () -> new RuleSet(0, 0, 10, 10, 0, 0, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new RuleSet(1000, 0, 0, 10, 0, 0, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new RuleSet(1000, 0, 10, 150, 0, 0, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new RuleSet(1000, 0, 10, 10, 0, -1, 0));
    }

    @Test
    @DisplayName("entry hash is SHA-256 of the source")
    void hash() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                EntryHash.of("abc"));
    }
}
