package xyz.joogiebear.surveyor.core.edit;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/** Live status and counters of one operation. Updated from region threads and read from anywhere. */
public final class OperationState {

    private final AtomicReference<OperationStatus> status = new AtomicReference<>(OperationStatus.PLANNED);
    private final AtomicLong changed = new AtomicLong();
    private final AtomicLong skippedProtected = new AtomicLong();
    private volatile boolean cancelRequested;

    public OperationStatus status() {
        return status.get();
    }

    /** @return false, leaving the status as it was, if the move is not allowed from the current status */
    public boolean moveTo(OperationStatus next) {
        OperationStatus current;
        do {
            current = status.get();
            if (!current.canMoveTo(next)) {
                return false;
            }
        } while (!status.compareAndSet(current, next));
        return true;
    }

    public void requestCancel() {
        cancelRequested = true;
    }

    public boolean cancelRequested() {
        return cancelRequested;
    }

    public void addChanged(long blocks) {
        changed.addAndGet(blocks);
    }

    public void addSkippedProtected(long blocks) {
        skippedProtected.addAndGet(blocks);
    }

    public long changed() {
        return changed.get();
    }

    public long skippedProtected() {
        return skippedProtected.get();
    }
}
