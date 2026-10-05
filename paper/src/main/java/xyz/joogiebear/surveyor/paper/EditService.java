package xyz.joogiebear.surveyor.paper;

import static net.kyori.adventure.text.minimessage.tag.resolver.Placeholder.unparsed;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import xyz.joogiebear.surveyor.core.edit.BlockPos;
import xyz.joogiebear.surveyor.core.agent.AgentScope;
import xyz.joogiebear.surveyor.core.edit.Clipboard;
import xyz.joogiebear.surveyor.core.edit.EditGuard;
import xyz.joogiebear.surveyor.core.edit.JournalCodec;
import xyz.joogiebear.surveyor.core.edit.OperationGate;
import xyz.joogiebear.surveyor.core.edit.OperationStatus;
import xyz.joogiebear.surveyor.core.edit.RegionGuard;
import xyz.joogiebear.surveyor.core.edit.ScopeGuard;
import xyz.joogiebear.surveyor.core.edit.Selection;
import xyz.joogiebear.surveyor.core.edit.Selections;
import xyz.joogiebear.surveyor.core.region.Cuboid;
import xyz.joogiebear.surveyor.core.region.Flag;
import xyz.joogiebear.surveyor.core.region.Region;
import xyz.joogiebear.surveyor.paper.storage.EditStore;

/**
 * Runs edit jobs. A job is planned off the region threads, then worked through one unit (a slab of
 * one chunk) at a time on the region scheduler that owns that chunk. Each unit is scanned, its
 * previous state is committed to the undo journal, and only then is it applied, in slices that stay
 * within `limits.tick-budget-ms` and continue on the next tick.
 */
final class EditService {

    static final int BATCH_BLOCKS = 4096;

    // Clock reads are cheap but not free; look at the time every few dozen blocks.
    private static final int CLOCK_INTERVAL = 64;

    private final SurveyorPlugin plugin;
    private final EditStore store;
    private final OperationGate gate = new OperationGate();
    private final Map<String, EditJob> active = new ConcurrentHashMap<>();
    private final Map<Object, Clipboard> clipboards = new ConcurrentHashMap<>();
    private final Selections<Object> selections = new Selections<>();
    private final Executor async;
    private volatile boolean stopping;
    private volatile List<String> interrupted = List.of();

    EditService(SurveyorPlugin plugin, EditStore store) {
        this.plugin = plugin;
        this.store = store;
        this.async = task -> plugin.getServer().getAsyncScheduler().runNow(plugin, scheduled -> task.run());
    }

    Selections<Object> selections() {
        return selections;
    }

    EditStore store() {
        return store;
    }

    Executor async() {
        return async;
    }

    /** Sets one corner of the player's selection in a world and tells them. */
    void select(Player player, String world, boolean first, BlockPos pos) {
        Selection selection = first ? selections.setPos1(player.getUniqueId(), world, pos)
                : selections.setPos2(player.getUniqueId(), world, pos);
        String size = selection.cuboid().map(area -> " (" + area.volume() + " blocks)").orElse("");
        plugin.reply(player, plugin.messages().get(first ? "selection.pos1" : "selection.pos2",
                unparsed("x", String.valueOf(pos.x())), unparsed("y", String.valueOf(pos.y())),
                unparsed("z", String.valueOf(pos.z())), unparsed("size", size)));
    }

    Clipboard clipboard(Object actorKey) {
        return clipboards.get(actorKey);
    }

    void setClipboard(Object actorKey, Clipboard clipboard) {
        clipboards.put(actorKey, clipboard);
    }

    void forget(Object actorKey) {
        selections.forget(actorKey);
        clipboards.remove(actorKey);
    }

    List<String> interrupted() {
        return interrupted;
    }

    /** Marks operations a crash left unfinished as failed. Their applied batches are undoable from the journal. */
    CompletableFuture<Void> recover() {
        return store.recoverInterrupted().thenAccept(ids -> {
            interrupted = List.copyOf(ids);
            if (!ids.isEmpty()) {
                plugin.getLogger().warning(ids.size() + " edit operation(s) were interrupted by a crash or restart and "
                        + "are now marked failed: " + String.join(", ", ids)
                        + ". Blocks they already changed can be restored with /sv undo <id>.");
            }
        });
    }

