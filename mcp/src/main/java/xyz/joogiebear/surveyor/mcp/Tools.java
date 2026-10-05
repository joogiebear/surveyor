package xyz.joogiebear.surveyor.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;

/** The tool catalogue: names, descriptions and input schemas (JSON Schema 2020-12). */
public final class Tools {

    public record Definition(String name, String description, JsonObject inputSchema, boolean readOnly) {

        JsonObject toJson() {
            JsonObject out = new JsonObject();
            out.addProperty("name", name);
            out.addProperty("description", description);
            out.add("inputSchema", inputSchema);
            JsonObject annotations = new JsonObject();
            annotations.addProperty("readOnlyHint", readOnly);
            out.add("annotations", annotations);
            return out;
        }
    }

    private static final List<Definition> ALL = build();

    private Tools() {
    }

    public static List<Definition> all() {
        return ALL;
    }

    public static Definition find(String name) {
        return ALL.stream().filter(d -> d.name().equals(name)).findFirst().orElse(null);
    }

    private static List<Definition> build() {
        List<Definition> tools = new ArrayList<>();
        JsonObject world = string("World name, as listed by world.list.");
        JsonObject pos1 = position("First corner [x, y, z]. Omit pos1 and pos2 to use the selection made with selection.set.");
        JsonObject pos2 = position("Opposite corner [x, y, z].");

        tools.add(read("world.list", "Lists the worlds this token may use, with their height limits and the token's block cap.",
                props(), required()));
        tools.add(read("selection.get", "Returns this token's selection in a world.",
                props("world", world), required("world")));
        tools.add(mutate("selection.set", "Sets or clears this token's selection in a world. Nothing in the world changes.",
                props("world", world, "pos1", position("Corner 1 [x, y, z]."), "pos2", position("Corner 2 [x, y, z]."),
                        "clear", bool("Drop the selection instead of setting corners.")),
                required("world"), false));

        tools.add(mutate("edit.set", "Fills the area with a block pattern: 'stone' or weighted '50%stone,50%andesite'; a block "
                + "may carry a state, 'oak_stairs[facing=north]'. Blocks in protected regions or outside the token's scope are "
                + "skipped and counted. Use dryRun first. Big edits return an operationId at once; poll operation.status.",
                editProps(world, pos1, pos2, "pattern", string("Block pattern.")), required("world", "pattern"), true));
        tools.add(mutate("edit.replace", "Replaces blocks matching 'from' with the 'to' pattern inside the area. 'from' is a comma "
                + "separated block list without weights; a plain type matches in any state. Otherwise as edit.set.",
                editProps(world, pos1, pos2, "from", string("Block list to replace."), "to", string("Replacement pattern.")),
                required("world", "from", "to"), true));
        tools.add(mutate("edit.copy", "Copies the area to this token's clipboard. Changes nothing in the world. The clipboard "
                + "remembers where the area sat relative to 'anchor' (default: the area's minimum corner).",
                props("world", world, "pos1", pos1, "pos2", pos2, "anchor", position("Reference block [x, y, z]."),
                        "reason", reason(), "idempotencyKey", idempotencyKey()),
                required("world"), false));
        tools.add(mutate("edit.paste", "Pastes this token's clipboard so its anchor lands on 'at'. Rules as edit.set.",
                props("world", world, "at", position("Block [x, y, z] the clipboard anchor lands on."),
                        "dryRun", dryRun(), "strict", strict(), "reason", reason(), "idempotencyKey", idempotencyKey()),
                required("world", "at"), true));
        tools.add(mutate("edit.undo", "Restores the blocks an earlier edit by this token changed. Positions the token may no "
                + "longer touch are skipped. An operation can be undone once its blocks were all restored.",
                props("operationId", string("Id returned by the edit, like op_01..."), "dryRun", dryRun(),
                        "reason", reason(), "idempotencyKey", idempotencyKey()),
                required("operationId"), true));

        tools.add(mutate("region.create", "Creates a cuboid protection region. The token is not a member of it.",
                props("world", world, "region", string("Region name: 1-32 letters, digits, _ or -."),
                        "min", position("Minimum corner [x, y, z]."), "max", position("Maximum corner [x, y, z]."),
                        "dryRun", dryRun(), "reason", reason(), "idempotencyKey", idempotencyKey()),
                required("world", "region", "min", "max"), true));
        tools.add(mutate("region.update", "Changes a region's priority (higher wins where regions overlap).",
                props("world", world, "region", string("Region name."), "priority", integer("New priority."),
                        "dryRun", dryRun(), "reason", reason(), "idempotencyKey", idempotencyKey()),
                required("world", "region", "priority"), true));
        tools.add(mutate("region.delete", "Deletes a region.",
                props("world", world, "region", string("Region name."), "dryRun", dryRun(), "reason", reason(),
                        "idempotencyKey", idempotencyKey()),
                required("world", "region"), true));
        tools.add(read("region.get", "Returns a region: bounds, priority, owners, members and flags.",
                props("world", world, "region", string("Region name.")), required("world", "region")));
        tools.add(read("region.list", "Lists regions, in one world or in every world the token may use.",
                props("world", world), required()));
        tools.add(read("region.at", "Lists the regions that cover a block.",
                props("world", world, "at", position("Block [x, y, z].")), required("world", "at")));

        tools.add(mutate("flag.set", "Sets a region flag. Flags: build, interact, pvp, mob-spawning, entry.",
                props("world", world, "region", string("Region name."), "flag", string("Flag name."),
                        "value", enumeration("allow", "deny"),
                        "audience", enumeration("everyone", "members", "owners", "non-members"),
                        "dryRun", dryRun(), "reason", reason(), "idempotencyKey", idempotencyKey()),
                required("world", "region", "flag", "value"), true));
        tools.add(mutate("flag.clear", "Removes a region flag, so the config default applies again.",
                props("world", world, "region", string("Region name."), "flag", string("Flag name."),
                        "dryRun", dryRun(), "reason", reason(), "idempotencyKey", idempotencyKey()),
                required("world", "region", "flag"), true));
        tools.add(mutate("member.add", "Adds a player to a region as member or owner. The player must have joined the server before.",
                props("world", world, "region", string("Region name."), "player", string("Player name or UUID."),
                        "role", enumeration("member", "owner"), "dryRun", dryRun(), "reason", reason(),
                        "idempotencyKey", idempotencyKey()),
                required("world", "region", "player"), true));
        tools.add(mutate("member.remove", "Removes a player from a region.",
                props("world", world, "region", string("Region name."), "player", string("Player name or UUID."),
                        "dryRun", dryRun(), "reason", reason(), "idempotencyKey", idempotencyKey()),
                required("world", "region", "player"), true));

        tools.add(read("operation.status", "Returns the status and counts of one of this token's operations: planned, running, "
                + "done, failed or cancelled.",
                props("operationId", string("Id returned by the edit.")), required("operationId")));
        tools.add(mutate("operation.cancel", "Stops one of this token's running operations before its next batch. Blocks already "
                + "changed stay and can be undone.",
                props("operationId", string("Id returned by the edit."), "reason", reason()),
                required("operationId"), false));
        tools.add(read("audit.query", "Returns recent audit entries for this token, newest first.",
                props("operationId", string("Only entries of this operation."),
                        "region", string("Only entries that mention this region name."),
                        "action", string("Only entries with this action, such as edit.set."),
                        "limit", integer("Entries to return, 1-200 (default 50).")),
                required()));
        return List.copyOf(tools);
    }

