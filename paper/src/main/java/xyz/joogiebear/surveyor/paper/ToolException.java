package xyz.joogiebear.surveyor.paper;

import com.google.gson.JsonObject;
import xyz.joogiebear.surveyor.core.agent.ErrorCode;
import xyz.joogiebear.surveyor.mcp.ToolResult;

/** A tool call that must be refused. The message is written for an agent to act on. */
final class ToolException extends RuntimeException {

    private final ErrorCode code;
    private final JsonObject fields;

    ToolException(ErrorCode code, String message) {
        this(code, message, new JsonObject());
    }

    ToolException(ErrorCode code, String message, JsonObject fields) {
        super(message, null, false, false);
        this.code = code;
        this.fields = fields;
    }

    ToolResult result() {
        return ToolResult.error(code, getMessage(), fields);
    }
}
