package xyz.joogiebear.surveyor.core.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class BindPolicyTest {

    @Test
    void loopbackAddressesAreAccepted() {
        assertEquals("127.0.0.1", BindPolicy.resolve("127.0.0.1", false, false).getHostAddress());
        assertTrue(BindPolicy.resolve("localhost", false, false).isLoopbackAddress());
        assertTrue(BindPolicy.resolve("::1", false, false).isLoopbackAddress());
        assertTrue(BindPolicy.resolve("127.0.0.5", false, false).isLoopbackAddress());
    }

    @Test
    void anythingElseIsRefusedWithoutAllowRemote() {
        for (String bind : new String[] {"0.0.0.0", "192.168.1.10", "::", "8.8.8.8"}) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> BindPolicy.resolve(bind, false, false));
            assertTrue(e.getMessage().contains("allow-remote"), e.getMessage());
        }
    }

    @Test
    void allowRemoteStillNeedsTls() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> BindPolicy.resolve("0.0.0.0", true, false));
        assertTrue(e.getMessage().contains("TLS"), e.getMessage());
        assertEquals("0.0.0.0", BindPolicy.resolve("0.0.0.0", true, true).getHostAddress());
    }

    @Test
    void hostnamesAndGarbageAreRefusedWithoutALookup() {
        assertThrows(IllegalArgumentException.class, () -> BindPolicy.resolve("example.com", true, true));
        assertThrows(IllegalArgumentException.class, () -> BindPolicy.resolve("", false, false));
        assertThrows(IllegalArgumentException.class, () -> BindPolicy.resolve(null, false, false));
        assertThrows(IllegalArgumentException.class, () -> BindPolicy.resolve("1.2.3.999", false, false));
    }
}
