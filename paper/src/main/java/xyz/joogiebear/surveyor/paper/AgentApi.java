package xyz.joogiebear.surveyor.paper;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.bukkit.Bukkit;
import org.bukkit.World;
import xyz.joogiebear.surveyor.core.agent.AgentScope;
import xyz.joogiebear.surveyor.core.agent.AgentToken;
import xyz.joogiebear.surveyor.core.agent.ErrorCode;
import xyz.joogiebear.surveyor.core.agent.RateLimiter;
import xyz.joogiebear.surveyor.core.agent.TokenRegistry;
import xyz.joogiebear.surveyor.core.agent.TokenSecrets;
import xyz.joogiebear.surveyor.core.audit.AuthFailures;
import xyz.joogiebear.surveyor.mcp.ArgumentValidator;
import xyz.joogiebear.surveyor.mcp.Principal;
import xyz.joogiebear.surveyor.mcp.ToolBackend;
import xyz.joogiebear.surveyor.mcp.ToolResult;
import xyz.joogiebear.surveyor.mcp.Tools;
import xyz.joogiebear.surveyor.paper.storage.AgentStore;

/**
 * Runs agent tool calls. Every call passes the same gate in the same order: the token is still
 * valid, the rate limit has room, the token's scope allows the tool, the arguments fit the schema,
 * a reason is given where required. Region rights and size limits are then checked by the tool.
 */
final class AgentApi implements ToolBackend {

    /** The tools that change the world, regions or operations. Each call is written to the audit log. */
    private static final Set<String> AUDITED = Set.of("edit.set", "edit.replace", "edit.paste", "edit.undo",
            "region.create", "region.update", "region.delete", "flag.set", "flag.clear", "member.add", "member.remove",
            "operation.cancel");

    /** Cancelling a runaway edit must never be blocked by a missing reason. */
    private static final Set<String> NO_REASON_NEEDED = Set.of("operation.cancel");

    /** Edits write their own audit row when they finish, so a started edit is not audited twice. */
    private static final Set<String> AUDITED_AT_FINISH = Set.of("edit.set", "edit.replace", "edit.paste", "edit.undo");

    private static final long IDEMPOTENCY_TTL_MILLIS = TimeUnit.HOURS.toMillis(24);
    private static final int AUDIT_DEFAULT = 50;
    private static final int AUDIT_MAX = 200;

    /** One tool call after the gate: who, what, and the common arguments. */
    record Call(AgentToken token, Actor.Agent actor, AgentScope scope, String tool, Args args, boolean dryRun,
                String reason) {
    }

    private record Pending(String requestHash, CompletableFuture<ToolResult> result) {
    }

    private final SurveyorPlugin plugin;
    private final AgentStore store;
    private final TokenRegistry tokens = new TokenRegistry();
    private final AuthFailures authFailures = new AuthFailures(TimeUnit.MINUTES.toMillis(1));
    private final RateLimiter<Long> limiter = new RateLimiter<>(System::nanoTime);
    private final Map<String, Pending> pending = new ConcurrentHashMap<>();
    private final AgentEdits edits;
    private final AgentRegions regions;

    AgentApi(SurveyorPlugin plugin, AgentStore store) {
        this.plugin = plugin;
        this.store = store;
        this.edits = new AgentEdits(plugin, this);
        this.regions = new AgentRegions(plugin);
    }

    TokenRegistry tokens() {
        return tokens;
    }

    AgentStore store() {
        return store;
    }

    /** Loads the tokens and drops idempotency results that have expired. */
    CompletableFuture<Integer> load() {
        return store.loadTokens().thenCompose(loaded -> {
            tokens.replaceAll(loaded);
            return store.pruneResults(System.currentTimeMillis() - IDEMPOTENCY_TTL_MILLIS).thenApply(ignored -> loaded.size());
        });
    }

    Actor.Agent actorOf(AgentToken token) {
        return new Actor.Agent(token.id(), () -> {
            AgentToken current = tokens.get(token.id());
            return current == null ? AgentScope.NONE : current.scope();
        });
    }

