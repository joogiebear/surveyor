package xyz.joogiebear.surveyor.paper;

import static xyz.joogiebear.surveyor.paper.AgentApi.done;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import xyz.joogiebear.surveyor.core.agent.AgentScope;
import xyz.joogiebear.surveyor.core.agent.ErrorCode;
import xyz.joogiebear.surveyor.core.region.Audience;
import xyz.joogiebear.surveyor.core.region.Cuboid;
import xyz.joogiebear.surveyor.core.region.Flag;
import xyz.joogiebear.surveyor.core.region.FlagValue;
import xyz.joogiebear.surveyor.core.region.Region;
import xyz.joogiebear.surveyor.core.region.Role;
import xyz.joogiebear.surveyor.mcp.ToolResult;
import xyz.joogiebear.surveyor.paper.storage.RegionException;

/**
 * The region, flag and member tools. Reads come from the in-memory snapshot; writes go through the
 * same {@link xyz.joogiebear.surveyor.paper.storage.RegionStore} as the commands. A token only sees
 * and changes regions that touch (read) or lie entirely inside (write) its scope.
 */
final class AgentRegions {

    private static final int LIST_LIMIT = 200;

    private final SurveyorPlugin plugin;

    AgentRegions(SurveyorPlugin plugin) {
        this.plugin = plugin;
    }

    CompletableFuture<ToolResult> run(AgentApi.Call call) {
        if (!plugin.regions().loaded()) {
            throw new ToolException(ErrorCode.INTERNAL, "Regions have not loaded yet. Try again in a moment.");
        }
        return switch (call.tool()) {
            case "region.create" -> create(call);
            case "region.update" -> update(call);
            case "region.delete" -> delete(call);
            case "region.get" -> done(ToolResult.ok(single(visible(call))));
            case "region.list" -> done(list(call));
            case "region.at" -> done(at(call));
            case "flag.set" -> flagSet(call);
            case "flag.clear" -> flagClear(call);
            case "member.add" -> memberAdd(call);
            case "member.remove" -> memberRemove(call);
            default -> throw new IllegalStateException("tool " + call.tool() + " is not a region tool");
        };
    }

    private CompletableFuture<ToolResult> create(AgentApi.Call call) {
        String world = world(call);
        String name = call.args().string("region");
        if (!Region.isValidName(name)) {
            throw new ToolException(ErrorCode.INVALID_ARGUMENT, "Region names use letters, digits, _ and -, 1 to 32 characters.");
        }
        if (Bukkit.getWorld(world) == null) {
            throw new ToolException(ErrorCode.NOT_FOUND, "There is no loaded world '" + world + "'. Call world.list.");
        }
        int[] a = call.args().position("min");
        int[] b = call.args().position("max");
        Cuboid bounds = Cuboid.of(a[0], a[1], a[2], b[0], b[1], b[2]);
        if (!call.scope().covers(world, bounds)) {
            throw new ToolException(ErrorCode.OUT_OF_SCOPE, "A region made by this token must lie entirely inside its scope"
                    + boxText(call.scope()) + ".");
        }
        if (plugin.regions().snapshot().find(world, name) != null) {
            throw new ToolException(ErrorCode.CONFLICT, "A region named '" + name + "' already exists in " + world + ".");
        }
        if (call.dryRun()) {
            return done(planned(Args.region(new Region(0, world, name, bounds, 0, Map.of(), Map.of()))));
        }
        return written(plugin.regions().create(world, name, bounds, null));
    }

    private CompletableFuture<ToolResult> update(AgentApi.Call call) {
        Region region = writable(call);
        int priority = call.args().integer("priority");
        if (call.dryRun()) {
            return done(planned(Args.region(region.withPriority(priority))));
        }
        return written(plugin.regions().setPriority(region.id(), priority));
    }

    private CompletableFuture<ToolResult> delete(AgentApi.Call call) {
        Region region = writable(call);
        if (call.dryRun()) {
            return done(planned(Args.region(region)));
        }
        return written(plugin.regions().delete(region.id()));
    }

