package xyz.joogiebear.surveyor.core.agent;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import java.util.LinkedHashSet;
import java.util.Set;
import xyz.joogiebear.surveyor.core.region.Cuboid;

/**
 * What an agent token may touch. A token with no worlds or no actions can do nothing, so a new
 * token is harmless until an admin scopes it. With a box, the token is confined to that box in
 * its world: positions outside it, in any world, are out of scope.
 *
 * @param maxBlocks    per-operation block cap, or null to use {@code limits.max-blocks-per-operation}
 * @param ratePerMinute calls per minute, or null to use {@code agents.default-rate-limit-per-minute}
 */
public record AgentScope(Set<String> worlds, Box box, Set<String> actions, Integer maxBlocks, Integer ratePerMinute) {

    public static final AgentScope NONE = new AgentScope(Set.of(), null, Set.of(), null, null);

    public record Box(String world, Cuboid area) {
    }

    public AgentScope {
        worlds = Set.copyOf(worlds);
        actions = Set.copyOf(actions);
        if (maxBlocks != null && maxBlocks < 1) {
            throw new IllegalArgumentException("maxBlocks must be at least 1");
        }
        if (ratePerMinute != null && ratePerMinute < 1) {
            throw new IllegalArgumentException("ratePerMinute must be at least 1");
        }
        for (String action : actions) {
            if (!AgentActions.isValid(action)) {
                throw new IllegalArgumentException("unknown action " + action);
            }
        }
    }

    public boolean allowsAction(String tool) {
        return actions.stream().anyMatch(pattern -> AgentActions.covers(pattern, tool));
    }

    public boolean allowsWorld(String world) {
        return worlds.contains(world);
    }

    public boolean contains(String world, int x, int y, int z) {
        if (!worlds.contains(world)) {
            return false;
        }
        return box == null || (box.world().equals(world) && box.area().contains(x, y, z));
    }

    /** Whether any part of the area is inside the scope. */
    public boolean touches(String world, Cuboid area) {
        if (!worlds.contains(world)) {
            return false;
        }
        return box == null || (box.world().equals(world) && box.area().intersects(area));
    }

    /** Whether all of the area is inside the scope. */
    public boolean covers(String world, Cuboid area) {
        if (!worlds.contains(world)) {
            return false;
        }
        return box == null || (box.world().equals(world) && box.area().contains(area));
    }

    public AgentScope withWorlds(Set<String> next) {
        return new AgentScope(next, box, actions, maxBlocks, ratePerMinute);
    }

    public AgentScope withBox(Box next) {
        return new AgentScope(worlds, next, actions, maxBlocks, ratePerMinute);
    }

    public AgentScope withActions(Set<String> next) {
        return new AgentScope(worlds, box, next, maxBlocks, ratePerMinute);
    }

    public AgentScope withMaxBlocks(Integer next) {
        return new AgentScope(worlds, box, actions, next, ratePerMinute);
    }

    public AgentScope withRatePerMinute(Integer next) {
        return new AgentScope(worlds, box, actions, maxBlocks, next);
    }

    public String toJson() {
        JsonObject out = new JsonObject();
        out.add("worlds", strings(worlds));
        if (box != null) {
            JsonObject b = new JsonObject();
            b.addProperty("world", box.world());
            b.add("min", ints(box.area().minX(), box.area().minY(), box.area().minZ()));
            b.add("max", ints(box.area().maxX(), box.area().maxY(), box.area().maxZ()));
            out.add("box", b);
        }
        out.add("actions", strings(actions));
        if (maxBlocks != null) {
            out.addProperty("maxBlocks", maxBlocks);
        }
        if (ratePerMinute != null) {
            out.addProperty("ratePerMinute", ratePerMinute);
        }
        return out.toString();
    }

    /** @throws IllegalArgumentException if the text is not a valid scope */
    public static AgentScope fromJson(String json) {
        try {
            JsonObject in = JsonParser.parseString(json).getAsJsonObject();
            Box box = null;
            if (in.has("box") && !in.get("box").isJsonNull()) {
                JsonObject b = in.getAsJsonObject("box");
                int[] min = intArray(b.getAsJsonArray("min"));
                int[] max = intArray(b.getAsJsonArray("max"));
                box = new Box(b.get("world").getAsString(), Cuboid.of(min[0], min[1], min[2], max[0], max[1], max[2]));
            }
            return new AgentScope(strings(in.get("worlds")), box, strings(in.get("actions")),
                    optionalInt(in, "maxBlocks"), optionalInt(in, "ratePerMinute"));
        } catch (JsonParseException | IllegalStateException | ClassCastException | NullPointerException
                 | IndexOutOfBoundsException | NumberFormatException e) {
            throw new IllegalArgumentException("invalid scope: " + e.getMessage(), e);
        }
    }

    private static JsonArray strings(Set<String> values) {
        JsonArray array = new JsonArray();
        values.stream().sorted().forEach(array::add);
        return array;
    }

    private static JsonArray ints(int... values) {
        JsonArray array = new JsonArray();
        for (int value : values) {
            array.add(value);
        }
        return array;
    }

    private static Set<String> strings(JsonElement element) {
        Set<String> out = new LinkedHashSet<>();
        if (element != null && !element.isJsonNull()) {
            element.getAsJsonArray().forEach(e -> out.add(e.getAsString()));
        }
        return out;
    }

    private static int[] intArray(JsonArray array) {
        if (array.size() != 3) {
            throw new IllegalArgumentException("a box corner needs three numbers");
        }
        return new int[] {array.get(0).getAsInt(), array.get(1).getAsInt(), array.get(2).getAsInt()};
    }

    private static Integer optionalInt(JsonObject in, String key) {
        return in.has(key) && !in.get(key).isJsonNull() ? in.get(key).getAsInt() : null;
    }
}
