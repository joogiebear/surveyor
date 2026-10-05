package xyz.joogiebear.surveyor.core.audit;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Keeps a burst of identical events from becoming a burst of audit rows. The first event of a key
 * in a window is written at once; the rest are only counted, and {@link #drain} hands back the
 * counts when the window has closed so they can be written as one summary row.
 */
public final class EventCoalescer {

    /** Events with the same key are the same kind of event for the same actor in the same place. */
    public record Key(String actor, String action, String where) {
    }

    public record Suppressed(Key key, int count) {
    }

    private static final class Window {

        final long start;
        int suppressed;

        Window(long start) {
            this.start = start;
        }
    }

    private final long windowMillis;
    private final Map<Key, Window> windows = new HashMap<>();
    private final List<Suppressed> finished = new ArrayList<>();

    public EventCoalescer(long windowMillis) {
        this.windowMillis = windowMillis;
    }

    /** @return true if this event should be written now, false if it was folded into the open window */
    public synchronized boolean offer(Key key, long nowMillis) {
        Window window = windows.get(key);
        if (window != null && nowMillis - window.start < windowMillis) {
            window.suppressed++;
            return false;
        }
        if (window != null && window.suppressed > 0) {
            finished.add(new Suppressed(key, window.suppressed));
        }
        windows.put(key, new Window(nowMillis));
        return true;
    }

    /**
     * The counts of every window that has closed, or of every window when {@code all} is set (shutdown).
     * Closed windows are forgotten, so the map only ever holds recent keys.
     */
    public synchronized List<Suppressed> drain(long nowMillis, boolean all) {
        List<Suppressed> out = new ArrayList<>(finished);
        finished.clear();
        windows.entrySet().removeIf(entry -> {
            Window window = entry.getValue();
            if (!all && nowMillis - window.start < windowMillis) {
                return false;
            }
            if (window.suppressed > 0) {
                out.add(new Suppressed(entry.getKey(), window.suppressed));
            }
            return true;
        });
        return out;
    }
}