    /** @return the message key explaining why the job was refused, or null once it is running */
    String start(EditJob job) {
        if (stopping) {
            return "edit.error.stopping";
        }
        if (job.guarded() && !plugin.regions().loaded()) {
            return "error.not-loaded";
        }
        if (!gate.tryAcquire(plugin.settings().limits().maxConcurrentOperations())) {
            return "edit.error.busy";
        }
        active.put(job.id, job);
        store.insert(new EditStore.NewOperation(job.id, job.actor.type(), job.actor.id(), job.kind, job.world.getName(),
                        job.params(), job.reason, job.planned >= 0 ? job.planned : job.area() == null ? 0 : job.area().volume(),
                        regionNamesJson(regionsAffected(job.world.getName(), job.area()))))
                .thenCompose(ignored -> job.prepare(store))
                .thenAcceptAsync(ignored -> precheck(job), async)
                .thenCompose(ignored -> {
                    job.state.moveTo(OperationStatus.RUNNING);
                    return store.setStatus(job.id, OperationStatus.RUNNING, job.state);
                })
                .thenAccept(ignored -> proceed(job, 0))
                .exceptionally(error -> {
                    fail(job, error);
                    return null;
                });
        return null;
    }

    /** Asks a running job to stop before its next unit. Units already applied stay undoable. */
    boolean cancel(String id) {
        EditJob job = active.get(id);
        if (job == null) {
            return false;
        }
        job.state.requestCancel();
        return true;
    }

    EditJob find(String id) {
        return active.get(id);
    }

    /** The actor's newest unfinished job, or null. Ids sort in creation order. */
    EditJob latestActive(Actor actor) {
        return active.values().stream().filter(job -> sameActor(job.actor, actor))
                .max(Comparator.comparing(job -> job.id)).orElse(null);
    }

    /** Asks every unfinished job of an actor to stop, as when a token is revoked. @return how many were asked */
    int cancelAll(Actor actor) {
        int asked = 0;
        for (EditJob job : active.values()) {
            if (sameActor(job.actor, actor)) {
                job.state.requestCancel();
                asked++;
            }
        }
        return asked;
    }

    static boolean sameActor(Actor a, Actor b) {
        return a.type().equals(b.type()) && a.id().equals(b.id());
    }

    /** Called from onDisable. Region tasks stop with the plugin, so record what is still running as cancelled. */
    void shutdown() {
        stopping = true;
        List<CompletableFuture<Void>> writes = new ArrayList<>();
        for (EditJob job : active.values()) {
            job.state.requestCancel();
            if (job.state.moveTo(OperationStatus.CANCELLED)) {
                writes.add(store.setStatus(job.id, OperationStatus.CANCELLED, job.state));
            }
        }
        try {
            CompletableFuture.allOf(writes.toArray(new CompletableFuture[0])).get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            plugin.getLogger().warning("Could not record " + writes.size() + " running edit(s) as cancelled: " + e);
        }
    }

    private void precheck(EditJob job) {
        if (!job.guarded() || job.area() == null) {
            return;
        }
        long denied = guardFor(job.actor, job.world.getName(), false).countDenied(job.area());
        if (denied == 0) {
            return;
        }
        if (job.bypass) {
            audit(job, "bypass.edit", EditJob.describe(job.area()) + " (" + denied + " protected blocks)", "allowed");
        } else if (job.strict) {
            throw new StrictRefusal(denied);
        }
    }

    private void proceed(EditJob job, int unit) {
        if (unit >= job.units()) {
            finish(job, OperationStatus.DONE);
            return;
        }
        if (job.state.cancelRequested() || stopping) {
            finish(job, OperationStatus.CANCELLED);
            return;
        }
        int cx = job.chunkX(unit);
        int cz = job.chunkZ(unit);
        holdChunk(job, cx, cz);
        job.world.getChunkAtAsync(cx, cz, true).whenComplete((chunk, error) -> {
            if (error != null || chunk == null) {
                fail(job, error != null ? error : new IllegalStateException("chunk " + cx + "," + cz + " could not be loaded"));
                return;
            }
            new UnitRun(job, unit, cx, cz).start();
        });
    }

    /** Token scope first, then region rights. A bypassing actor is not limited by either. */
    EditGuard guardFor(Actor actor, String world, boolean bypass) {
        if (bypass) {
            return EditGuard.ALLOW_ALL;
        }
        EditGuard regions = new RegionGuard(plugin.regions().snapshot(), world, actor.regionIdentity(),
                plugin.settings().defaults().get(Flag.BUILD));
        AgentScope scope = actor.scope();
        return scope == null ? regions : new ScopeGuard(scope, world, regions);
    }

    /** Names of the regions whose bounds overlap the area. */
    List<String> regionsAffected(String world, Cuboid area) {
        if (area == null) {
            return List.of();
        }
        return plugin.regions().snapshot().list(world).stream()
                .filter(region -> region.bounds().intersects(area)).map(Region::name).toList();
    }

