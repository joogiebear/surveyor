package xyz.joogiebear.surveyor.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.Base64;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;
import xyz.joogiebear.surveyor.core.agent.AgentScope;
import xyz.joogiebear.surveyor.core.agent.ErrorCode;

class McpProtocolTest {

    private static final Principal BUILDER = new Principal(1, "builder",
            new AgentScope(Set.of("world"), null, Set.of("world.list", "edit.*"), null, null));

    private final ToolBackend backend = new ToolBackend() {
        @Override
        public Auth authenticate(String secret) {
            return new Auth(BUILDER, null);
        }

        @Override
        public CompletableFuture<ToolResult> call(Principal principal, String tool, JsonObject arguments) {
            if (tool.equals("edit.set")) {
                return CompletableFuture.completedFuture(ToolResult.error(ErrorCode.OUT_OF_SCOPE, "nope"));
            }
            JsonObject fields = new JsonObject();
            fields.addProperty("tool", tool);
            fields.add("arguments", arguments);
            return CompletableFuture.completedFuture(ToolResult.ok(fields));
        }
    };

    private final McpProtocol protocol = new McpProtocol(backend, "1.2.3", Logger.getAnonymousLogger());

    private static final String META = "\"_meta\":{\"io.modelcontextprotocol/protocolVersion\":\"2026-07-28\","
            + "\"io.modelcontextprotocol/clientCapabilities\":{}}";

    private McpProtocol.Reply send(String body, Map<String, String> headers) {
        return protocol.handle(BUILDER, body, headers).join();
    }

    private static Map<String, String> modernHeaders(String method, String name) {
        return name == null ? Map.of("mcp-protocol-version", "2026-07-28", "mcp-method", method)
                : Map.of("mcp-protocol-version", "2026-07-28", "mcp-method", method, "mcp-name", name);
    }

    private static JsonObject object(JsonObject parent, String key) {
        return parent.getAsJsonObject(key);
    }

    @Test
    void modernDiscoverListsVersionsAndTools() {
        var reply = send("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"server/discover\",\"params\":{" + META + "}}",
                modernHeaders("server/discover", null));
        assertEquals(200, reply.status());
        JsonObject result = object(reply.body(), "result");
        assertEquals("complete", result.get("resultType").getAsString());
        assertEquals("2026-07-28", result.getAsJsonArray("supportedVersions").get(0).getAsString());
        assertTrue(result.getAsJsonArray("supportedVersions").toString().contains("2025-11-25"));
        assertEquals("1.2.3", object(object(result, "_meta"), "io.modelcontextprotocol/serverInfo").get("version").getAsString());
    }

    @Test
    void toolListIsFilteredByTheTokenScope() {
        var reply = send("{\"jsonrpc\":\"2.0\",\"id\":\"a\",\"method\":\"tools/list\",\"params\":{" + META + "}}",
                modernHeaders("tools/list", null));
        String names = object(reply.body(), "result").getAsJsonArray("tools").toString();
        assertTrue(names.contains("\"world.list\""));
        assertTrue(names.contains("\"edit.set\""));
        assertTrue(names.contains("\"edit.undo\""));
        assertFalse(names.contains("\"region.delete\""));
        assertFalse(names.contains("\"audit.query\""));
        assertEquals("a", reply.body().get("id").getAsString());
    }

    @Test
    void modernToolCallReturnsStructuredContent() {
        var reply = send("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"world.list\","
                + "\"arguments\":{\"x\":1}," + META + "}}", modernHeaders("tools/call", "world.list"));
        JsonObject result = object(reply.body(), "result");
        assertFalse(result.get("isError").getAsBoolean());
        assertEquals("world.list", object(result, "structuredContent").get("tool").getAsString());
        assertEquals(1, object(object(result, "structuredContent"), "arguments").get("x").getAsInt());
        assertEquals("complete", result.get("resultType").getAsString());
        assertTrue(result.getAsJsonArray("content").get(0).getAsJsonObject().get("text").getAsString().contains("\"ok\":true"));
    }

