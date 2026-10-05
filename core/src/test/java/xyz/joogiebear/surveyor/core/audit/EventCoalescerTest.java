package xyz.joogiebear.surveyor.core.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import xyz.joogiebear.surveyor.core.audit.EventCoalescer.Key;
import xyz.joogiebear.surveyor.core.audit.EventCoalescer.Suppressed;

class EventCoalescerTest {

    private static final Key A = new Key("alice", "bypass.build", "spawn");
    private static final Key B = new Key("alice", "bypass.interact", "spawn");

    private final EventCoalescer coalescer = new EventCoalescer(60_000);

    @Test
    void writesTheFirstEventAndCountsTheRest() {
        assertTrue(coalescer.offer(A, 0));
        assertFalse(coalescer.offer(A, 1_000));
        assertFalse(coalescer.offer(A, 59_999));
        assertEquals(List.of(), coalescer.drain(59_999, false));
        assertEquals(List.of(new Suppressed(A, 2)), coalescer.drain(60_000, false));
    }

    @Test
    void keysAreIndependent() {
        assertTrue(coalescer.offer(A, 0));
        assertTrue(coalescer.offer(B, 0));
        assertTrue(coalescer.offer(new Key("bob", "bypass.build", "spawn"), 0));
        assertTrue(coalescer.offer(new Key("alice", "bypass.build", "arena"), 0));
    }

    @Test
    void anEventAfterTheWindowStartsANewOneAndKeepsTheOldCount() {
        coalescer.offer(A, 0);
        coalescer.offer(A, 10);
        assertTrue(coalescer.offer(A, 61_000));
        assertEquals(List.of(new Suppressed(A, 1)), coalescer.drain(61_000, false));
    }

    @Test
    void aWindowWithNothingSuppressedLeavesNoSummaryButIsForgotten() {
        coalescer.offer(A, 0);
        assertEquals(List.of(), coalescer.drain(60_000, false));
        assertTrue(coalescer.offer(A, 60_001));
    }

    @Test
    void drainingEverythingFlushesOpenWindows() {
        coalescer.offer(A, 0);
        coalescer.offer(A, 5);
        assertEquals(List.of(new Suppressed(A, 1)), coalescer.drain(10, true));
        assertEquals(List.of(), coalescer.drain(10, true));
    }
}
