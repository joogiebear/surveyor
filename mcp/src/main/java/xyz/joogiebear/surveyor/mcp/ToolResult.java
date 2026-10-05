package xyz.joogiebear.surveyor.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import xyz.joogiebear.surveyor.core.agent.ErrorCode;

/** The result shape of the SPEC (7.3): {@code ok}, tool specific fields, and {@code errors} when it failed. */
public record ToolResult(JsonObject body) {

    public boolean ok() {
        return body.get("ok").getAsBoolean();
    }

    public static ToolResult ok(JsonObject fields) {
        return build(true, fields, null, null);
    }

    public static ToolResult error(ErrorCode code, String message) {
        return build(false, new JsonObject(), code, message);
    }

    /** A failure that still carries fields, such as the counts of a refused strict edit. */
    public static ToolResult error(ErrorCode code, String message, JsonObject fields) {
        return build(false, fields, code, message);
    }

    private static ToolResult build(boolean ok, JsonObject fields, ErrorCode code, String message) {
        JsonObject body = new JsonObject();
        body.addProperty("ok", ok);
        fields.entrySet().forEach(e -> body.add(e.getKey(), e.getValue()));
        if (code != null) {
            JsonObject error = new JsonObject();
            error.addProperty("code", code.name());
            error.addProperty("message", message);
            JsonArray errors = new JsonArray();
            errors.add(error);
            body.add("errors", errors);
        }
        return new ToolResult(body);
    }
}
