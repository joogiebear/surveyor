package xyz.joogiebear.surveyor.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class LimitsTest {

    @Test
    void specDefaultsAreAccepted() {
        Limits limits = new Limits(250_000, 8, 4);
        assertEquals(8, limits.tickBudgetMs());
    }

    @Test
    void blockCapMustBePositive() {
        assertThrows(IllegalArgumentException.class, () -> new Limits(0, 8, 4));
    }

    @Test
    void tickBudgetMustLeaveRoomInTheTick() {
        assertThrows(IllegalArgumentException.class, () -> new Limits(1000, 0, 4));
        assertThrows(IllegalArgumentException.class, () -> new Limits(1000, 50, 4));
        new Limits(1000, 49, 4);
    }

    @Test
    void concurrentOperationsMustBePositive() {
        assertThrows(IllegalArgumentException.class, () -> new Limits(1000, 8, 0));
    }
}
