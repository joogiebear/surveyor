package xyz.joogiebear.surveyor.paper;

import static xyz.joogiebear.surveyor.paper.AgentApi.done;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.block.data.BlockData;
import xyz.joogiebear.surveyor.core.agent.AgentScope;
import xyz.joogiebear.surveyor.core.agent.ErrorCode;
import xyz.joogiebear.surveyor.core.edit.BatchPlanner;
import xyz.joogiebear.surveyor.core.edit.BlockMask;
import xyz.joogiebear.surveyor.core.edit.BlockPattern;
import xyz.joogiebear.surveyor.core.edit.BlockPos;
import xyz.joogiebear.surveyor.core.edit.Clipboard;
import xyz.joogiebear.surveyor.core.edit.EditGuard;
import xyz.joogiebear.surveyor.core.edit.JournalCodec;
import xyz.joogiebear.surveyor.core.edit.OperationIds;
import xyz.joogiebear.surveyor.core.edit.OperationState;
import xyz.joogiebear.surveyor.core.edit.OperationStatus;
import xyz.joogiebear.surveyor.core.edit.PatternException;
import xyz.joogiebear.surveyor.core.edit.Selection;
import xyz.joogiebear.surveyor.core.region.Cuboid;
import xyz.joogiebear.surveyor.mcp.ToolResult;
import xyz.joogiebear.surveyor.paper.storage.EditStore;

/**
 * The selection, edit and operation tools. Planning (counting what the guards refuse) happens off
 * the region threads; the edit itself is an {@link EditJob} run by {@link EditService}, exactly as
 * for players.
 */
final class AgentEdits {

    private record Resolved(BlockPattern pattern, BlockData[] blocks) {
    }

    private final SurveyorPlugin plugin;
    private final AgentApi api;

    AgentEdits(SurveyorPlugin plugin, AgentApi api) {
        this.plugin = plugin;
        this.api = api;
    }

    CompletableFuture<ToolResult> run(AgentApi.Call call) {
        return switch (call.tool()) {
            case "selection.get" -> done(selectionGet(call));
            case "selection.set" -> done(selectionSet(call));
            case "edit.set" -> set(call);
            case "edit.replace" -> replace(call);
            case "edit.copy" -> copy(call);
            case "edit.paste" -> paste(call);
            case "edit.undo" -> undo(call);
            case "operation.status" -> status(call);
            case "operation.cancel" -> cancel(call);
            default -> throw new IllegalStateException("tool " + call.tool() + " is not an edit tool");
        };
    }

    // ---- selections

    private ToolResult selectionGet(AgentApi.Call call) {
        World world = world(call);
        return ToolResult.ok(selectionFields(world, plugin.edits().selections().get(call.actor().key(), world.getName())));
    }

    private ToolResult selectionSet(AgentApi.Call call) {
        World world = world(call);
        Args args = call.args();
        Object key = call.actor().key();
        if (args.bool("clear")) {
            plugin.edits().selections().clear(key, world.getName());
        } else if (!args.has("pos1") && !args.has("pos2")) {
            throw new ToolException(ErrorCode.INVALID_ARGUMENT, "Give pos1, pos2 or clear: true.");
        }
        int[] a = args.optPosition("pos1");
        int[] b = args.optPosition("pos2");
        if (a != null) {
            plugin.edits().selections().setPos1(key, world.getName(), new BlockPos(a[0], a[1], a[2]));
        }
        if (b != null) {
            plugin.edits().selections().setPos2(key, world.getName(), new BlockPos(b[0], b[1], b[2]));
        }
        return ToolResult.ok(selectionFields(world, plugin.edits().selections().get(key, world.getName())));
    }

    private static JsonObject selectionFields(World world, Selection selection) {
        JsonObject out = new JsonObject();
        out.addProperty("world", world.getName());
        if (selection.pos1() != null) {
            out.add("pos1", Args.position(selection.pos1().x(), selection.pos1().y(), selection.pos1().z()));
        }
        if (selection.pos2() != null) {
            out.add("pos2", Args.position(selection.pos2().x(), selection.pos2().y(), selection.pos2().z()));
        }
        selection.cuboid().ifPresent(area -> out.addProperty("blocks", area.volume()));
        return out;
    }

    // ---- edits

    private CompletableFuture<ToolResult> set(AgentApi.Call call) {
        World world = world(call);
        Cuboid area = area(call, world, selectedArea(call, world));
        String text = call.args().string("pattern");
        Resolved to = resolve(text);
        return plan(call, world, area, List.of(), () -> new EditJob.Fill(call.actor(), world, call.args().bool("strict"),
                false, call.reason(), area, text, to.pattern(), to.blocks()));
    }

