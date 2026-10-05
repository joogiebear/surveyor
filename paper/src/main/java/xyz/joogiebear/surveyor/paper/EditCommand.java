package xyz.joogiebear.surveyor.paper;

import static net.kyori.adventure.text.minimessage.tag.resolver.Placeholder.unparsed;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import xyz.joogiebear.surveyor.core.edit.BatchPlanner;
import xyz.joogiebear.surveyor.core.edit.BlockMask;
import xyz.joogiebear.surveyor.core.edit.BlockPattern;
import xyz.joogiebear.surveyor.core.edit.BlockPos;
import xyz.joogiebear.surveyor.core.edit.Clipboard;
import xyz.joogiebear.surveyor.core.edit.OperationIds;
import xyz.joogiebear.surveyor.core.edit.OperationStatus;
import xyz.joogiebear.surveyor.core.edit.PatternException;
import xyz.joogiebear.surveyor.core.edit.Selection;
import xyz.joogiebear.surveyor.core.region.Cuboid;
import xyz.joogiebear.surveyor.paper.storage.EditStore;

/** The editing subcommands: wand, pos1, pos2, sel, set, replace, copy, paste, undo, cancel. */
final class EditCommand {

    static final List<String> SUBCOMMANDS = List.of(
            "wand", "pos1", "pos2", "sel", "set", "replace", "copy", "paste", "undo", "cancel");

    private static final String ADMIN = "surveyor.admin";

    private record Resolved(BlockPattern pattern, BlockData[] blocks) {
    }

    private final SurveyorPlugin plugin;

    EditCommand(SurveyorPlugin plugin) {
        this.plugin = plugin;
    }

