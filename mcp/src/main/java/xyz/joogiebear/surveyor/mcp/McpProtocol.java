package xyz.joogiebear.surveyor.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import xyz.joogiebear.surveyor.core.agent.ErrorCode;

/**
 * JSON-RPC handling for MCP over HTTP, one request at a time and nothing kept between requests.
 *
 * <p>It speaks the stateless revision 2026-07-28 (every request carries its version in {@code _meta})
 * and, on the same endpoint, the earlier handshake revisions: an {@code initialize} request is
 * answered without assigning a session, and later requests are served as they arrive.
 */
public final class McpProtocol {

    public static final String MODERN = "2026-07-28";
    static final List<String> LEGACY = List.of("2025-11-25", "2025-06-18", "2025-03-26");

    private static final String META = "_meta";
    private static final String META_VERSION = "io.modelcontextprotocol/protocolVersion";
    private static final String META_CAPABILITIES = "io.modelcontextprotocol/clientCapabilities";
    private static final String META_SERVER_INFO = "io.modelcontextprotocol/serverInfo";
    private static final long CALL_TIMEOUT_SECONDS = 30;

    private static final int PARSE_ERROR = -32700;
    private static final int INVALID_REQUEST = -32600;
    private static final int METHOD_NOT_FOUND = -32601;
    private static final int INVALID_PARAMS = -32602;
    private static final int HEADER_MISMATCH = -32020;
    private static final int UNSUPPORTED_VERSION = -32022;

    /** @param body null for an empty reply (202 for a notification) */
    public record Reply(int status, JsonObject body) {
    }

    private final ToolBackend backend;
    private final String serverVersion;
    private final Logger logger;

    public McpProtocol(ToolBackend backend, String serverVersion, Logger logger) {
        this.backend = backend;
        this.serverVersion = serverVersion;
        this.logger = logger;
    }

    /** @param headers request headers with lower-case names */
    public CompletableFuture<Reply> handle(Principal principal, String text, Map<String, String> headers) {
        JsonElement parsed;
        try {
            parsed = JsonParser.parseString(text);
        } catch (JsonParseException e) {
            return done(error(null, PARSE_ERROR, "The body is not valid JSON.", 400));
        }
        if (!parsed.isJsonObject()) {
            return done(error(null, INVALID_REQUEST, "Send one JSON-RPC message per request; batches are not supported.", 400));
        }
        JsonObject message = parsed.getAsJsonObject();
        JsonElement id = message.get("id");
        if (!message.has("method") || !message.get("method").isJsonPrimitive()) {
            return done(error(id, INVALID_REQUEST, "A JSON-RPC request needs a string method.", 400));
        }
        String method = message.get("method").getAsString();
        if (id == null) {
            return done(new Reply(202, null));
        }
        if (!id.isJsonPrimitive() || id.getAsJsonPrimitive().isBoolean()) {
            return done(error(null, INVALID_REQUEST, "The id must be a string or a number.", 400));
        }
        JsonObject params = message.has("params") && message.get("params").isJsonObject()
                ? message.getAsJsonObject("params") : new JsonObject();
        JsonObject meta = params.has(META) && params.get(META).isJsonObject() ? params.getAsJsonObject(META) : null;
        boolean modern = meta != null && meta.has(META_VERSION);

        Reply refused = modern ? checkModern(id, method, params, meta, headers) : checkLegacy(id, method, headers);
        if (refused != null) {
            return done(refused);
        }
        return switch (method) {
            case "initialize" -> done(modern ? error(id, METHOD_NOT_FOUND, "initialize is not part of this protocol revision.", 404)
                    : initialize(id, params));
            case "ping" -> done(result(id, new JsonObject(), modern));
            case "server/discover" -> done(modern ? discover(id) : error(id, METHOD_NOT_FOUND,
                    "server/discover needs the per-request _meta of revision " + MODERN + ".", 404));
            case "tools/list" -> done(result(id, modern ? uncached(listTools(principal)) : listTools(principal), modern));
            case "tools/call" -> call(principal, id, params, modern);
            default -> done(error(id, METHOD_NOT_FOUND, "Unknown method " + method + ".", modern ? 404 : 200));
        };
    }