    private CompletableFuture<ToolResult> replace(AgentApi.Call call) {
        World world = world(call);
        Cuboid area = area(call, world, selectedArea(call, world));
        String from = call.args().string("from");
        String to = call.args().string("to");
        List<Predicate<BlockData>> mask = new ArrayList<>();
        try {
            for (String token : BlockMask.parse(from).blocks()) {
                mask.add(EditJob.Replace.matcher(token, Bukkit.createBlockData(token)));
            }
        } catch (PatternException e) {
            throw new ToolException(ErrorCode.INVALID_ARGUMENT, patternProblem(e));
        } catch (IllegalArgumentException e) {
            throw new ToolException(ErrorCode.INVALID_ARGUMENT, "Unknown block in 'from': " + from
                    + ". Use block ids such as stone or oak_stairs[facing=north].");
        }
        Resolved target = resolve(to);
        return plan(call, world, area, List.of("The skipped counts of a dry run cover every position in the area, including blocks "
                        + "replace would leave alone; the final counts only include blocks that would have changed."),
                () -> new EditJob.Replace(call.actor(), world, call.args().bool("strict"), false, call.reason(), area, from, to,
                        mask, target.pattern(), target.blocks()));
    }

    private CompletableFuture<ToolResult> paste(AgentApi.Call call) {
        World world = world(call);
        Clipboard clipboard = plugin.edits().clipboard(call.actor().key());
        if (clipboard == null) {
            throw new ToolException(ErrorCode.INVALID_ARGUMENT, "This token's clipboard is empty. Call edit.copy first.");
        }
        int[] at = call.args().position("at");
        Cuboid origin = clipboard.areaAt(new BlockPos(at[0], at[1], at[2]));
        Cuboid area = area(call, world, origin);
        return plan(call, world, area, List.of(), () -> new EditJob.Paste(call.actor(), world, call.args().bool("strict"),
                false, call.reason(), area, clipboard, origin));
    }

    private CompletableFuture<ToolResult> copy(AgentApi.Call call) {
        World world = world(call);
        Cuboid area = area(call, world, selectedArea(call, world));
        if (!call.scope().covers(world.getName(), area)) {
            throw new ToolException(ErrorCode.OUT_OF_SCOPE, "Part of that area is outside this token's scope"
                    + boxText(call.scope()) + ". Copy only reads blocks inside the scope.");
        }
        int[] anchor = call.args().optPosition("anchor");
        Clipboard.Builder builder = Clipboard.builder(area, anchor == null
                ? new BlockPos(area.minX(), area.minY(), area.minZ()) : new BlockPos(anchor[0], anchor[1], anchor[2]));
        EditJob job = new EditJob.Copy(call.actor(), world, area, builder,
                clipboard -> plugin.edits().setClipboard(call.actor().key(), clipboard));
        job.planned = area.volume();
        String refused = plugin.edits().start(job);
        if (refused != null) {
            throw refusal(refused);
        }
        return done(ToolResult.ok(fields(job.id, job.state.status(), area.volume(), 0, 0, 0, List.of(), List.of())));
    }

    /**
     * Counts what the token's scope and the regions would refuse, then either reports that (dry run),
     * refuses (strict with anything denied) or starts the edit and returns its operation id at once.
     */
    private CompletableFuture<ToolResult> plan(AgentApi.Call call, World world, Cuboid area,
                                                                    List<String> notes, Supplier<EditJob> job) {
        EditService service = plugin.edits();
        return CompletableFuture.supplyAsync(
                () -> service.guardFor(call.actor(), world.getName(), false).countDenials(area), service.async())
                .thenApply(counts -> {
                    List<String> regions = service.regionsAffected(world.getName(), area);
                    long planned = area.volume() - counts.total();
                    List<String> warnings = new ArrayList<>(notes);
                    if (call.args().bool("strict") && counts.total() > 0) {
                        JsonObject fields = fields(null, null, planned, 0, counts.protectedBlocks(), counts.outOfScope(), regions, warnings);
                        boolean scope = counts.outOfScope() > 0;
                        throw new ToolException(scope ? ErrorCode.OUT_OF_SCOPE : ErrorCode.REGION_DENIED,
                                counts.protectedBlocks() + " positions in the area are protected by regions and " + counts.outOfScope()
                                        + " are outside this token's scope, so strict refused the edit and nothing was changed. "
                                        + "Narrow the area, or leave strict off to skip those positions.", fields);
                    }
                    if (call.dryRun()) {
                        warnings.add(0, "Dry run: nothing was changed.");
                        return ToolResult.ok(fields(null, OperationStatus.PLANNED, planned, 0, counts.protectedBlocks(),
                                counts.outOfScope(), regions, warnings));
                    }
                    EditJob started = job.get();
                    started.planned = planned;
                    String refused = service.start(started);
                    if (refused != null) {
                        throw refusal(refused);
                    }
                    warnings.add("The edit runs over several ticks. Poll operation.status for the final counts.");
                    return ToolResult.ok(fields(started.id, started.state.status(), planned, 0, counts.protectedBlocks(),
                            counts.outOfScope(), regions, warnings));
                });
    }

