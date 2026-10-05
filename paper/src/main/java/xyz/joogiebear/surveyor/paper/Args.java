package xyz.joogiebear.surveyor.paper;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.Collection;
import xyz.joogiebear.surveyor.core.region.Cuboid;
import xyz.joogiebear.surveyor.core.region.Region;
import xyz.joogiebear.surveyor.core.region.Role;

/** Typed reads of tool arguments. The validator has already checked names, types and required fields. */
record Args(JsonObject json) {

    boolean has(String key) {
        return json.has(key);
    }

    String string(String key) {
        return json.get(key).getAsString();
    }

    String optString(String key) {
        return json.has(key) ? json.get(key).getAsString() : null;
    }

    boolean bool(String key) {
        return json.has(key) && json.get(key).getAsBoolean();
    }

    Integer optInt(String key) {
        return json.has(key) ? json.get(key).getAsInt() : null;
    }

    int integer(String key) {
        return json.get(key).getAsInt();
    }

    int[] position(String key) {
        JsonArray array = json.getAsJsonArray(key);
        return new int[] {array.get(0).getAsInt(), array.get(1).getAsInt(), array.get(2).getAsInt()};
    }

    int[] optPosition(String key) {
        return json.has(key) ? position(key) : null;
    }

    static JsonArray position(int x, int y, int z) {
        JsonArray array = new JsonArray();
        array.add(x);
        array.add(y);
        array.add(z);
        return array;
    }

    static JsonArray strings(Collection<String> values) {
        JsonArray array = new JsonArray();
        values.forEach(array::add);
        return array;
    }

    static JsonObject skipped(long protectedBlocks, long outOfScope) {
        JsonObject out = new JsonObject();
        out.addProperty("protected", protectedBlocks);
        out.addProperty("outOfScope", outOfScope);
        return out;
    }

    static JsonObject area(Cuboid area) {
        JsonObject out = new JsonObject();
        out.add("min", position(area.minX(), area.minY(), area.minZ()));
        out.add("max", position(area.maxX(), area.maxY(), area.maxZ()));
        return out;
    }

    static JsonObject region(Region region) {
        JsonObject out = new JsonObject();
        out.addProperty("region", region.name());
        out.addProperty("world", region.world());
        out.add("min", position(region.bounds().minX(), region.bounds().minY(), region.bounds().minZ()));
        out.add("max", position(region.bounds().maxX(), region.bounds().maxY(), region.bounds().maxZ()));
        out.addProperty("priority", region.priority());
        JsonArray owners = new JsonArray();
        JsonArray members = new JsonArray();
        region.members().forEach((player, role) -> (role == Role.OWNER ? owners : members)
                .add(player.toString()));
        out.add("owners", owners);
        out.add("members", members);
        JsonObject flags = new JsonObject();
        region.flags().forEach((flag, value) -> {
            JsonObject entry = new JsonObject();
            entry.addProperty("value", value.allow() ? "allow" : "deny");
            entry.addProperty("audience", value.audience().key());
            flags.add(flag.key(), entry);
        });
        out.add("flags", flags);
        return out;
    }
}
