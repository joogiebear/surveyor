package xyz.joogiebear.surveyor.core.region;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

public record Region(long id, String world, String name, Cuboid bounds, int priority,
                     Map<UUID, Role> members, Map<Flag, FlagValue> flags) {

    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_-]{1,32}");

    public Region {
        if (!isValidName(name)) {
            throw new IllegalArgumentException("region names use letters, digits, _ and -, 1 to 32 characters");
        }
        members = Map.copyOf(members);
        flags = Map.copyOf(flags);
    }

    public static boolean isValidName(String name) {
        return name != null && NAME.matcher(name).matches();
    }

    /** The role of this actor in the region, or null when not a member (or no actor). */
    public Role roleOf(UUID actor) {
        return actor == null ? null : members.get(actor);
    }

    public Region withPriority(int value) {
        return new Region(id, world, name, bounds, value, members, flags);
    }

    public Region withMember(UUID player, Role role) {
        Map<UUID, Role> next = new HashMap<>(members);
        next.put(player, role);
        return new Region(id, world, name, bounds, priority, next, flags);
    }

    public Region withoutMember(UUID player) {
        Map<UUID, Role> next = new HashMap<>(members);
        next.remove(player);
        return new Region(id, world, name, bounds, priority, next, flags);
    }

    public Region withFlag(Flag flag, FlagValue value) {
        Map<Flag, FlagValue> next = new EnumMap<>(Flag.class);
        next.putAll(flags);
        next.put(flag, value);
        return new Region(id, world, name, bounds, priority, members, next);
    }

    public Region withoutFlag(Flag flag) {
        Map<Flag, FlagValue> next = new EnumMap<>(Flag.class);
        next.putAll(flags);
        next.remove(flag);
        return new Region(id, world, name, bounds, priority, members, next);
    }
}