    private CompletableFuture<ToolResult> undo(AgentApi.Call call) {
        String id = call.args().string("operationId");
        if (!OperationIds.isValid(id)) {
            throw new ToolException(ErrorCode.INVALID_ARGUMENT, "'" + id + "' is not an operation id. Ids look like op_0abc123xyz4567.");
        }
        EditService service = plugin.edits();
        return service.store().find(id).thenCompose(operation -> {
            EditStore.Operation own = own(call, operation);
            if (!own.kind().journaled()) {
                throw new ToolException(ErrorCode.INVALID_ARGUMENT, "Operation " + id + " (" + own.kind().key() + ") changed no blocks, so there is nothing to undo.");
            }
            if (own.undoneBy() != null) {
                throw new ToolException(ErrorCode.CONFLICT, "Operation " + id + " was already undone by " + own.undoneBy() + ".");
            }
            if (own.blocksChanged() > 0 && !own.hasJournal()) {
                throw new ToolException(ErrorCode.CONFLICT, "The undo journal of operation " + id
                        + " was deleted after undo.retention-days, so it can no longer be undone.");
            }
            if (own.status() == OperationStatus.PLANNED || own.status() == OperationStatus.RUNNING) {
                throw new ToolException(ErrorCode.CONFLICT, "Operation " + id + " is still " + own.status().key()
                        + ". Wait for it to finish, or cancel it with operation.cancel first.");
            }
            World world = Bukkit.getWorld(own.world());
            if (world == null) {
                throw new ToolException(ErrorCode.NOT_FOUND, "The world of operation " + id + " is not loaded.");
            }
            if (!call.scope().allowsWorld(own.world())) {
                throw new ToolException(ErrorCode.OUT_OF_SCOPE, "This token may not use world '" + own.world() + "'.");
            }
            return service.store().readJournal(id).thenApplyAsync(rows -> {
                EditGuard guard = service.guardFor(call.actor(), own.world(), false);
                long entries = 0;
                long protectedBlocks = 0;
                long outOfScope = 0;
                for (EditStore.JournalRow row : rows) {
                    for (JournalCodec.Entry entry : row.entries()) {
                        entries++;
                        switch (guard.check(entry.x(), entry.y(), entry.z())) {
                            case PROTECTED -> protectedBlocks++;
                            case OUT_OF_SCOPE -> outOfScope++;
                            case NONE -> {
                            }
                        }
                    }
                }
                long planned = entries - protectedBlocks - outOfScope;
                if (call.dryRun()) {
                    return ToolResult.ok(fields(null, OperationStatus.PLANNED, planned, 0, protectedBlocks, outOfScope, List.of(),
                            List.of("Dry run: nothing was changed.")));
                }
                EditJob.Undo job = new EditJob.Undo(call.actor(), world, false, call.reason(), id);
                job.planned = planned;
                String refused = service.start(job);
                if (refused != null) {
                    throw refusal(refused);
                }
                JsonObject out = fields(job.id, job.state.status(), planned, 0, protectedBlocks, outOfScope, List.of(),
                        List.of("The undo runs over several ticks. Poll operation.status for the final counts."));
                out.addProperty("undoes", id);
                return ToolResult.ok(out);
            }, service.async());
        });
    }

    // ---- operations