    int effectiveRate(AgentScope scope) {
        return scope.ratePerMinute() != null ? scope.ratePerMinute() : plugin.settings().defaultRatePerMinute();
    }

    int effectiveMaxBlocks(AgentScope scope) {
        int limit = plugin.settings().limits().maxBlocksPerOperation();
        return scope.maxBlocks() != null ? Math.min(limit, scope.maxBlocks()) : limit;
    }

    /** Revokes at once in memory, then saves. Running operations of the token are asked to stop. */
    CompletableFuture<Void> revoke(AgentToken token) {
        AgentToken revoked = token.revoked(System.currentTimeMillis());
        tokens.put(revoked);
        Actor actor = actorOf(token);
        plugin.edits().cancelAll(actor);
        plugin.edits().forget(actor.key());
        limiter.forget(token.id());
        return store.revoke(token.id(), revoked.revokedAt());
    }

    AuthFailures authFailures() {
        return authFailures;
    }

    @Override
    public void missingCredentials() {
        failed(AuthFailures.UNKNOWN);
    }

    @Override
    public Auth authenticate(String secret) {
        AgentToken token = tokens.authenticate(secret);
        if (token == null) {
            failed(AuthFailures.UNKNOWN);
            return new Auth(null, "That token is not valid.");
        }
        return switch (token.status(System.currentTimeMillis())) {
            case ACTIVE -> new Auth(new Principal(token.id(), token.name(), token.scope()), null);
            case REVOKED -> {
                failed(token.prefix());
                yield new Auth(null, "That token has been revoked.");
            }
            case EXPIRED -> {
                failed(token.prefix());
                yield new Auth(null, "That token has expired.");
            }
        };
    }

    // Counted, not stored: anyone who can reach the port could otherwise grow the database or the log.
    // A key is a real token's prefix or "unknown", never anything the caller typed.
    private void failed(String key) {
        long report = authFailures.record(key, System.currentTimeMillis());
        if (report > 0) {
            plugin.getLogger().warning(report + " failed MCP authentication(s) since the last warning (latest: " + key
                    + "). Totals are in /sv status.");
        }
    }

    @Override
    public CompletableFuture<ToolResult> call(Principal principal, String tool, JsonObject arguments) {
        AgentToken token = tokens.get(principal.tokenId());
        if (token == null || token.status(System.currentTimeMillis()) != AgentToken.Status.ACTIVE) {
            return done(ToolResult.error(ErrorCode.UNAUTHORIZED, "That token is no longer valid."));
        }
        long wait = limiter.tryAcquire(token.id(), effectiveRate(token.scope()));
        if (wait > 0) {
            JsonObject fields = new JsonObject();
            fields.addProperty("retryAfterMs", wait);
            return done(ToolResult.error(ErrorCode.RATE_LIMITED, "Too many calls: this token may make "
                    + effectiveRate(token.scope()) + " per minute. Wait " + (wait + 999) / 1000 + " s and try again.", fields));
        }
        return gated(token, tool, arguments).thenApply(result -> {
            audit(token, tool, arguments, result);
            return result;
        });
    }

    private CompletableFuture<ToolResult> gated(AgentToken token, String tool, JsonObject arguments) {
        AgentScope scope = token.scope();
        if (!scope.allowsAction(tool)) {
            return done(ToolResult.error(ErrorCode.OUT_OF_SCOPE, "This token may not call " + tool
                    + ". Its allowed actions are: " + (scope.actions().isEmpty() ? "none" : String.join(", ", scope.actions().stream().sorted().toList()))
                    + ". Ask an admin to widen it with /sv agent token scope."));
        }
        String problem = ArgumentValidator.validate(Tools.find(tool).inputSchema(), arguments);
        if (problem != null) {
            return done(ToolResult.error(ErrorCode.INVALID_ARGUMENT, problem));
        }
        Args args = new Args(arguments);
        boolean dryRun = args.bool("dryRun");
        String reason = args.optString("reason");
        boolean mutating = AUDITED.contains(tool);
        if (mutating && !dryRun && !NO_REASON_NEEDED.contains(tool) && plugin.settings().requireReason()
                && (reason == null || reason.isBlank())) {
            return done(ToolResult.error(ErrorCode.INVALID_ARGUMENT,
                    "This server requires a reason for changes. Add a 'reason' argument saying why, and call again."));
        }
        Call call = new Call(token, actorOf(token), scope, tool, args, dryRun, reason == null || reason.isBlank() ? null : reason.trim());
        String key = args.optString("idempotencyKey");
        if (key == null || !mutating || dryRun) {
            return execute(call);
        }
        return replayable(call, key);
    }

