package xyz.joogiebear.surveyor.paper;

import static net.kyori.adventure.text.minimessage.tag.resolver.Placeholder.unparsed;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import xyz.joogiebear.surveyor.core.region.Audience;
import xyz.joogiebear.surveyor.core.region.Cuboid;
import xyz.joogiebear.surveyor.core.region.Flag;
import xyz.joogiebear.surveyor.core.region.FlagValue;
import xyz.joogiebear.surveyor.core.region.Region;
import xyz.joogiebear.surveyor.core.region.Role;
import xyz.joogiebear.surveyor.paper.storage.RegionException;

/** The `/sv region ...` subcommands. Reads memory on the caller's thread; writes go through the store. */
final class RegionCommand {

    static final List<String> SUBCOMMANDS = List.of(
            "create", "delete", "info", "list", "addmember", "removemember", "owner", "flag", "priority");

    private static final int LIST_LIMIT = 50;

    private final SurveyorPlugin plugin;

    RegionCommand(SurveyorPlugin plugin) {
        this.plugin = plugin;
    }

    /** @param args everything after `region` */
    void run(CommandSender sender, String label, String[] args) {
        Messages messages = plugin.messages();
        if (!plugin.regions().loaded()) {
            sender.sendMessage(messages.get("error.not-loaded"));
            return;
        }
        String sub = args.length == 0 ? "" : args[0].toLowerCase();
        switch (sub) {
            case "create" -> create(sender, label, args);
            case "delete" -> withRegion(sender, label, args, 2, region ->
                    done(sender, plugin.regions().delete(region.id()), "region.deleted"));
            case "info" -> withRegion(sender, label, args, 2, region -> info(sender, region));
            case "list" -> list(sender, args);
            case "addmember" -> withRegionAndPlayer(sender, label, args, (region, player) -> {
                if (region.roleOf(player) != null) {
                    sender.sendMessage(messages.get("error.already-member"));
                    return;
                }
                done(sender, plugin.regions().setMember(region.id(), player, Role.MEMBER), "region.member-added");
            });
            case "removemember" -> withRegionAndPlayer(sender, label, args, (region, player) ->
                    done(sender, plugin.regions().removeMember(region.id(), player), "region.member-removed"));
            case "owner" -> withRegionAndPlayer(sender, label, args, (region, player) ->
                    done(sender, plugin.regions().setMember(region.id(), player, Role.OWNER), "region.owner-set"));
            case "flag" -> flag(sender, label, args);
            case "priority" -> priority(sender, label, args);
            default -> sender.sendMessage(messages.get("region.usage", unparsed("label", label)));
        }
    }

    private void create(CommandSender sender, String label, String[] args) {
        Messages messages = plugin.messages();
        if (args.length != 8 && args.length != 2) {
            usage(sender, label, "create");
            return;
        }
        Ref ref = ref(sender, args[1]);
        if (ref == null) {
            return;
        }
        if (!Region.isValidName(ref.name())) {
            sender.sendMessage(messages.get("error.bad-name"));
            return;
        }
        if (Bukkit.getWorld(ref.world()) == null) {
            sender.sendMessage(messages.get("error.unknown-world", unparsed("world", ref.world())));
            return;
        }
        Cuboid bounds = args.length == 2 ? fromSelection(sender, ref.world()) : fromCoordinates(sender, args);
        if (bounds == null) {
            return;
        }
        UUID owner = sender instanceof Player p ? p.getUniqueId() : null;
        done(sender, plugin.regions().create(ref.world(), ref.name(), bounds, owner), "region.created");
    }

