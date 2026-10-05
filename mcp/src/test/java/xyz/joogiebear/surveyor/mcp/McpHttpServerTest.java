package xyz.joogiebear.surveyor.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import xyz.joogiebear.surveyor.core.agent.AgentScope;

class McpHttpServerTest {

    private static final String PING = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}";

    private McpHttpServer server;
    private HttpClient client;
    private URI uri;

    @BeforeEach
    void start() throws IOException {
        ToolBackend backend = new ToolBackend() {
            @Override
            public Auth authenticate(String secret) {
                return secret.equals("good") ? new Auth(new Principal(1, "t", AgentScope.NONE), null)
                        : new Auth(null, "That token is not valid.");
            }

            @Override
            public CompletableFuture<ToolResult> call(Principal principal, String tool, JsonObject arguments) {
                return CompletableFuture.completedFuture(ToolResult.ok(new JsonObject()));
            }
        };
        server = new McpHttpServer(InetAddress.getLoopbackAddress(), 0, backend, "test", Logger.getAnonymousLogger());
        server.start();
        client = HttpClient.newHttpClient();
        uri = URI.create("http://127.0.0.1:" + server.port() + "/mcp");
    }

    @AfterEach
    void stop() {
        server.stop();
    }

    private HttpResponse<String> post(String auth, String origin, String body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri).POST(HttpRequest.BodyPublishers.ofString(body))
                .header("Content-Type", "application/json");
        if (auth != null) {
            request.header("Authorization", auth);
        }
        if (origin != null) {
            request.header("Origin", origin);
        }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void aValidTokenGetsAnAnswer() throws Exception {
        HttpResponse<String> response = post("Bearer good", null, PING);
        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("\"result\""));
        assertEquals("no-store", response.headers().firstValue("Cache-Control").orElse(""));
    }

    @Test
    void missingOrWrongCredentialsAreRefusedBeforeAnythingIsParsed() throws Exception {
        for (String auth : new String[] {null, "Bearer bad", "Basic good", "Bearer ", "good"}) {
            HttpResponse<String> response = post(auth, null, PING);
            assertEquals(401, response.statusCode(), String.valueOf(auth));
            assertNotNull(response.headers().firstValue("WWW-Authenticate").orElse(null));
            assertTrue(response.body().contains("UNAUTHORIZED"));
            assertFalse(response.body().contains("\"result\""));
        }
        assertEquals(401, post("Bearer bad", null, "not json").statusCode());
    }

    @Test
    void onlyPostIsAllowedOnTheEndpoint() throws Exception {
        for (String method : new String[] {"GET", "DELETE", "PUT"}) {
            HttpRequest request = HttpRequest.newBuilder(uri).method(method, HttpRequest.BodyPublishers.noBody())
                    .header("Authorization", "Bearer good").build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            assertEquals(405, response.statusCode(), method);
            assertEquals("POST", response.headers().firstValue("Allow").orElse(""));
        }
    }

    @Test
    void otherPathsAreNotFound() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + "/other"))
                .POST(HttpRequest.BodyPublishers.ofString(PING)).header("Authorization", "Bearer good").build();
        assertEquals(404, client.send(request, HttpResponse.BodyHandlers.ofString()).statusCode());
    }

    @Test
    void foreignOriginsAreForbiddenAndLoopbackOnesAllowed() throws Exception {
        assertEquals(403, post("Bearer good", "https://evil.example", PING).statusCode());
        assertEquals(403, post("Bearer good", "null", PING).statusCode());
        assertEquals(200, post("Bearer good", "http://localhost:3000", PING).statusCode());
        assertEquals(200, post("Bearer good", "http://127.0.0.1", PING).statusCode());
        assertTrue(McpHttpServer.originAllowed(null));
        assertTrue(McpHttpServer.originAllowed("http://[::1]:8080"));
        assertFalse(McpHttpServer.originAllowed("http://localhost.evil.example"));
        assertFalse(McpHttpServer.originAllowed("not a uri"));
    }

    @Test
    void oversizedBodiesAreRefused() throws Exception {
        String big = "{\"pad\":\"" + "x".repeat(McpHttpServer.MAX_BODY_BYTES) + "\"}";
        assertEquals(413, post("Bearer good", null, big).statusCode());
    }

    @Test
    void malformedJsonFromAnAuthenticatedCallerIsABadRequest() throws Exception {
        HttpResponse<String> response = post("Bearer good", null, "{nope");
        assertEquals(400, response.statusCode());
        assertTrue(response.body().contains("-32700"));
    }

    @Test
    void notificationsAreAcceptedWithNoBody() throws Exception {
        HttpResponse<String> response = post("Bearer good", null, "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}");
        assertEquals(202, response.statusCode());
        assertEquals("", response.body());
    }
}
