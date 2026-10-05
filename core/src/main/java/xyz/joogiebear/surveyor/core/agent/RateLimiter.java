package xyz.joogiebear.surveyor.core.agent;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * A token bucket per key: a full bucket holds one minute of calls and refills evenly. State is
 * kept in memory only, which is enough because it exists to stop bursts, not to bill.
 */
public final class RateLimiter<K> {

    private static final long MINUTE_NANOS = 60_000_000_000L;

    private final Map<K, Bucket> buckets = new ConcurrentHashMap<>();
    private final LongSupplier nanoClock;

    public RateLimiter(LongSupplier nanoClock) {
        this.nanoClock = nanoClock;
    }

    /** @return 0 if the call may go ahead, otherwise how many milliseconds until it could */
    public long tryAcquire(K key, int perMinute) {
        return buckets.computeIfAbsent(key, k -> new Bucket(perMinute, nanoClock.getAsLong()))
                .take(perMinute, nanoClock.getAsLong());
    }

    public void forget(K key) {
        buckets.remove(key);
    }

    private static final class Bucket {

        private double tokens;
        private long last;

        Bucket(int capacity, long now) {
            tokens = capacity;
            last = now;
        }

        synchronized long take(int perMinute, long now) {
            tokens = Math.min(perMinute, tokens + (now - last) * (double) perMinute / MINUTE_NANOS);
            last = now;
            if (tokens >= 1) {
                tokens -= 1;
                return 0;
            }
            double missing = 1 - tokens;
            return (long) Math.ceil(missing * MINUTE_NANOS / perMinute / 1_000_000.0);
        }
    }
}
