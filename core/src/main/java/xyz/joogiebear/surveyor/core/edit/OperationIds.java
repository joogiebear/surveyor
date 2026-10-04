package xyz.joogiebear.surveyor.core.edit;

import java.util.concurrent.ThreadLocalRandom;
import java.util.random.RandomGenerator;

public final class OperationIds {

    public static final String PREFIX = "op_";

    private static final int TIME_DIGITS = 9;
    private static final int RANDOM_DIGITS = 5;

    private OperationIds() {
    }

    public static String next() {
        return next(System.currentTimeMillis(), ThreadLocalRandom.current());
    }

    /** Base 36 time, padded so ids sort in creation order, then random digits to separate the same millisecond. */
    public static String next(long millis, RandomGenerator random) {
        StringBuilder id = new StringBuilder(PREFIX);
        String time = Long.toString(millis, 36);
        id.append("0".repeat(Math.max(0, TIME_DIGITS - time.length()))).append(time);
        for (int i = 0; i < RANDOM_DIGITS; i++) {
            id.append(Character.forDigit(random.nextInt(36), 36));
        }
        return id.toString();
    }

    public static boolean isValid(String id) {
        return id.matches(PREFIX + "[0-9a-z]{" + (TIME_DIGITS + RANDOM_DIGITS) + "}");
    }
}
