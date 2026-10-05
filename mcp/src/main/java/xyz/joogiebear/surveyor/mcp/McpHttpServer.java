package xyz.joogiebear.surveyor.mcp;

import com.google.gson.JsonObject;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.logging.Logger;
import xyz.joogiebear.surveyor.core.agent.ErrorCode;

/** The MCP endpoint: one path, POST only, bearer authentication, loopback origins only. */
public final class McpHttpServer {

    static final String PATH = "/mcp";
    static final int MAX_BODY_BYTES = 1 << 20;

    private static final Set<String> LOOPBACK_HOSTS = Set.of("localhost", "127.0.0.1", "[::1]", "::1");

    private final HttpServer server;
    private final ExecutorService executor;
    private final ToolBackend backend;
    private final McpProtocol protocol;
    private final Logger logger;

    /** @throws IOException if the address cannot be bound, for example because the port is taken */
    public McpHttpServer(InetAddress bind, int port, ToolBackend backend, String version, Logger logger) throws IOException {
        this.backend = backend;
        this.logger = logger;
        this.protocol = new McpProtocol(backend, version, logger);
        int[] counter = {0};
        this.executor = Executors.newFixedThreadPool(4, runnable -> {
            Thread thread = new Thread(runnable, "Surveyor-MCP-" + counter[0]++);
            thread.setDaemon(true);
            return thread;
        });
        this.server = HttpServer.create(new InetSocketAddress(bind, port), 0);
        this.server.createContext(PATH, this::handle);
        this.server.setExecutor(executor);
    }

    public void start() {
        server.start();
    }

    public int port() {
        return server.getAddress().getPort();
    }

    /** {@code host:port} as bound. */
    public String address() {
        return server.getAddress().getAddress().getHostAddress() + ":" + port();
    }

    public void stop() {
        server.stop(1);
        executor.shutdownNow();
    }

    private void handle(HttpExchange exchange) throws IOException {
        try {
            route(exchange);
        } catch (RuntimeException e) {
            logger.log(Level.SEVERE, "MCP request failed", e);
            send(exchange, 500, ToolResult.error(ErrorCode.INTERNAL, "The server could not handle the request.").body());
        }
    }

    private void route(HttpExchange exchange) throws IOException {
        if (!exchange.getRequestURI().getPath().equals(PATH)) {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
            return;
        }
        if (!originAllowed(exchange.getRequestHeaders().getFirst("Origin"))) {
            send(exchange, 403, ToolResult.error(ErrorCode.UNAUTHORIZED, "This origin is not allowed.").body());
            return;
        }
        if (!exchange.getRequestMethod().equals("POST")) {
            exchange.getResponseHeaders().set("Allow", "POST");
            exchange.sendResponseHeaders(405, -1);
            exchange.close();
            return;
        }
        ToolBackend.Auth auth = authenticate(exchange.getRequestHeaders().getFirst("Authorization"));
        if (auth.principal() == null) {
            exchange.getResponseHeaders().set("WWW-Authenticate", "Bearer realm=\"surveyor\"");
            send(exchange, 401, ToolResult.error(ErrorCode.UNAUTHORIZED, auth.message()).body());
            return;
        }
        byte[] body;
        try (InputStream in = exchange.getRequestBody()) {
            body = in.readNBytes(MAX_BODY_BYTES + 1);
        }
        if (body.length > MAX_BODY_BYTES) {
            send(exchange, 413, ToolResult.error(ErrorCode.INVALID_ARGUMENT,
                    "The request is larger than " + MAX_BODY_BYTES + " bytes.").body());
            return;
        }
        protocol.handle(auth.principal(), new String(body, StandardCharsets.UTF_8), lowerCase(exchange.getRequestHeaders()))
                .whenComplete((reply, failure) -> {
                    try {
                        if (failure != null) {
                            logger.log(Level.SEVERE, "MCP request failed", failure);
                            send(exchange, 500, ToolResult.error(ErrorCode.INTERNAL, "The server could not handle the request.").body());
                        } else {
                            send(exchange, reply.status(), reply.body());
                        }
                    } catch (IOException | RuntimeException e) {
                        logger.log(Level.WARNING, "Could not answer an MCP request: " + e);
                    }
                });
    }

    private ToolBackend.Auth authenticate(String header) {
        if (header == null || !header.regionMatches(true, 0, "Bearer ", 0, 7) || header.length() == 7) {
            return new ToolBackend.Auth(null, "Send the token as 'Authorization: Bearer <secret>'.");
        }
        return backend.authenticate(header.substring(7).trim());
    }

    /** Browsers send Origin; a page on another site must not be able to drive a local server. */
    static boolean originAllowed(String origin) {
        if (origin == null) {
            return true;
        }
        try {
            String host = new URI(origin).getHost();
            return host != null && LOOPBACK_HOSTS.contains(host.toLowerCase(Locale.ROOT));
        } catch (URISyntaxException e) {
            return false;
        }
    }

    private static Map<String, String> lowerCase(Headers headers) {
        Map<String, String> out = new HashMap<>();
        headers.forEach((name, values) -> out.put(name.toLowerCase(Locale.ROOT), values.get(0)));
        return out;
    }

    private static void send(HttpExchange exchange, int status, JsonObject body) throws IOException {
        Headers headers = exchange.getResponseHeaders();
        headers.set("Cache-Control", "no-store");
        headers.set("X-Content-Type-Options", "nosniff");
        if (body == null) {
            exchange.sendResponseHeaders(status, -1);
            exchange.close();
            return;
        }
        byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
        headers.set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