    private static String regionNamesJson(List<String> names) {
        return "[" + String.join(",", names.stream().map(name -> "\"" + name + "\"").toList()) + "]";
    }

    private EditGuard guard(EditJob job) {
        return guardFor(job.actor, job.world.getName(), job.bypass);
    }

    // A chunk that was only loaded, with no player near it, is not part of a ticking region, and the
    // region scheduler never runs a task for it. The ticket makes it one for as long as the unit needs.
    private void holdChunk(EditJob job, int chunkX, int chunkZ) {
        releaseChunk(job);
        job.world.addPluginChunkTicket(chunkX, chunkZ, plugin);
        job.heldChunk = new int[] {chunkX, chunkZ};
    }

    private void releaseChunk(EditJob job) {
        int[] held = job.heldChunk;
        job.heldChunk = null;
        if (held != null) {
            job.world.removePluginChunkTicket(held[0], held[1], plugin);
        }
    }

    private void fail(EditJob job, Throwable error) {
        Throwable cause = error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
        if (cause instanceof StrictRefusal refusal) {
            finish(job, OperationStatus.FAILED, "edit.strict-refused", unparsed("count", String.valueOf(refusal.denied)));
            return;
        }
        plugin.getLogger().severe("Edit " + job.id + " (" + job.kind.key() + ") failed: " + cause);
        finish(job, OperationStatus.FAILED, "edit.failed");
    }

    private void finish(EditJob job, OperationStatus status) {
        finish(job, status, switch (status) {
            case DONE -> "edit.done." + job.kind.key();
            case CANCELLED -> "edit.cancelled";
            default -> "edit.failed";
        });
    }

    private void finish(EditJob job, OperationStatus status, String messageKey,
                        TagResolver... extra) {
        if (!job.state.moveTo(status)) {
            return;
        }
        releaseChunk(job);
        store.setStatus(job.id, status, job.state).whenComplete((ignored, error) -> {
            if (error != null) {
                plugin.getLogger().severe("Could not record the end of edit " + job.id + ": " + error);
            }
            active.remove(job.id);
            gate.release();
            if (job.units() > 1) {
                plugin.getLogger().info("Edit " + job.id + " (" + job.kind.key() + ", " + status.key() + "): "
                        + job.state.changed() + " blocks in " + job.units() + " batches, "
                        + (System.nanoTime() - job.startedNanos) / 1_000_000 + " ms, slowest slice "
                        + job.slowestSliceNanos.get() / 1_000_000.0 + " ms (budget "
                        + plugin.settings().limits().tickBudgetMs() + " ms)");
            }
            if (status == OperationStatus.DONE) {
                job.done();
                if (job instanceof EditJob.Undo undo && job.state.skippedProtected() == 0) {
                    store.markUndone(undo.target(), job.id);
                }
            }
            audit(job, "edit." + job.kind.key(), job.id, status.key());
            var resolvers = new ArrayList<>(List.of(extra));
            resolvers.add(unparsed("id", job.id));
            resolvers.add(unparsed("changed", String.valueOf(job.state.changed())));
            resolvers.add(unparsed("skipped", String.valueOf(job.state.skippedProtected())));
            job.actor.tell(plugin, plugin.messages().get(messageKey,
                    resolvers.toArray(new TagResolver[0])));
            if (job.state.skippedProtected() > 0 && status == OperationStatus.DONE) {
                job.actor.tell(plugin, plugin.messages().get("edit.skipped-protected",
                        unparsed("skipped", String.valueOf(job.state.skippedProtected()))));
            }
        });
    }

    private void audit(EditJob job, String action, String target, String result) {
        store.audit(job.actor.type(), job.actor.id(), action, target, job.id, result, job.reason).exceptionally(error -> {
            plugin.getLogger().warning("Could not write audit row for " + job.id + ": " + error.getMessage());
            return null;
        });
    }

    private void onRegion(EditJob job, int chunkX, int chunkZ, long delayTicks, Runnable body) {
        if (stopping) {
            return;
        }
        Consumer<ScheduledTask> task = scheduled -> {
            long begin = System.nanoTime();
            try {
                body.run();
                job.slowestSliceNanos.accumulate(System.nanoTime() - begin);
            } catch (Throwable e) {
                fail(job, e);
            }
        };
        if (delayTicks <= 0) {
            plugin.getServer().getRegionScheduler().execute(plugin, job.world, chunkX, chunkZ, () -> task.accept(null));
        } else {
            plugin.getServer().getRegionScheduler().runDelayed(plugin, job.world, chunkX, chunkZ, task, delayTicks);
        }
    }

    private long budgetNanos() {
        return plugin.settings().limits().tickBudgetMs() * 1_000_000L;
    }

