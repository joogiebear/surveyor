package xyz.joogiebear.surveyor.core.audit;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Counts failed authentications without storing one row per attempt, so nobody who can reach the
 * port can grow the database or flood the log. Keys are token prefixes of tokens that exist, or
 * "unknown", so the map stays as small as the token list.
 */
public final class AuthFailures {

    public static final String UNKNOWN = "unknown";

    public record Entry(String key, long count, long lastMillis) {
    }

    private static final class Counter {

        long count;
        long last;
    }

    private final long warnIntervalMillis;
    private final Map<String, Counter> counters = new HashMap<>();
    private long sinceWarning;
    private long lastWarning = Long.MIN_VALUE;

    public AuthFailures(long warnIntervalMillis) {
        this.warnIntervalMillis = warnIntervalMillis;
    }

    /** @return how many failures a log warning should report now, or 0 if one was written recently */
    public synchronized long record(String key, long nowMillis) {
        Counter counter = counters.computeIfAbsent(key, k -> new Counter());
        counter.count++;
        counter.last = nowMillis;
        sinceWarning++;
        if (lastWarning != Long.MIN_VALUE && nowMillis - lastWarning < warnIntervalMillis) {
            return 0;
        }
        lastWarning = nowMillis;
        long report = sinceWarning;
        sinceWarning = 0;
        return report;
    }

    public synchronized long total() {
        return counters.values().stream().mapToLong(c -> c.count).sum();
    }

    /** Most failures first. */
    public synchronized List<Entry> entries() {
        return counters.entrySet().stream()
                .map(e -> new Entry(e.getKey(), e.getValue().count, e.getValue().last))
                .sorted(Comparator.comparingLong(Entry::count).reversed().thenComparing(Entry::key)).toList();
    }
}