    /** Runs the call once per idempotency key. A repeat gets the first result, and a different request under the same key is refused. */
    private CompletableFuture<ToolResult> replayable(Call call, String key) {
        JsonObject copy = call.args().json().deepCopy();
        copy.remove("idempotencyKey");
        String hash = TokenSecrets.hash(call.tool() + "\n" + copy);
        String slot = call.token().id() + ":" + key;
        Pending mine = new Pending(hash, new CompletableFuture<>());
        Pending prior = pending.putIfAbsent(slot, mine);
        if (prior != null) {
            return prior.requestHash().equals(hash) ? prior.result() : done(keyReused());
        }
        store.findResult(call.token().id(), key).thenCompose(stored -> {
            if (stored != null && System.currentTimeMillis() - stored.createdAt() < IDEMPOTENCY_TTL_MILLIS) {
                if (!stored.requestHash().equals(hash)) {
                    return done(keyReused());
                }
                JsonObject replay = JsonParser.parseString(stored.responseJson()).getAsJsonObject();
                replay.addProperty("replayed", true);
                return done(new ToolResult(replay));
            }
            return execute(call).thenCompose(result -> result.ok()
                    ? store.storeResult(call.token().id(), key, hash, result.body().toString()).thenApply(ignored -> result)
                    : done(result));
        }).whenComplete((result, failure) -> {
            pending.remove(slot);
            if (failure != null) {
                plugin.getLogger().warning("Idempotency check failed for " + call.tool() + ": " + failure);
                mine.result().complete(ToolResult.error(ErrorCode.INTERNAL, "The server could not complete the call. Try again later."));
            } else {
                mine.result().complete(result);
            }
        });
        return mine.result();
    }

    private static ToolResult keyReused() {
        return ToolResult.error(ErrorCode.CONFLICT,
                "This idempotencyKey was already used for a different request. Use a new key for a new request.");
    }

    private CompletableFuture<ToolResult> execute(Call call) {
        CompletableFuture<ToolResult> out;
        try {
            out = dispatch(call);
        } catch (ToolException e) {
            return done(e.result());
        }
        return out.exceptionally(failure -> {
            Throwable cause = failure instanceof CompletionException && failure.getCause() != null ? failure.getCause() : failure;
            if (cause instanceof ToolException e) {
                return e.result();
            }
            plugin.getLogger().severe("Agent tool " + call.tool() + " failed: " + cause);
            return ToolResult.error(ErrorCode.INTERNAL, "The server could not complete the call. Try again later.");
        });
    }

    private CompletableFuture<ToolResult> dispatch(Call call) {
        return switch (call.tool()) {
            case "world.list" -> done(worlds(call));
            case "selection.get", "selection.set", "edit.set", "edit.replace", "edit.copy", "edit.paste", "edit.undo",
                 "operation.status", "operation.cancel" -> edits.run(call);
            case "region.create", "region.update", "region.delete", "region.get", "region.list", "region.at",
                 "flag.set", "flag.clear", "member.add", "member.remove" -> regions.run(call);
            case "audit.query" -> auditQuery(call);
            default -> throw new IllegalStateException("tool " + call.tool() + " has no handler");
        };
    }