    private CompletableFuture<ToolResult> status(AgentApi.Call call) {
        String id = operationId(call);
        return plugin.edits().store().find(id).thenApply(operation -> {
            EditStore.Operation own = own(call, operation);
            EditJob live = plugin.edits().find(id);
            OperationStatus status = own.status();
            long changed = own.blocksChanged();
            long skippedProtected = own.skippedProtected();
            long skippedOutOfScope = own.skippedOutOfScope();
            if (live != null) {
                OperationState state = live.state;
                status = state.status();
                changed = state.changed();
                skippedProtected = state.skippedProtected();
                skippedOutOfScope = state.skippedOutOfScope();
            }
            JsonObject out = fields(own.id(), status, own.blocksPlanned(), changed, skippedProtected, skippedOutOfScope,
                    names(own.regionsAffected()), List.of());
            out.addProperty("kind", own.kind().key());
            out.addProperty("createdAt", own.createdAt());
            if (own.finishedAt() != null) {
                out.addProperty("finishedAt", own.finishedAt());
            }
            if (own.reason() != null) {
                out.addProperty("reason", own.reason());
            }
            if (own.undoneBy() != null) {
                out.addProperty("undoneBy", own.undoneBy());
            }
            return ToolResult.ok(out);
        });
    }

    private CompletableFuture<ToolResult> cancel(AgentApi.Call call) {
        String id = operationId(call);
        return plugin.edits().store().find(id).thenApply(operation -> {
            EditStore.Operation own = own(call, operation);
            EditJob live = plugin.edits().find(id);
            if (live == null) {
                throw new ToolException(ErrorCode.CONFLICT, "Operation " + id + " is not running; its status is "
                        + own.status().key() + ".");
            }
            plugin.edits().cancel(id);
            return ToolResult.ok(fields(id, live.state.status(), own.blocksPlanned(), live.state.changed(),
                    live.state.skippedProtected(), live.state.skippedOutOfScope(), names(own.regionsAffected()),
                    List.of("Cancel requested: the edit stops before its next batch. Blocks already changed stay and can be undone with edit.undo.")));
        });
    }

    private static String operationId(AgentApi.Call call) {
        String id = call.args().string("operationId");
        if (!OperationIds.isValid(id)) {
            throw new ToolException(ErrorCode.INVALID_ARGUMENT, "'" + id + "' is not an operation id. Ids look like op_0abc123xyz4567.");
        }
        return id;
    }

    /** A token sees only its own operations; anything else is reported as not found. */
    private static EditStore.Operation own(AgentApi.Call call, EditStore.Operation operation) {
        if (operation == null || !operation.actorType().equals("agent") || !operation.actorId().equals(call.actor().id())) {
            throw new ToolException(ErrorCode.NOT_FOUND, "This token has no operation with that id.");
        }
        return operation;
    }

    private static List<String> names(String json) {
        if (json == null || json.length() <= 2) {
            return List.of();
        }
        List<String> names = new ArrayList<>();
        for (String part : json.substring(1, json.length() - 1).split(",")) {
            names.add(part.replace("\"", ""));
        }
        return names;
    }

    // ---- shared

    private static JsonObject fields(String operationId, OperationStatus status, long planned, long changed,
                                     long skippedProtected, long skippedOutOfScope, List<String> regions, List<String> warnings) {
        JsonObject out = new JsonObject();
        if (operationId != null) {
            out.addProperty("operationId", operationId);
        }
        if (status != null) {
            out.addProperty("status", status.key());
        }
        out.addProperty("blocksPlanned", planned);
        out.addProperty("blocksChanged", changed);
        out.add("skipped", Args.skipped(skippedProtected, skippedOutOfScope));
        out.add("regionsAffected", Args.strings(regions));
        JsonArray notes = Args.strings(warnings);
        out.add("warnings", notes);
        return out;
    }

    private World world(AgentApi.Call call) {
        String name = call.args().string("world");
        if (!call.scope().allowsWorld(name)) {
            throw new ToolException(ErrorCode.OUT_OF_SCOPE, "This token may not use world '" + name + "'. Allowed worlds: "
                    + (call.scope().worlds().isEmpty() ? "none" : String.join(", ", call.scope().worlds().stream().sorted().toList())) + ".");
        }
        World world = Bukkit.getWorld(name);
        if (world == null) {
            throw new ToolException(ErrorCode.NOT_FOUND, "There is no loaded world '" + name + "'. Call world.list.");
        }
        return world;
    }

