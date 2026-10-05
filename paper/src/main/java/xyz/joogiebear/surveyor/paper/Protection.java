package xyz.joogiebear.surveyor.paper;

import static net.kyori.adventure.text.minimessage.tag.resolver.Placeholder.unparsed;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import xyz.joogiebear.surveyor.core.audit.EventCoalescer;
import xyz.joogiebear.surveyor.core.region.Flag;
import xyz.joogiebear.surveyor.core.region.FlagResolver;
import xyz.joogiebear.surveyor.core.region.Region;
import xyz.joogiebear.surveyor.paper.storage.RegionStore;

/** Answers "may this happen here". Only reads memory, so it is safe on any region thread. */
final class Protection {

    static final String BYPASS = "surveyor.bypass.region";

    private static final long NOTICE_COOLDOWN_NANOS = 1_000_000_000L;
    private static final long BYPASS_WINDOW_MILLIS = 60_000L;

    private final SurveyorPlugin plugin;
    private final RegionStore regions;
    private final Map<UUID, Long> lastNotice = new ConcurrentHashMap<>();
    private final EventCoalescer bypasses = new EventCoalescer(BYPASS_WINDOW_MILLIS);

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
        String world = where.getWorld().getName();
        String regionNames = String.join(",", regions.snapshot()
                .at(world, where.getBlockX(), where.getBlockY(), where.getBlockZ()).stream().map(Region::name).toList());
        var key = new EventCoalescer.Key(player.getUniqueId().toString(), "bypass." + flag.key(), regionNames);
        // A bypassing builder breaks blocks by the hundred; one row per window says the same thing.
        if (bypasses.offer(key, System.currentTimeMillis())) {
            writeBypass(key, world + " " + where.getBlockX() + " " + where.getBlockY() + " " + where.getBlockZ()
                    + (regionNames.isEmpty() ? "" : " in " + regionNames));
        }
        return false;
    }

    /** Writes the counts of bypass windows that have closed as one summary row each. */
    void flushBypasses(boolean all) {
        for (EventCoalescer.Suppressed more : bypasses.drain(System.currentTimeMillis(), all)) {
            EventCoalescer.Key key = more.key();
            writeBypass(key, (key.where().isEmpty() ? "no region" : key.where()) + ": " + more.count()
                    + " more within " + BYPASS_WINDOW_MILLIS / 1000 + " s");
        }
    }

    private void writeBypass(EventCoalescer.Key key, String target) {
        regions.audit("player", key.actor(), key.action(), target, "allowed").exceptionally(error -> {
            plugin.getLogger().warning("Could not write bypass audit row: " + error.getMessage());
            return null;
        });
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
