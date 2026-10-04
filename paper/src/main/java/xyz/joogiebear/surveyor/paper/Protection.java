package xyz.joogiebear.surveyor.paper;

import static net.kyori.adventure.text.minimessage.tag.resolver.Placeholder.unparsed;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import xyz.joogiebear.surveyor.core.region.Flag;
import xyz.joogiebear.surveyor.core.region.FlagResolver;
import xyz.joogiebear.surveyor.paper.storage.RegionStore;

/** Answers "may this happen here". Only reads memory, so it is safe on any region thread. */
final class Protection {

    static final String BYPASS = "surveyor.bypass.region";

    private static final long NOTICE_COOLDOWN_NANOS = 1_000_000_000L;

    private final SurveyorPlugin plugin;
    private final RegionStore regions;
    private final Map<UUID, Long> lastNotice = new ConcurrentHashMap<>();

    Protection(SurveyorPlugin plugin, RegionStore regions) {
        this.plugin = plugin;
        this.regions = regions;
    }

    /** The region rules alone, ignoring bypass. Actor may be null for events with no player. */
    boolean resolve(UUID actor, Location where, Flag flag) {
        boolean defaultAllow = plugin.settings().defaults().get(flag);
        if (!regions.loaded()) {
            // Before regions are loaded, protected land is unknown, so edits must not slip through.
            return (flag != Flag.BUILD && flag != Flag.INTERACT) && defaultAllow;
        }
        var at = regions.snapshot().at(where.getWorld().getName(), where.getBlockX(), where.getBlockY(), where.getBlockZ());
        return FlagResolver.resolve(at, flag, actor, defaultAllow);
    }

    /** True when the player is stopped. A denial overridden by the bypass permission is audited. */
    boolean denies(Player player, Location where, Flag flag) {
        if (resolve(player.getUniqueId(), where, flag)) {
            return false;
        }
        if (!player.hasPermission(BYPASS)) {
            return true;
        }
        String target = where.getWorld().getName() + " " + where.getBlockX() + " " + where.getBlockY() + " "
                + where.getBlockZ();
        regions.audit("player", player.getUniqueId().toString(), "bypass." + flag.key(), target, "allowed")
                .exceptionally(error -> {
                    plugin.getLogger().warning("Could not write bypass audit row: " + error.getMessage());
                    return null;
                });
        return false;
    }

    void notifyDenied(Player player, Flag flag) {
        long now = System.nanoTime();
        Long last = lastNotice.get(player.getUniqueId());
        if (last != null && now - last < NOTICE_COOLDOWN_NANOS) {
            return;
        }
        lastNotice.put(player.getUniqueId(), now);
        plugin.reply(player, plugin.messages().get("protection.denied", unparsed("flag", flag.key())));
    }

    void forget(UUID player) {
        lastNotice.remove(player);
    }
}