    private static Definition read(String name, String description, JsonObject props, JsonArray required) {
        return new Definition(name, description, schema(props, required), true);
    }

    /** @param dryRunnable whether the schema has the dryRun argument; the caller lists it when it does */
    private static Definition mutate(String name, String description, JsonObject props, JsonArray required,
                                     boolean dryRunnable) {
        if (!props.has("reason") && !name.equals("selection.set")) {
            props.add("reason", reason());
        }
        if (dryRunnable && !props.has("dryRun")) {
            props.add("dryRun", dryRun());
        }
        return new Definition(name, description, schema(props, required), false);
    }

    private static JsonObject editProps(JsonObject world, JsonObject pos1, JsonObject pos2, Object... extra) {
        JsonObject props = props("world", world, "pos1", pos1, "pos2", pos2);
        for (int i = 0; i < extra.length; i += 2) {
            props.add((String) extra[i], (JsonObject) extra[i + 1]);
        }
        props.add("dryRun", dryRun());
        props.add("strict", strict());
        props.add("reason", reason());
        props.add("idempotencyKey", idempotencyKey());
        return props;
    }

    private static JsonObject schema(JsonObject props, JsonArray required) {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.add("properties", props);
        if (!required.isEmpty()) {
            schema.add("required", required);
        }
        schema.addProperty("additionalProperties", false);
        return schema;
    }

    private static JsonObject props(Object... pairs) {
        JsonObject props = new JsonObject();
        for (int i = 0; i < pairs.length; i += 2) {
            props.add((String) pairs[i], (JsonObject) pairs[i + 1]);
        }
        return props;
    }

    private static JsonArray required(String... names) {
        JsonArray array = new JsonArray();
        for (String name : names) {
            array.add(name);
        }
        return array;
    }

    private static JsonObject typed(String type, String description) {
        JsonObject out = new JsonObject();
        out.addProperty("type", type);
        out.addProperty("description", description);
        return out;
    }

    private static JsonObject string(String description) {
        return typed("string", description);
    }

    private static JsonObject integer(String description) {
        return typed("integer", description);
    }

    private static JsonObject bool(String description) {
        return typed("boolean", description);
    }

    private static JsonObject enumeration(String... values) {
        JsonObject out = new JsonObject();
        out.addProperty("type", "string");
        out.add("enum", required(values));
        return out;
    }

    private static JsonObject position(String description) {
        JsonObject out = typed("array", description);
        JsonObject item = new JsonObject();
        item.addProperty("type", "integer");
        out.add("items", item);
        out.addProperty("minItems", 3);
        out.addProperty("maxItems", 3);
        return out;
    }

    private static JsonObject dryRun() {
        return bool("Plan only: report what would happen and change nothing.");
    }

    private static JsonObject strict() {
        return bool("Fail the whole call, changing nothing, if any position in the area is protected or out of scope.");
    }

    private static JsonObject reason() {
        return string("Why you are doing this. Stored in the audit log; required for changes on most servers.");
    }

    private static JsonObject idempotencyKey() {
        return string("Any unique text. Repeating a call with the same key returns the first result instead of running again.");
    }
}
