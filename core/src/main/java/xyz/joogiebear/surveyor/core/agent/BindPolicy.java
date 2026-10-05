package xyz.joogiebear.surveyor.core.agent;

import java.net.InetAddress;
import java.net.UnknownHostException;

/** Which addresses the MCP server may bind to. Loopback only in v1: TLS, which remote access needs, is not built. */
public final class BindPolicy {

    private BindPolicy() {
    }

    /**
     * @return the address to bind to
     * @throws IllegalArgumentException with a message an admin can act on
     */
    public static InetAddress resolve(String bind, boolean allowRemote, boolean tlsConfigured) {
        InetAddress address = parse(bind);
        if (address.isLoopbackAddress()) {
            return address;
        }
        if (!allowRemote) {
            throw new IllegalArgumentException("server.bind is " + bind + ", which is not a loopback address. "
                    + "Use 127.0.0.1, or set server.allow-remote: true and configure TLS.");
        }
        if (!tlsConfigured) {
            throw new IllegalArgumentException("server.bind is " + bind + " and server.allow-remote is true, "
                    + "but remote access needs TLS and this version has none. Use 127.0.0.1.");
        }
        return address;
    }

    // Only literals and "localhost": a hostname would mean a DNS lookup on the startup path.
    private static InetAddress parse(String bind) {
        if (bind == null || bind.isBlank()) {
            throw new IllegalArgumentException("server.bind must not be empty");
        }
        String text = bind.trim();
        boolean ipv4 = text.matches("\\d{1,3}(\\.\\d{1,3}){3}");
        if (!text.equalsIgnoreCase("localhost") && !ipv4 && !text.contains(":")) {
            throw new IllegalArgumentException("server.bind must be an IP address or localhost, got " + bind);
        }
        try {
            return InetAddress.getByName(text);
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException("server.bind is not a valid address: " + bind, e);
        }
    }
}