    private Reply checkModern(JsonElement id, String method, JsonObject params, JsonObject meta, Map<String, String> headers) {
        String version = meta.get(META_VERSION).isJsonPrimitive() ? meta.get(META_VERSION).getAsString() : "";
        if (!MODERN.equals(version)) {
            JsonObject data = new JsonObject();
            data.add("supported", versions());
            data.addProperty("requested", version);
            return error(id, UNSUPPORTED_VERSION, "Unsupported protocol version", data, 400);
        }
        if (!meta.has(META_CAPABILITIES) || !meta.get(META_CAPABILITIES).isJsonObject()) {
            return error(id, INVALID_PARAMS, "_meta must include " + META_CAPABILITIES + ".", 400);
        }
        if (!MODERN.equals(headers.get("mcp-protocol-version"))) {
            return error(id, HEADER_MISMATCH, "MCP-Protocol-Version must be " + MODERN + " to match the request body.", 400);
        }
        if (!method.equals(headers.get("mcp-method"))) {
            return error(id, HEADER_MISMATCH, "Mcp-Method must be " + method + " to match the request body.", 400);
        }
        if (method.equals("tools/call")) {
            String name = params.has("name") && params.get("name").isJsonPrimitive() ? params.get("name").getAsString() : null;
            if (name != null && !name.equals(decodeHeader(headers.get("mcp-name")))) {
                return error(id, HEADER_MISMATCH, "Mcp-Name must be " + name + " to match the request body.", 400);
            }
        }
        return null;
    }

    private Reply checkLegacy(JsonElement id, String method, Map<String, String> headers) {
        String version = headers.get("mcp-protocol-version");
        if (version != null && !LEGACY.contains(version) && !method.equals("initialize")) {
            JsonObject data = new JsonObject();
            data.add("supported", versions());
            data.addProperty("requested", version);
            return error(id, INVALID_REQUEST, "Unsupported MCP-Protocol-Version " + version + ".", data, 400);
        }
        return null;
    }

    private Reply initialize(JsonElement id, JsonObject params) {
        String asked = params.has("protocolVersion") && params.get("protocolVersion").isJsonPrimitive()
                ? params.get("protocolVersion").getAsString() : "";
        JsonObject result = new JsonObject();
        result.addProperty("protocolVersion", LEGACY.contains(asked) ? asked : LEGACY.get(0));
        result.add("capabilities", capabilities());
        JsonObject info = new JsonObject();
        info.addProperty("name", "Surveyor");
        info.addProperty("version", serverVersion);
        result.add("serverInfo", info);
        result.addProperty("instructions", instructions());
        return result(id, result, false);
    }

    private Reply discover(JsonElement id) {
        JsonObject result = new JsonObject();
        result.add("supportedVersions", versions());
        result.add("capabilities", capabilities());
        result.addProperty("instructions", instructions());
        JsonObject info = new JsonObject();
        info.addProperty("name", "Surveyor");
        info.addProperty("version", serverVersion);
        JsonObject meta = new JsonObject();
        meta.add(META_SERVER_INFO, info);
        result.add(META, meta);
        return result(id, uncached(result), true);
    }

    // Revision 2026-07-28 requires freshness hints on these results. The tool list depends on the token
    // and its scope can change at any time, so it is private and never cached.
    private static JsonObject uncached(JsonObject result) {
        result.addProperty("cacheScope", "private");
        result.addProperty("ttlMs", 0);
        return result;
    }

    private static JsonObject capabilities() {
        JsonObject tools = new JsonObject();
        tools.addProperty("listChanged", false);
        JsonObject capabilities = new JsonObject();
        capabilities.add("tools", tools);
        return capabilities;
    }