    private record Change(int x, int y, int z, BlockData next, String old) {
    }

    private static final class StrictRefusal extends RuntimeException {

        private final long denied;

        StrictRefusal(long denied) {
            super("strict edit refused, " + denied + " protected positions", null, false, false);
            this.denied = denied;
        }
    }

    /** One unit of a job: scan, journal, apply. Slices run on the region thread that owns the chunk. */
    private final class UnitRun {

        private final EditJob job;
        private final int unit;
        private final int chunkX;
        private final int chunkZ;
        private final int size;
        private final EditGuard guard;
        private final List<Change> pending = new ArrayList<>();
        private final int[] position = new int[3];
        private int cursor;
        private int applied;
        private int changed;
        private long skippedProtected;
        private long skippedOutOfScope;

        UnitRun(EditJob job, int unit, int chunkX, int chunkZ) {
            this.job = job;
            this.unit = unit;
            this.chunkX = chunkX;
            this.chunkZ = chunkZ;
            this.size = job.size(unit);
            this.guard = guard(job);
        }

        void start() {
            onRegion(job, chunkX, chunkZ, 0, this::scan);
        }

        private void scan() {
            if (!job.world.isChunkLoaded(chunkX, chunkZ)) {
                job.world.getChunkAtAsync(chunkX, chunkZ, true).whenComplete((chunk, error) -> {
                    if (error != null) {
                        fail(job, error);
                    } else {
                        onRegion(job, chunkX, chunkZ, 0, this::scan);
                    }
                });
                return;
            }
            boolean journaled = job.kind.journaled();
            long deadline = System.nanoTime() + budgetNanos();
            while (cursor < size) {
                int index = cursor++;
                job.position(unit, index, position);
                int x = position[0];
                int y = position[1];
                int z = position[2];
                BlockData old = job.world.getBlockAt(x, y, z).getBlockData();
                BlockData next = job.visit(unit, index, x, y, z, old);
                if (next != null && !next.equals(old)) {
                    EditGuard.Denial denial = job.guarded() ? guard.check(x, y, z) : EditGuard.Denial.NONE;
                    switch (denial) {
                        case NONE -> pending.add(new Change(x, y, z, next, journaled ? old.getAsString() : null));
                        case PROTECTED -> skippedProtected++;
                        case OUT_OF_SCOPE -> skippedOutOfScope++;
                    }
                }
                if (cursor % CLOCK_INTERVAL == 0 && System.nanoTime() >= deadline) {
                    break;
                }
            }
            if (cursor < size) {
                onRegion(job, chunkX, chunkZ, 1, this::scan);
                return;
            }
            scanned();
        }

        private void scanned() {
            job.state.addSkippedProtected(skippedProtected);
            job.state.addSkippedOutOfScope(skippedOutOfScope);
            if (pending.isEmpty()) {
                proceed(job, unit + 1);
                return;
            }
            if (!job.kind.journaled()) {
                onRegion(job, chunkX, chunkZ, 1, this::apply);
                return;
            }
            List<JournalCodec.Entry> entries = pending.stream()
                    .map(change -> new JournalCodec.Entry(change.x, change.y, change.z, change.old)).toList();
            // The row is stored before any block of this unit changes, so a crash cannot lose the way back.
            store.writeJournal(job.id, chunkX, chunkZ, unit, entries).whenComplete((ignored, error) -> {
                if (error != null) {
                    fail(job, error);
                } else {
                    onRegion(job, chunkX, chunkZ, 0, this::apply);
                }
            });
        }

        private void apply() {
            // Regions may have changed while the journal was being written, so check again.
            EditGuard fresh = guard(job);
            long deadline = System.nanoTime() + budgetNanos();
            while (applied < pending.size()) {
                Change change = pending.get(applied++);
                EditGuard.Denial denial = job.guarded() ? fresh.check(change.x, change.y, change.z) : EditGuard.Denial.NONE;
                if (denial == EditGuard.Denial.PROTECTED) {
                    job.state.addSkippedProtected(1);
                } else if (denial == EditGuard.Denial.OUT_OF_SCOPE) {
                    job.state.addSkippedOutOfScope(1);
                } else {
                    job.world.getBlockAt(change.x, change.y, change.z).setBlockData(change.next, false);
                    changed++;
                }
                if (applied % CLOCK_INTERVAL == 0 && System.nanoTime() >= deadline) {
                    break;
                }
            }
            if (applied < pending.size()) {
                onRegion(job, chunkX, chunkZ, 1, this::apply);
                return;
            }
            job.state.addChanged(changed);
            proceed(job, unit + 1);
        }
    }
}