    private Cuboid fromSelection(CommandSender sender, String world) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(plugin.messages().get("error.selection-needs-player"));
            return null;
        }
        Cuboid bounds = plugin.edits().selections().get(player.getUniqueId(), world).cuboid().orElse(null);
        if (bounds == null) {
            sender.sendMessage(plugin.messages().get("error.no-selection"));
        }
        return bounds;
    }

    private Cuboid fromCoordinates(CommandSender sender, String[] args) {
        Messages messages = plugin.messages();
        int[] base = sender instanceof Player p
                ? new int[] {p.getLocation().getBlockX(), p.getLocation().getBlockY(), p.getLocation().getBlockZ()}
                : null;
        int[] c = new int[6];
        for (int i = 0; i < 6; i++) {
            String token = args[2 + i];
            boolean relative = token.startsWith("~");
            if (relative && base == null) {
                sender.sendMessage(messages.get("error.relative-needs-player"));
                return null;
            }
            try {
                int offset = relative && token.length() > 1 ? Integer.parseInt(token.substring(1)) : relative ? 0 : Integer.parseInt(token);
                c[i] = relative ? base[i % 3] + offset : offset;
            } catch (NumberFormatException e) {
                sender.sendMessage(messages.get("error.bad-number", unparsed("value", token)));
                return null;
            }
        }
        return Cuboid.of(c[0], c[1], c[2], c[3], c[4], c[5]);
    }

    private void info(CommandSender sender, Region region) {
        Messages messages = plugin.messages();
        String none = messages.raw("region.none");
        Cuboid b = region.bounds();
        sender.sendMessage(messages.get("region.info.header", unparsed("name", region.name()), unparsed("world", region.world())));
        sender.sendMessage(messages.get("region.info.bounds",
                unparsed("min", b.minX() + " " + b.minY() + " " + b.minZ()),
                unparsed("max", b.maxX() + " " + b.maxY() + " " + b.maxZ()),
                unparsed("priority", String.valueOf(region.priority()))));
        sender.sendMessage(messages.get("region.info.owners", unparsed("list", players(region, Role.OWNER, none))));
        sender.sendMessage(messages.get("region.info.members", unparsed("list", players(region, Role.MEMBER, none))));
        String flags = region.flags().entrySet().stream()
                .map(e -> e.getKey().key() + "=" + (e.getValue().allow() ? "allow" : "deny")
                        + "(" + e.getValue().audience().key() + ")")
                .sorted()
                .collect(Collectors.joining(", "));
        sender.sendMessage(messages.get("region.info.flags", unparsed("list", flags.isEmpty() ? none : flags)));
    }

    private void list(CommandSender sender, String[] args) {
        Messages messages = plugin.messages();
        String world = args.length > 1 ? args[1] : sender instanceof Player p ? p.getWorld().getName() : null;
        List<Region> regions = plugin.regions().snapshot().list(world);
        if (regions.isEmpty()) {
            sender.sendMessage(messages.get("region.list-empty"));
            return;
        }
        sender.sendMessage(messages.get("region.list-header", unparsed("count", String.valueOf(regions.size()))));
        for (Region region : regions.stream().limit(LIST_LIMIT).toList()) {
            sender.sendMessage(messages.get("region.list-entry",
                    unparsed("name", region.name()), unparsed("world", region.world())));
        }
        if (regions.size() > LIST_LIMIT) {
            sender.sendMessage(messages.get("region.list-more", unparsed("count", String.valueOf(regions.size() - LIST_LIMIT))));
        }
    }

    private void flag(CommandSender sender, String label, String[] args) {
        Messages messages = plugin.messages();
        if (args.length < 4 || args.length > 5) {
            usage(sender, label, "flag");
            return;
        }
        Region region = existing(sender, args[1]);
        if (region == null) {
            return;
        }
        Flag flag = Flag.fromKey(args[2]).orElse(null);
        if (flag == null) {
            sender.sendMessage(messages.get("error.bad-flag", unparsed("value", args[2])));
            return;
        }
        String action = args[3].toLowerCase();
        if (action.equals("clear")) {
            done(sender, plugin.regions().clearFlag(region.id(), flag), "region.flag-cleared", unparsed("flag", flag.key()));
            return;
        }
        if (!action.equals("allow") && !action.equals("deny")) {
            sender.sendMessage(messages.get("error.bad-value", unparsed("value", args[3])));
            return;
        }
        Audience audience = args.length == 5 ? Audience.fromKey(args[4]).orElse(null) : Audience.EVERYONE;
        if (audience == null) {
            sender.sendMessage(messages.get("error.bad-audience", unparsed("value", args[4])));
            return;
        }
        done(sender, plugin.regions().setFlag(region.id(), flag, new FlagValue(action.equals("allow"), audience)),
                "region.flag-set", unparsed("flag", flag.key()));
    }

    private void priority(CommandSender sender, String label, String[] args) {
        if (args.length != 3) {
            usage(sender, label, "priority");
            return;
        }
        Region region = existing(sender, args[1]);
        if (region == null) {
            return;
        }
        try {
            done(sender, plugin.regions().setPriority(region.id(), Integer.parseInt(args[2])), "region.priority-set");
        } catch (NumberFormatException e) {
            sender.sendMessage(plugin.messages().get("error.bad-number", unparsed("value", args[2])));
        }
    }

    private void withRegion(CommandSender sender, String label, String[] args, int expected,
                            Consumer<Region> action) {
        if (args.length != expected) {
            usage(sender, label, args[0].toLowerCase());
            return;
        }
        Region region = existing(sender, args[1]);
        if (region != null) {
            action.accept(region);
        }
    }

    private void withRegionAndPlayer(CommandSender sender, String label, String[] args,
                                     BiConsumer<Region, UUID> action) {
        if (args.length != 3) {
            usage(sender, label, args[0].toLowerCase());
            return;
        }
        Region region = existing(sender, args[1]);
        if (region == null) {
            return;
        }
        // Cached lookup only: a name lookup against Mojang would block the region thread.
        OfflinePlayer player = Bukkit.getPlayerExact(args[2]) != null
                ? Bukkit.getPlayerExact(args[2]) : Bukkit.getOfflinePlayerIfCached(args[2]);
        if (player == null) {
            sender.sendMessage(plugin.messages().get("error.unknown-player", unparsed("value", args[2])));
            return;
        }
        action.accept(region, player.getUniqueId());
    }

    private void done(CommandSender sender, CompletableFuture<Region> future, String key,
                      TagResolver... extra) {
        future.whenComplete((region, error) -> {
            if (error != null) {
                fail(sender, error);
                return;
            }
            var resolvers = new ArrayList<>(List.of(extra));
            resolvers.add(unparsed("region", region.name()));
            plugin.reply(sender, plugin.messages().get(key, resolvers.toArray(new TagResolver[0])));
        });
    }

    private void fail(CommandSender sender, Throwable error) {
        Throwable cause = error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
        if (cause instanceof RegionException) {
            plugin.reply(sender, plugin.messages().get("error." + cause.getMessage()));
            return;
        }
        plugin.getLogger().severe("Region command failed: " + cause);
        plugin.reply(sender, plugin.messages().get("error.internal"));
    }

    private record Ref(String world, String name) {
    }

    /** Parses `name` (in the player's world) or `world:name`. Reports the problem and returns null if it cannot. */
    private Ref ref(CommandSender sender, String token) {
        int colon = token.indexOf(':');
        if (colon >= 0) {
            return new Ref(token.substring(0, colon), token.substring(colon + 1));
        }
        if (sender instanceof Player player) {
            return new Ref(player.getWorld().getName(), token);
        }
        sender.sendMessage(plugin.messages().get("error.world-required"));
        return null;
    }

    private Region existing(CommandSender sender, String token) {
        Ref ref = ref(sender, token);
        if (ref == null) {
            return null;
        }
        Region region = plugin.regions().snapshot().find(ref.world(), ref.name());
        if (region == null) {
            sender.sendMessage(plugin.messages().get("error.no-such-region", unparsed("value", token)));
        }
        return region;
    }

    private void usage(CommandSender sender, String label, String sub) {
        sender.sendMessage(plugin.messages().get("region.usage-" + sub, unparsed("label", label)));
    }

    private String players(Region region, Role role, String none) {
        String names = region.members().entrySet().stream()
                .filter(e -> e.getValue() == role)
                .map(e -> displayName(e.getKey()))
                .sorted()
                .collect(Collectors.joining(", "));
        return names.isEmpty() ? none : names;
    }

    private static String displayName(UUID id) {
        String name = Bukkit.getOfflinePlayer(id).getName();
        return name != null ? name : id.toString();
    }

    List<String> complete(CommandSender sender, String[] args) {
        String last = args[args.length - 1].toLowerCase();
        List<String> options = switch (args.length) {
            case 1 -> SUBCOMMANDS;
            case 2 -> args[0].equalsIgnoreCase("create") ? List.of() : regionNames(sender);
            case 3 -> switch (args[0].toLowerCase()) {
                case "addmember", "removemember", "owner" -> Bukkit.getOnlinePlayers().stream().map(Player::getName).toList();
                case "flag" -> Arrays.stream(Flag.values()).map(Flag::key).toList();
                default -> List.of();
            };
            case 4 -> args[0].equalsIgnoreCase("flag") ? List.of("allow", "deny", "clear") : List.of();
            case 5 -> args[0].equalsIgnoreCase("flag")
                    ? Arrays.stream(Audience.values()).map(Audience::key).toList() : List.of();
            default -> List.of();
        };
        return options.stream().filter(o -> o.toLowerCase().startsWith(last)).toList();
    }

    private List<String> regionNames(CommandSender sender) {
        String world = sender instanceof Player p ? p.getWorld().getName() : null;
        return plugin.regions().snapshot().list(world).stream().map(Region::name).toList();
    }
}