    /** @param args the subcommand first, then its arguments */
    void run(CommandSender sender, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(plugin.messages().get("error.player-only"));
            return;
        }
        String[] rest = Arrays.copyOfRange(args, 1, args.length);
        switch (args[0].toLowerCase()) {
            case "wand" -> wand(player);
            case "pos1" -> position(player, true);
            case "pos2" -> position(player, false);
            case "sel" -> selection(player, label, rest);
            case "set" -> set(player, label, rest);
            case "replace" -> replace(player, label, rest);
            case "copy" -> copy(player);
            case "paste" -> paste(player, rest);
            case "undo" -> undo(player, label, rest);
            case "cancel" -> cancel(player, label, rest);
            default -> sender.sendMessage(plugin.messages().get("usage", unparsed("label", label)));
        }
    }

    private void wand(Player player) {
        ItemStack wand = EditListener.createWand(plugin);
        if (!player.getInventory().addItem(wand).isEmpty()) {
            player.sendMessage(plugin.messages().get("selection.wand-full"));
            return;
        }
        player.sendMessage(plugin.messages().get("selection.wand-given"));
    }

    private void position(Player player, boolean first) {
        Location at = player.getLocation();
        plugin.edits().select(player, player.getWorld().getName(), first,
                new BlockPos(at.getBlockX(), at.getBlockY(), at.getBlockZ()));
    }

    private void selection(Player player, String label, String[] args) {
        Messages messages = plugin.messages();
        String world = player.getWorld().getName();
        if (args.length == 1 && args[0].equalsIgnoreCase("clear")) {
            plugin.edits().selections().clear(player.getUniqueId(), world);
            player.sendMessage(messages.get("selection.cleared"));
            return;
        }
        if (args.length != 0) {
            player.sendMessage(messages.get("selection.usage", unparsed("label", label)));
            return;
        }
        Selection selection = plugin.edits().selections().get(player.getUniqueId(), world);
        if (selection.pos1() == null && selection.pos2() == null) {
            player.sendMessage(messages.get("selection.none"));
            return;
        }
        player.sendMessage(messages.get("selection.info",
                unparsed("pos1", describe(selection.pos1(), messages)), unparsed("pos2", describe(selection.pos2(), messages)),
                unparsed("size", selection.cuboid().map(area -> area.volume() + " blocks").orElse(messages.raw("selection.incomplete")))));
    }

    private static String describe(BlockPos pos, Messages messages) {
        return pos == null ? messages.raw("selection.unset") : pos.x() + " " + pos.y() + " " + pos.z();
    }

    private void set(Player player, String label, String[] args) {
        boolean strict = strict(args);
        String[] words = withoutStrict(args);
        if (words.length != 1) {
            player.sendMessage(plugin.messages().get("edit.usage-set", unparsed("label", label)));
            return;
        }
        Resolved to = resolve(player, words[0]);
        Cuboid area = to == null ? null : selected(player);
        if (area != null) {
            submit(player, new EditJob.Fill(new Actor.Human(player), player.getWorld(), strict, bypass(player), null, area,
                    words[0], to.pattern(), to.blocks()), area.volume());
        }
    }

    private void replace(Player player, String label, String[] args) {
        Messages messages = plugin.messages();
        boolean strict = strict(args);
        String[] words = withoutStrict(args);
        if (words.length != 2) {
            player.sendMessage(messages.get("edit.usage-replace", unparsed("label", label)));
            return;
        }
        List<Predicate<BlockData>> mask = new ArrayList<>();
        try {
            for (String token : BlockMask.parse(words[0]).blocks()) {
                mask.add(EditJob.Replace.matcher(token, Bukkit.createBlockData(token)));
            }
        } catch (PatternException e) {
            player.sendMessage(messages.get("error." + e.reason().messageKey()));
            return;
        } catch (IllegalArgumentException e) {
            player.sendMessage(messages.get("error.unknown-block", unparsed("value", words[0])));
            return;
        }
        Resolved to = resolve(player, words[1]);
        Cuboid area = to == null ? null : selected(player);
        if (area != null) {
            submit(player, new EditJob.Replace(new Actor.Human(player), player.getWorld(), strict, bypass(player), null,
                    area, words[0], words[1], mask, to.pattern(), to.blocks()), area.volume());
        }
    }

    private void copy(Player player) {
        Cuboid area = selected(player);
        if (area == null) {
            return;
        }
        UUID id = player.getUniqueId();
        Clipboard.Builder builder = Clipboard.builder(area, anchor(player));
        submit(player, new EditJob.Copy(new Actor.Human(player), player.getWorld(), area, builder,
                clipboard -> plugin.edits().setClipboard(id, clipboard)), area.volume());
    }

    private void paste(Player player, String[] args) {
        Messages messages = plugin.messages();
        Clipboard clipboard = plugin.edits().clipboard(player.getUniqueId());
        if (clipboard == null) {
            player.sendMessage(messages.get("error.no-clipboard"));
            return;
        }
        Cuboid origin = clipboard.areaAt(anchor(player));
        Cuboid area = inWorld(player, origin);
        if (area != null) {
            submit(player, new EditJob.Paste(new Actor.Human(player), player.getWorld(), strict(args), bypass(player), null,
                    area, clipboard, origin), area.volume());
        }
    }

    private void undo(Player player, String label, String[] args) {
        Messages messages = plugin.messages();
        if (args.length > 1 || (args.length == 1 && !OperationIds.isValid(args[0]))) {
            player.sendMessage(messages.get("edit.usage-undo", unparsed("label", label)));
            return;
        }
        UUID actor = player.getUniqueId();
        boolean admin = player.hasPermission(ADMIN);
        boolean bypass = bypass(player);
        EditStore store = plugin.edits().store();
        var lookup = args.length == 1 ? store.find(args[0]) : store.latestUndoable("player", actor.toString());
        lookup.whenComplete((operation, error) -> {
            if (error != null) {
                plugin.getLogger().severe("Undo lookup failed: " + error);
                plugin.reply(player, messages.get("error.internal"));
                return;
            }
            String refusal = undoRefusal(operation, actor, admin, args.length == 1);
            World world = refusal == null ? Bukkit.getWorld(operation.world()) : null;
            if (refusal == null && world == null) {
                refusal = "edit.error.world-gone";
            }
            if (refusal != null) {
                plugin.reply(player, messages.get(refusal, unparsed("id", args.length == 1 ? args[0] : "")));
                return;
            }
            EditJob.Undo job = new EditJob.Undo(new Actor.Human(player), world, bypass, null, operation.id());
            String refused = plugin.edits().start(job);
            plugin.reply(player, refused != null ? messages.get(refused)
                    : messages.get("edit.started", unparsed("id", job.id), unparsed("kind", job.kind.key()),
                            unparsed("blocks", "?")));
        });
    }

    private static String undoRefusal(EditStore.Operation operation, UUID actor, boolean admin, boolean named) {
        if (operation == null) {
            return named ? "edit.error.unknown-operation" : "edit.error.nothing-to-undo";
        }
        if (!operation.kind().journaled()) {
            return "edit.error.not-undoable";
        }
        if (!operation.actorId().equals(actor.toString()) && !admin) {
            return "edit.error.not-yours";
        }
        if (operation.undoneBy() != null) {
            return "edit.error.already-undone";
        }
        if (operation.blocksChanged() > 0 && !operation.hasJournal()) {
            return "edit.error.journal-expired";
        }
        if (operation.status() == OperationStatus.PLANNED || operation.status() == OperationStatus.RUNNING) {
            return "edit.error.still-running";
        }
        return null;
    }

    private void cancel(Player player, String label, String[] args) {
        Messages messages = plugin.messages();
        if (args.length > 1) {
            player.sendMessage(messages.get("edit.usage-cancel", unparsed("label", label)));
            return;
        }
        EditJob job = args.length == 1 ? plugin.edits().find(args[0]) : plugin.edits().latestActive(new Actor.Human(player));
        if (job == null) {
            player.sendMessage(messages.get(args.length == 1 ? "edit.error.unknown-operation" : "edit.error.nothing-running",
                    unparsed("id", args.length == 1 ? args[0] : "")));
            return;
        }
        if (!EditService.sameActor(job.actor, new Actor.Human(player)) && !player.hasPermission(ADMIN)) {
            player.sendMessage(messages.get("edit.error.not-yours"));
            return;
        }
        plugin.edits().cancel(job.id);
        player.sendMessage(messages.get("edit.cancel-requested", unparsed("id", job.id)));
    }

    private void submit(Player player, EditJob job, long blocks) {
        Messages messages = plugin.messages();
        String refused = plugin.edits().start(job);
        player.sendMessage(refused != null ? messages.get(refused)
                : messages.get("edit.started", unparsed("id", job.id), unparsed("kind", job.kind.key()),
                        unparsed("blocks", String.valueOf(blocks))));
    }

    /** The player's selection cut to the world's height, or null after telling them why not. */
    private Cuboid selected(Player player) {
        Cuboid area = plugin.edits().selections().get(player.getUniqueId(), player.getWorld().getName())
                .cuboid().orElse(null);
        if (area == null) {
            player.sendMessage(plugin.messages().get("error.no-selection"));
            return null;
        }
        return inWorld(player, area);
    }

    private Cuboid inWorld(Player player, Cuboid area) {
        World world = player.getWorld();
        Cuboid clamped = BatchPlanner.clampY(area, world.getMinHeight(), world.getMaxHeight() - 1).orElse(null);
        if (clamped == null) {
            player.sendMessage(plugin.messages().get("error.outside-world"));
            return null;
        }
        var limits = plugin.settings().limits();
        if (limits.exceeds(clamped.volume())) {
            player.sendMessage(plugin.messages().get("edit.error.too-large",
                    unparsed("blocks", String.valueOf(clamped.volume())),
                    unparsed("max", String.valueOf(limits.maxBlocksPerOperation()))));
            return null;
        }
        return clamped;
    }

    /** Reads the block data syntax, and has the server confirm each block exists. Null after telling the player. */
    private Resolved resolve(Player player, String text) {
        Messages messages = plugin.messages();
        BlockPattern pattern;
        try {
            pattern = BlockPattern.parse(text);
        } catch (PatternException e) {
            player.sendMessage(messages.get("error." + e.reason().messageKey()));
            return null;
        }
        BlockData[] blocks = new BlockData[pattern.entries().size()];
        for (int i = 0; i < blocks.length; i++) {
            String token = pattern.entries().get(i).block();
            try {
                blocks[i] = Bukkit.createBlockData(token);
            } catch (IllegalArgumentException e) {
                player.sendMessage(messages.get("error.unknown-block", unparsed("value", token)));
                return null;
            }
        }
        return new Resolved(pattern, blocks);
    }

    private static BlockPos anchor(Player player) {
        Location at = player.getLocation();
        return new BlockPos(at.getBlockX(), at.getBlockY(), at.getBlockZ());
    }

    private static boolean bypass(Player player) {
        return player.hasPermission(Protection.BYPASS);
    }

    private static boolean strict(String[] args) {
        return args.length > 0 && args[args.length - 1].equalsIgnoreCase("strict");
    }

    private static String[] withoutStrict(String[] args) {
        return strict(args) ? Arrays.copyOf(args, args.length - 1) : args;
    }

    List<String> complete(CommandSender sender, String[] args) {
        String last = args[args.length - 1].toLowerCase();
        List<String> options = switch (args[0].toLowerCase()) {
            case "set", "replace", "paste" -> List.of("strict");
            case "sel" -> args.length == 2 ? List.of("clear") : List.of();
            default -> List.of();
        };
        return args.length < 2 ? List.of() : options.stream().filter(o -> o.startsWith(last)).toList();
    }
}