    private ToolResult worlds(Call call) {
        JsonArray worlds = new JsonArray();
        for (World world : Bukkit.getWorlds()) {
            if (!call.scope().allowsWorld(world.getName())) {
                continue;
            }
            JsonObject entry = new JsonObject();
            entry.addProperty("name", world.getName());
            entry.addProperty("environment", world.getEnvironment().name().toLowerCase());
            entry.addProperty("minY", world.getMinHeight());
            entry.addProperty("maxY", world.getMaxHeight() - 1);
            worlds.add(entry);
        }
        JsonObject out = new JsonObject();
        out.add("worlds", worlds);
        AgentScope scope = call.scope();
        if (scope.box() != null) {
            JsonObject box = Args.area(scope.box().area());
            box.addProperty("world", scope.box().world());
            out.add("box", box);
        }
        out.addProperty("maxBlocksPerOperation", effectiveMaxBlocks(scope));
        out.addProperty("callsPerMinute", effectiveRate(scope));
        out.add("actions", Args.strings(scope.actions().stream().sorted().toList()));
        return ToolResult.ok(out);
    }

    private CompletableFuture<ToolResult> auditQuery(Call call) {
        Integer asked = call.args().optInt("limit");
        int limit = asked == null ? AUDIT_DEFAULT : Math.max(1, Math.min(AUDIT_MAX, asked));
        return store.audit(new AgentStore.AuditFilter("agent", call.actor().id(), call.args().optString("operationId"),
                call.args().optString("region"), call.args().optString("action"), limit)).thenApply(rows -> {
            JsonArray entries = new JsonArray();
            for (AgentStore.AuditRow row : rows) {
                JsonObject entry = new JsonObject();
                entry.addProperty("at", row.at());
                entry.addProperty("action", row.action());
                entry.addProperty("target", row.target());
                entry.addProperty("operationId", row.operationId());
                entry.addProperty("result", row.result());
                entry.addProperty("reason", row.reason());
                entries.add(entry);
            }
            JsonObject out = new JsonObject();
            out.add("entries", entries);
            return ToolResult.ok(out);
        });
    }

    /** One audit row for a mutating call, unless an edit will write its own when it finishes. */
    private void audit(AgentToken token, String tool, JsonObject arguments, ToolResult result) {
        if (!AUDITED.contains(tool)) {
            return;
        }
        boolean dryRun = arguments.has("dryRun") && arguments.get("dryRun").isJsonPrimitive() && arguments.get("dryRun").getAsBoolean();
        if (dryRun && !plugin.settings().logDryRuns()) {
            return;
        }
        if (result.ok() && !dryRun && AUDITED_AT_FINISH.contains(tool)) {
            return;
        }
        String outcome = result.ok() ? "ok" : result.body().getAsJsonArray("errors").get(0).getAsJsonObject().get("code").getAsString();
        String reason = arguments.has("reason") && arguments.get("reason").isJsonPrimitive() ? arguments.get("reason").getAsString() : null;
        plugin.edits().store().audit("agent", String.valueOf(token.id()), dryRun ? tool + ".dry-run" : tool,
                target(arguments), result.ok() && result.body().has("operationId") ? result.body().get("operationId").getAsString() : null,
                outcome, reason).exceptionally(failure -> {
                    plugin.getLogger().warning("Could not write audit row for " + tool + ": " + failure.getMessage());
                    return null;
                });
    }

    private static String target(JsonObject arguments) {
        StringBuilder out = new StringBuilder();
        for (String key : List.of("world", "region", "flag", "player", "operationId", "pos1", "pos2", "at", "min", "max")) {
            if (arguments.has(key)) {
                out.append(out.isEmpty() ? "" : " ").append(key).append('=').append(arguments.get(key).isJsonPrimitive()
                        ? arguments.get(key).getAsString() : arguments.get(key).toString());
            }
        }
        return out.toString();
    }

    static <T> CompletableFuture<T> done(T value) {
        return CompletableFuture.completedFuture(value);
    }
}
