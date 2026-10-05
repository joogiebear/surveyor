package xyz.joogiebear.surveyor.core.edit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class OperationTest {

    @Test
    void normalLifecycle() {
        OperationState state = new OperationState();
        assertEquals(OperationStatus.PLANNED, state.status());
        assertTrue(state.moveTo(OperationStatus.RUNNING));
        assertTrue(state.moveTo(OperationStatus.DONE));
        assertEquals(OperationStatus.DONE, state.status());
    }

    @Test
    void terminalStatusesAreFinal() {
        for (OperationStatus end : List.of(OperationStatus.DONE, OperationStatus.FAILED, OperationStatus.CANCELLED)) {
            OperationState state = new OperationState();
            assertTrue(state.moveTo(OperationStatus.RUNNING));
            assertTrue(state.moveTo(end));
            for (OperationStatus next : OperationStatus.values()) {
                assertFalse(state.moveTo(next));
            }
            assertEquals(end, state.status());
        }
    }

    @Test
    void plannedCanFailOrBeCancelledButNotFinishDirectly() {
        assertFalse(new OperationState().moveTo(OperationStatus.DONE));
        assertTrue(new OperationState().moveTo(OperationStatus.FAILED));
        assertTrue(new OperationState().moveTo(OperationStatus.CANCELLED));
        assertFalse(new OperationState().moveTo(OperationStatus.PLANNED));
    }

    @Test
    void cancelRequestIsOnlyAFlag() {
        OperationState state = new OperationState();
        state.moveTo(OperationStatus.RUNNING);
        state.requestCancel();
        assertTrue(state.cancelRequested());
        assertEquals(OperationStatus.RUNNING, state.status());
    }

    @Test
    void countersAccumulate() {
        OperationState state = new OperationState();
        state.addChanged(10);
        state.addChanged(5);
        state.addSkippedProtected(3);
        state.addSkippedOutOfScope(7);
        assertEquals(15, state.changed());
        assertEquals(3, state.skippedProtected());
        assertEquals(7, state.skippedOutOfScope());
    }

    @Test
    void keysMatchTheSpecNames() {
        assertEquals("planned", OperationStatus.PLANNED.key());
        assertEquals("cancelled", OperationStatus.CANCELLED.key());
    }

    @Test
    void gateStopsAtTheCapAndFreesOnRelease() {
        OperationGate gate = new OperationGate();
        assertTrue(gate.tryAcquire(2));
        assertTrue(gate.tryAcquire(2));
        assertFalse(gate.tryAcquire(2));
        gate.release();
        assertTrue(gate.tryAcquire(2));
        assertEquals(2, gate.running());
    }

    @Test
    void loweringTheCapAppliesToNewOperationsOnly() {
        OperationGate gate = new OperationGate();
        assertTrue(gate.tryAcquire(4));
        assertTrue(gate.tryAcquire(4));
        assertFalse(gate.tryAcquire(1));
        assertEquals(2, gate.running());
    }

    @Test
    void idsAreValidAndSortInCreationOrder() {
        Random random = new Random(1);
        List<String> ids = new ArrayList<>();
        for (long t = 1_790_000_000_000L; t < 1_790_000_000_000L + 500; t += 7) {
            ids.add(OperationIds.next(t, random));
        }
        ids.forEach(id -> assertTrue(OperationIds.isValid(id), id));
        assertEquals(ids, ids.stream().sorted().toList());
        assertFalse(OperationIds.isValid("op_x"));
        assertFalse(OperationIds.isValid("'; DROP TABLE operations"));
    }

    @Test
    void sameMillisecondStillDiffers() {
        Random random = new Random(2);
        assertFalse(OperationIds.next(1_790_000_000_000L, random).equals(OperationIds.next(1_790_000_000_000L, random)));
    }
}