    @Test
    void aFailedToolIsAnErrorResultNotAProtocolError() {
        var reply = send("{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":{\"name\":\"edit.set\"," + META + "}}",
                modernHeaders("tools/call", "edit.set"));
        assertEquals(200, reply.status());
        JsonObject result = object(reply.body(), "result");
        assertTrue(result.get("isError").getAsBoolean());
        assertEquals("OUT_OF_SCOPE", object(result, "structuredContent").getAsJsonArray("errors")
                .get(0).getAsJsonObject().get("code").getAsString());
    }

    @Test
    void mismatchedHeadersAreRejected() {
        String body = "{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"tools/call\",\"params\":{\"name\":\"world.list\"," + META + "}}";
        for (Map<String, String> headers : java.util.List.of(
                modernHeaders("tools/list", "world.list"),
                modernHeaders("tools/call", "edit.set"),
                Map.of("mcp-method", "tools/call", "mcp-name", "world.list"),
                Map.of("mcp-protocol-version", "2025-11-25", "mcp-method", "tools/call", "mcp-name", "world.list"))) {
            var reply = send(body, headers);
            assertEquals(400, reply.status(), headers.toString());
            assertEquals(-32020, object(reply.body(), "error").get("code").getAsInt());
        }
    }

    @Test
    void base64EncodedNamesAreDecodedBeforeComparing() {
        String encoded = "=?base64?" + Base64.getEncoder().encodeToString("world.list".getBytes()) + "?=";
        var reply = send("{\"jsonrpc\":\"2.0\",\"id\":5,\"method\":\"tools/call\",\"params\":{\"name\":\"world.list\"," + META + "}}",
                modernHeaders("tools/call", encoded));
        assertEquals(200, reply.status());
        assertNull(reply.body().get("error"));
    }

    @Test
    void anUnknownVersionGetsTheSupportedList() {
        var reply = send("{\"jsonrpc\":\"2.0\",\"id\":6,\"method\":\"tools/list\",\"params\":{\"_meta\":{"
                + "\"io.modelcontextprotocol/protocolVersion\":\"1900-01-01\"}}}", modernHeaders("tools/list", null));
        assertEquals(400, reply.status());
        JsonObject error = object(reply.body(), "error");
        assertEquals(-32022, error.get("code").getAsInt());
        assertTrue(object(error, "data").getAsJsonArray("supported").toString().contains("2026-07-28"));
    }

    @Test
    void modernRequestsMustDeclareCapabilities() {
        var reply = send("{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"tools/list\",\"params\":{\"_meta\":{"
                + "\"io.modelcontextprotocol/protocolVersion\":\"2026-07-28\"}}}", modernHeaders("tools/list", null));
        assertEquals(400, reply.status());
        assertEquals(-32602, object(reply.body(), "error").get("code").getAsInt());
    }

    @Test
    void legacyInitializeEchoesASupportedVersionWithoutASession() {
        var reply = send("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-06-18\","
                + "\"capabilities\":{},\"clientInfo\":{\"name\":\"c\",\"version\":\"1\"}}}", Map.of());
        JsonObject result = object(reply.body(), "result");
        assertEquals("2025-06-18", result.get("protocolVersion").getAsString());
        assertEquals("Surveyor", object(result, "serverInfo").get("name").getAsString());
        assertTrue(result.has("capabilities"));
        assertFalse(result.has("resultType"));
    }

    @Test
    void legacyInitializeAnswersWithOurLatestWhenTheClientAsksForSomethingElse() {
        var reply = send("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2024-11-05\"}}",
                Map.of());
        assertEquals("2025-11-25", object(reply.body(), "result").get("protocolVersion").getAsString());
    }

    @Test
    void legacyRequestsAfterInitializeAreServedWithoutResultType() {
        var reply = send("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}", Map.of("mcp-protocol-version", "2025-11-25"));
        assertEquals(200, reply.status());
        assertFalse(object(reply.body(), "result").has("resultType"));
        assertTrue(object(reply.body(), "result").getAsJsonArray("tools").size() > 0);
        // No version header at all counts as 2025-03-26.
        assertEquals(200, send("{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"ping\"}", Map.of()).status());
    }

    @Test
    void legacyRequestsWithAnUnknownVersionHeaderAreRefused() {
        var reply = send("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}", Map.of("mcp-protocol-version", "1999-01-01"));
        assertEquals(400, reply.status());
    }

    @Test
    void notificationsGetNoBody() {
        var reply = send("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}", Map.of());
        assertEquals(202, reply.status());
        assertNull(reply.body());
    }

    @Test
    void protocolErrors() {
        assertEquals(-32700, object(send("{nope", Map.of()).body(), "error").get("code").getAsInt());
        assertEquals(-32600, object(send("[{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}]", Map.of()).body(), "error")
                .get("code").getAsInt());
        assertEquals(-32600, object(send("{\"jsonrpc\":\"2.0\",\"id\":true,\"method\":\"ping\"}", Map.of()).body(), "error")
                .get("code").getAsInt());
        var unknownTool = send("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"nuke\"}}", Map.of());
        assertEquals(-32602, object(unknownTool.body(), "error").get("code").getAsInt());
        var unknownMethod = send("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"resources/list\"}", Map.of());
        assertEquals(-32601, object(unknownMethod.body(), "error").get("code").getAsInt());
        var unknownModern = send("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"resources/list\",\"params\":{" + META + "}}",
                modernHeaders("resources/list", null));
        assertEquals(404, unknownModern.status());
        assertEquals(JsonParser.parseString("-32601"), object(unknownModern.body(), "error").get("code"));
    }

    @Test
    void aBackendThatFailsBecomesAnInternalErrorResult() {
        ToolBackend broken = new ToolBackend() {
            @Override
            public Auth authenticate(String secret) {
                return new Auth(BUILDER, null);
            }

            @Override
            public CompletableFuture<ToolResult> call(Principal principal, String tool, JsonObject arguments) {
                return CompletableFuture.failedFuture(new IllegalStateException("boom"));
            }
        };
        var reply = new McpProtocol(broken, "1", Logger.getAnonymousLogger()).handle(BUILDER,
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"world.list\"}}", Map.of()).join();
        JsonObject structured = object(object(reply.body(), "result"), "structuredContent");
        assertEquals("INTERNAL", structured.getAsJsonArray("errors").get(0).getAsJsonObject().get("code").getAsString());
        assertFalse(structured.toString().contains("boom"));
    }
}