    private static String instructions() {
        return "Region protection and block editing for a Minecraft server. Start with world.list. Every change can be "
                + "previewed with dryRun. Large edits return an operationId at once; poll operation.status. Undo with edit.undo.";
    }

    private static JsonArray versions() {
        JsonArray array = new JsonArray();
        array.add(MODERN);
        LEGACY.forEach(array::add);
        return array;
    }

    private static JsonObject listTools(Principal principal) {
        JsonArray tools = new JsonArray();
        for (Tools.Definition tool : Tools.all()) {
            if (principal.scope().allowsAction(tool.name())) {
                tools.add(tool.toJson());
            }
        }
        JsonObject result = new JsonObject();
        result.add("tools", tools);
        return result;
    }

    private CompletableFuture<Reply> call(Principal principal, JsonElement id, JsonObject params, boolean modern) {
        if (!params.has("name") || !params.get("name").isJsonPrimitive()) {
            return done(error(id, INVALID_PARAMS, "tools/call needs a tool name.", 200));
        }
        String name = params.get("name").getAsString();
        if (Tools.find(name) == null) {
            return done(error(id, INVALID_PARAMS, "Unknown tool: " + name, 200));
        }
        if (params.has("arguments") && !params.get("arguments").isJsonObject()) {
            return done(error(id, INVALID_PARAMS, "arguments must be an object.", 200));
        }
        JsonObject arguments = params.has("arguments") ? params.getAsJsonObject("arguments") : new JsonObject();
        return backend.call(principal, name, arguments)
                .orTimeout(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .exceptionally(failure -> {
                    logger.log(Level.SEVERE, "Tool " + name + " failed", failure);
                    return ToolResult.error(ErrorCode.INTERNAL, "The server could not complete the call. Try again later.");
                })
                .thenApply(tool -> result(id, toolResult(tool), modern));
    }

    private static JsonObject toolResult(ToolResult tool) {
        JsonObject text = new JsonObject();
        text.addProperty("type", "text");
        text.addProperty("text", tool.body().toString());
        JsonArray content = new JsonArray();
        content.add(text);
        JsonObject result = new JsonObject();
        result.add("content", content);
        result.add("structuredContent", tool.body());
        result.addProperty("isError", !tool.ok());
        return result;
    }

    // Modern results carry resultType; earlier revisions do not know the field.
    private static Reply result(JsonElement id, JsonObject result, boolean modern) {
        if (modern) {
            result.addProperty("resultType", "complete");
        }
        JsonObject body = envelope(id);
        body.add("result", result);
        return new Reply(200, body);
    }

    private static Reply error(JsonElement id, int code, String message, int status) {
        return error(id, code, message, null, status);
    }

    private static Reply error(JsonElement id, int code, String message, JsonObject data, int status) {
        JsonObject error = new JsonObject();
        error.addProperty("code", code);
        error.addProperty("message", message);
        if (data != null) {
            error.add("data", data);
        }
        JsonObject body = envelope(id);
        body.add("error", error);
        return new Reply(status, body);
    }

    private static JsonObject envelope(JsonElement id) {
        JsonObject body = new JsonObject();
        body.addProperty("jsonrpc", "2.0");
        if (id != null) {
            body.add("id", id);
        }
        return body;
    }

    private static CompletableFuture<Reply> done(Reply reply) {
        return CompletableFuture.completedFuture(reply);
    }

    /** Mcp-Name may arrive as {@code =?base64?...?=} when the value is not plain ASCII. */
    static String decodeHeader(String value) {
        if (value != null && value.startsWith("=?base64?") && value.endsWith("?=") && value.length() >= 11) {
            try {
                return new String(Base64.getDecoder().decode(value.substring(9, value.length() - 2)), StandardCharsets.UTF_8);
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
        return value;
    }
}