    private CompletableFuture<ToolResult> flagSet(AgentApi.Call call) {
        Region region = writable(call);
        Flag flag = flag(call);
        Audience audience = call.args().has("audience") ? Audience.fromKey(call.args().string("audience")).orElseThrow() : Audience.EVERYONE;
        FlagValue value = new FlagValue(call.args().string("value").equals("allow"), audience);
        if (call.dryRun()) {
            return done(planned(Args.region(region.withFlag(flag, value))));
        }
        return written(plugin.regions().setFlag(region.id(), flag, value));
    }

    private CompletableFuture<ToolResult> flagClear(AgentApi.Call call) {
        Region region = writable(call);
        Flag flag = flag(call);
        if (call.dryRun()) {
            return done(planned(Args.region(region.withoutFlag(flag))));
        }
        return written(plugin.regions().clearFlag(region.id(), flag));
    }

    private CompletableFuture<ToolResult> memberAdd(AgentApi.Call call) {
        Region region = writable(call);
        UUID player = player(call.args().string("player"));
        Role role = call.args().has("role") && call.args().string("role").equals("owner") ? Role.OWNER : Role.MEMBER;
        Role current = region.roleOf(player);
        if (current != null && role == Role.MEMBER) {
            throw new ToolException(ErrorCode.CONFLICT, current == Role.OWNER
                    ? "That player is already an owner of this region."
                    : "That player is already a member of this region. Use role 'owner' to make them an owner.");
        }
        if (call.dryRun()) {
            return done(planned(Args.region(region.withMember(player, role))));
        }
        return written(plugin.regions().setMember(region.id(), player, role));
    }

    private CompletableFuture<ToolResult> memberRemove(AgentApi.Call call) {
        Region region = writable(call);
        UUID player = player(call.args().string("player"));
        if (region.roleOf(player) == null) {
            throw new ToolException(ErrorCode.NOT_FOUND, "That player is not a member of this region.");
        }
        if (call.dryRun()) {
            return done(planned(Args.region(region.withoutMember(player))));
        }
        return written(plugin.regions().removeMember(region.id(), player));
    }

    private ToolResult list(AgentApi.Call call) {
        String only = call.args().optString("world");
        if (only != null && !call.scope().allowsWorld(only)) {
            throw new ToolException(ErrorCode.OUT_OF_SCOPE, "This token may not use world '" + only + "'.");
        }
        List<Region> regions = plugin.regions().snapshot().list(only).stream()
                .filter(region -> call.scope().touches(region.world(), region.bounds())).toList();
        JsonArray entries = new JsonArray();
        regions.stream().limit(LIST_LIMIT).forEach(region -> entries.add(Args.region(region)));
        JsonObject out = new JsonObject();
        out.add("regions", entries);
        out.addProperty("total", regions.size());
        if (regions.size() > LIST_LIMIT) {
            out.add("warnings", Args.strings(List.of("Only the first " + LIST_LIMIT + " regions are listed. Pass a world to narrow it.")));
        }
        return ToolResult.ok(out);
    }

    private ToolResult at(AgentApi.Call call) {
        String world = world(call);
        int[] at = call.args().position("at");
        if (!call.scope().contains(world, at[0], at[1], at[2])) {
            throw new ToolException(ErrorCode.OUT_OF_SCOPE, "That position is outside this token's scope" + boxText(call.scope()) + ".");
        }
        JsonArray regions = new JsonArray();
        plugin.regions().snapshot().at(world, at[0], at[1], at[2]).stream()
                .sorted(Comparator.comparingInt(Region::priority).reversed()).forEach(region -> regions.add(Args.region(region)));
        JsonObject out = new JsonObject();
        out.add("regions", regions);
        return ToolResult.ok(out);
    }

    // ---- shared