    /** pos1 and pos2 as given, or this token's selection when both are left out. */
    private Cuboid selectedArea(AgentApi.Call call, World world) {
        int[] a = call.args().optPosition("pos1");
        int[] b = call.args().optPosition("pos2");
        if (a != null && b != null) {
            return Cuboid.of(a[0], a[1], a[2], b[0], b[1], b[2]);
        }
        if (a != null || b != null) {
            throw new ToolException(ErrorCode.INVALID_ARGUMENT, "Give both pos1 and pos2, or neither to use this token's selection.");
        }
        return plugin.edits().selections().get(call.actor().key(), world.getName()).cuboid().orElseThrow(() ->
                new ToolException(ErrorCode.INVALID_ARGUMENT,
                        "No area: give pos1 and pos2, or set a selection first with selection.set."));
    }

    /** Cuts the area to the world, then applies the size cap and the token's scope, in that order. */
    private Cuboid area(AgentApi.Call call, World world, Cuboid raw) {
        Cuboid area = BatchPlanner.clampY(raw, world.getMinHeight(), world.getMaxHeight() - 1).orElseThrow(() ->
                new ToolException(ErrorCode.INVALID_ARGUMENT, "That area is outside the world's height, "
                        + world.getMinHeight() + " to " + (world.getMaxHeight() - 1) + "."));
        int cap = api.effectiveMaxBlocks(call.scope());
        if (area.volume() > cap) {
            throw new ToolException(ErrorCode.LIMIT_EXCEEDED, "That area is "
                    + (area.volume() == Long.MAX_VALUE ? "far more than " + cap : String.valueOf(area.volume()))
                    + " blocks; this token may change at most " + cap + " per operation. Split it into smaller areas.");
        }
        WorldBorder border = world.getWorldBorder();
        Location center = border.getCenter();
        double half = border.getSize() / 2;
        if (area.minX() < center.getX() - half || area.maxX() + 1 > center.getX() + half
                || area.minZ() < center.getZ() - half || area.maxZ() + 1 > center.getZ() + half) {
            throw new ToolException(ErrorCode.INVALID_ARGUMENT, "That area reaches outside the world border of '" + world.getName()
                    + "' (centre " + center.getBlockX() + ", " + center.getBlockZ() + ", size " + (long) border.getSize() + ").");
        }
        if (!call.scope().touches(world.getName(), area)) {
            throw new ToolException(ErrorCode.OUT_OF_SCOPE, "None of that area is inside this token's scope" + boxText(call.scope()) + ".");
        }
        return area;
    }

    private static String boxText(AgentScope scope) {
        if (scope.box() == null) {
            return "";
        }
        Cuboid b = scope.box().area();
        return " (box in " + scope.box().world() + ": " + b.minX() + " " + b.minY() + " " + b.minZ() + " to "
                + b.maxX() + " " + b.maxY() + " " + b.maxZ() + ")";
    }

    private static Resolved resolve(String text) {
        BlockPattern pattern;
        try {
            pattern = BlockPattern.parse(text);
        } catch (PatternException e) {
            throw new ToolException(ErrorCode.INVALID_ARGUMENT, patternProblem(e));
        }
        BlockData[] blocks = new BlockData[pattern.entries().size()];
        for (int i = 0; i < blocks.length; i++) {
            String token = pattern.entries().get(i).block();
            try {
                blocks[i] = Bukkit.createBlockData(token);
            } catch (IllegalArgumentException e) {
                throw new ToolException(ErrorCode.INVALID_ARGUMENT, "Unknown block '" + token
                        + "'. Use block ids such as stone or oak_stairs[facing=north].");
            }
        }
        return new Resolved(pattern, blocks);
    }

    private static String patternProblem(PatternException e) {
        return switch (e.reason()) {
            case EMPTY -> "The pattern is empty or has an empty entry. Example: 50%stone,50%andesite";
            case BAD_WEIGHT -> "A weight must be a whole number above 0, as in 50%stone.";
            case MIXED_WEIGHTS -> "Give a weight to every entry or to none.";
            case UNBALANCED -> "Square brackets in the pattern do not match.";
            case WEIGHT_NOT_ALLOWED -> "The block list in 'from' cannot have weights.";
        };
    }

    private static ToolException refusal(String messageKey) {
        return switch (messageKey) {
            case "edit.error.busy" -> new ToolException(ErrorCode.LIMIT_EXCEEDED,
                    "Too many edits are running on the server. Wait for one to finish and try again.");
            case "edit.error.stopping" -> new ToolException(ErrorCode.INTERNAL, "The server is shutting down.");
            case "error.not-loaded" -> new ToolException(ErrorCode.INTERNAL,
                    "Regions have not loaded yet, so edits are refused. Try again in a moment.");
            default -> new ToolException(ErrorCode.INTERNAL, "The edit could not be started.");
        };
    }
}
