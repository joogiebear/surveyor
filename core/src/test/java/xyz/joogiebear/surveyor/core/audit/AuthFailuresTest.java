package xyz.joogiebear.surveyor.core.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

class AuthFailuresTest {

    private final AuthFailures failures = new AuthFailures(60_000);

    @Test
    void warnsAtOnceThenAtMostOncePerInterval() {
        assertEquals(1, failures.record(AuthFailures.UNKNOWN, 0));
        assertEquals(0, failures.record(AuthFailures.UNKNOWN, 1_000));
        assertEquals(0, failures.record(AuthFailures.UNKNOWN, 59_999));
        // The two that were held back are reported with the next one.
        assertEquals(3, failures.record(AuthFailures.UNKNOWN, 60_000));
    }

    @Test
    void countsPerKeyMostFirst() {
        failures.record("sv_abcdefg", 5);
        failures.record(AuthFailures.UNKNOWN, 6);
        failures.record(AuthFailures.UNKNOWN, 7);
        assertEquals(3, failures.total());
        assertEquals(List.of(new AuthFailures.Entry(AuthFailures.UNKNOWN, 2, 7), new AuthFailures.Entry("sv_abcdefg", 1, 5)),
                failures.entries());
    }

    @Test
    void startsEmpty() {
        assertEquals(0, failures.total());
        assertEquals(List.of(), failures.entries());
    }
}
