package xyz.joogiebear.surveyor.core.edit;

import java.util.concurrent.atomic.AtomicInteger;

/** Enforces `limits.max-concurrent-operations`. The cap is passed in so a reload takes effect at once. */
public final class OperationGate {

    private final AtomicInteger running = new AtomicInteger();

    public boolean tryAcquire(int max) {
        int current;
        do {
            current = running.get();
            if (current >= max) {
                return false;
            }
        } while (!running.compareAndSet(current, current + 1));
        return true;
    }

    public void release() {
        running.decrementAndGet();
    }

    public int running() {
        return running.get();
    }
}