    /** Applies a store change and turns a rejected change into the matching error. */
    private static CompletableFuture<ToolResult> written(CompletableFuture<Region> change) {
        return change.handle((region, failure) -> {
            if (failure == null) {
                return ToolResult.ok(single(region));
            }
            Throwable cause = failure instanceof CompletionException && failure.getCause() != null ? failure.getCause() : failure;
            if (cause instanceof RegionException e) {
                throw new CompletionException(switch (e.getMessage()) {
                    case "region-exists" -> new ToolException(ErrorCode.CONFLICT, "A region with that name already exists in this world.");
                    case "region-gone" -> new ToolException(ErrorCode.NOT_FOUND, "That region no longer exists.");
                    case "not-a-member" -> new ToolException(ErrorCode.NOT_FOUND, "That player is not a member of the region.");
                    default -> new ToolException(ErrorCode.INTERNAL, "The change was refused.");
                });
            }
            throw new CompletionException(cause);
        });
    }

    private static ToolResult planned(JsonObject region) {
        JsonObject out = single(region);
        out.add("warnings", Args.strings(List.of("Dry run: nothing was changed. 'region' shows how it would look afterwards.")));
        return ToolResult.ok(out);
    }

    private static JsonObject single(JsonObject region) {
        JsonObject out = new JsonObject();
        out.add("region", region);
        return out;
    }

    private static JsonObject single(Region region) {
        return single(Args.region(region));
    }

    private String world(AgentApi.Call call) {
        String world = call.args().string("world");
        if (!call.scope().allowsWorld(world)) {
            throw new ToolException(ErrorCode.OUT_OF_SCOPE, "This token may not use world '" + world + "'. Allowed worlds: "
                    + (call.scope().worlds().isEmpty() ? "none" : String.join(", ", call.scope().worlds().stream().sorted().toList())) + ".");
        }
        return world;
    }

    /** A region the token can see: it touches the scope. Anything else is reported as missing. */
    private Region visible(AgentApi.Call call) {
        String world = world(call);
        String name = call.args().string("region");
        Region region = plugin.regions().snapshot().find(world, name);
        if (region == null || !call.scope().touches(world, region.bounds())) {
            throw new ToolException(ErrorCode.NOT_FOUND, "There is no region '" + name + "' in " + world + " that this token can see.");
        }
        return region;
    }

    /** A region the token may change: it lies entirely inside the scope. */
    private Region writable(AgentApi.Call call) {
        Region region = visible(call);
        if (!call.scope().covers(region.world(), region.bounds())) {
            throw new ToolException(ErrorCode.OUT_OF_SCOPE, "Region '" + region.name() + "' reaches outside this token's scope"
                    + boxText(call.scope()) + ", so this token may not change it.");
        }
        return region;
    }

    private static Flag flag(AgentApi.Call call) {
        String key = call.args().string("flag");
        return Flag.fromKey(key).orElseThrow(() -> new ToolException(ErrorCode.INVALID_ARGUMENT,
                "Unknown flag '" + key + "'. Flags: build, interact, pvp, mob-spawning, entry."));
    }

    /** A UUID, an online player, or a name the server has cached. Never a lookup that waits on Mojang. */
    private static UUID player(String text) {
        try {
            UUID id = UUID.fromString(text);
            OfflinePlayer player = Bukkit.getOfflinePlayer(id);
            if (player.isOnline() || player.hasPlayedBefore()) {
                return id;
            }
        } catch (IllegalArgumentException notAUuid) {
            // fall through to the name lookups
        }
        Player online = Bukkit.getPlayerExact(text);
        OfflinePlayer player = online != null ? online : Bukkit.getOfflinePlayerIfCached(text);
        if (player == null) {
            throw new ToolException(ErrorCode.NOT_FOUND, "Unknown player '" + text + "'. They must have joined this server before.");
        }
        return player.getUniqueId();
    }

    private static String boxText(AgentScope scope) {
        if (scope.box() == null) {
            return "";
        }
        Cuboid b = scope.box().area();
        return " (box in " + scope.box().world() + ": " + b.minX() + " " + b.minY() + " " + b.minZ() + " to "
                + b.maxX() + " " + b.maxY() + " " + b.maxZ() + ")";
    }
}
