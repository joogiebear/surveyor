package xyz.joogiebear.surveyor.core.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class RateLimiterTest {

    private static final long SECOND = 1_000_000_000L;

    private final AtomicLong now = new AtomicLong();
    private final RateLimiter<Long> limiter = new RateLimiter<>(now::get);

    @Test
    void allowsAFullMinuteThenRefuses() {
        for (int i = 0; i < 6; i++) {
            assertEquals(0, limiter.tryAcquire(1L, 6));
        }
        long wait = limiter.tryAcquire(1L, 6);
        // Six per minute is one every ten seconds.
        assertEquals(10_000, wait);
    }

    @Test
    void refillsEvenly() {
        for (int i = 0; i < 6; i++) {
            limiter.tryAcquire(1L, 6);
        }
        now.addAndGet(5 * SECOND);
        assertEquals(5_000, limiter.tryAcquire(1L, 6));
        now.addAndGet(5 * SECOND);
        assertEquals(0, limiter.tryAcquire(1L, 6));
        assertTrue(limiter.tryAcquire(1L, 6) > 0);
    }

    @Test
    void neverBanksMoreThanAMinute() {
        limiter.tryAcquire(1L, 2);
        now.addAndGet(3600 * SECOND);
        assertEquals(0, limiter.tryAcquire(1L, 2));
        assertEquals(0, limiter.tryAcquire(1L, 2));
        assertTrue(limiter.tryAcquire(1L, 2) > 0);
    }

    @Test
    void keysAreIndependentAndForgettable() {
        assertEquals(0, limiter.tryAcquire(1L, 1));
        assertTrue(limiter.tryAcquire(1L, 1) > 0);
        assertEquals(0, limiter.tryAcquire(2L, 1));
        limiter.forget(1L);
        assertEquals(0, limiter.tryAcquire(1L, 1));
    }

    @Test
    void aLowerLimitAppliesAtOnce() {
        assertEquals(0, limiter.tryAcquire(1L, 100));
        assertEquals(0, limiter.tryAcquire(1L, 1));
        assertTrue(limiter.tryAcquire(1L, 1) > 0);
    }
}
