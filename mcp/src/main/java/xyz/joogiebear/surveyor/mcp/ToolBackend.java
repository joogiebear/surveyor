package xyz.joogiebear.surveyor.mcp;

import com.google.gson.JsonObject;
import java.util.concurrent.CompletableFuture;

/** What the server asks of the plugin: who a secret belongs to, and to run a tool. */
public interface ToolBackend {

    /** @param principal the caller, or null with {@code message} saying why not */
    record Auth(Principal principal, String message) {
    }

    /** Safe to call from any thread. Never logs or stores the secret. */
    Auth authenticate(String secret);

    /** Safe to call from any thread. The future completes with a result, never exceptionally. */
    CompletableFuture<ToolResult> call(Principal principal, String tool, JsonObject arguments);
}
