package xyz.joogiebear.surveyor.paper;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.LongAccumulator;
import java.util.function.Consumer;
import java.util.function.Predicate;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import xyz.joogiebear.surveyor.core.edit.Batch;
import xyz.joogiebear.surveyor.core.edit.BatchPlanner;
import xyz.joogiebear.surveyor.core.edit.BlockPattern;
import xyz.joogiebear.surveyor.core.edit.Clipboard;
import xyz.joogiebear.surveyor.core.edit.EditKind;
import xyz.joogiebear.surveyor.core.edit.JournalCodec;
import xyz.joogiebear.surveyor.core.edit.OperationIds;
import xyz.joogiebear.surveyor.core.edit.OperationState;
import xyz.joogiebear.surveyor.core.region.Cuboid;
import xyz.joogiebear.surveyor.paper.storage.EditStore;

/**
 * One edit, described as numbered units (a slab of one chunk, or one journal row) and what to do at
 * each position. {@link EditService} runs the units; the job only decides what block belongs where.
 */
abstract class EditJob {

    final String id = OperationIds.next();
    final EditKind kind;
    final Actor actor;
    final World world;
    final boolean strict;
    final boolean bypass;
    final String reason;
    final OperationState state = new OperationState();
    final LongAccumulator slowestSliceNanos = new LongAccumulator(Math::max, 0);
    final long startedNanos = System.nanoTime();
    /** Blocks that passed the checks at planning time, or -1 to record the size of the area. */
    long planned = -1;
    /** The chunk this job currently holds a plugin ticket on, or null. */
    volatile int[] heldChunk;

    EditJob(EditKind kind, Actor actor, World world, boolean strict, boolean bypass, String reason) {
        this.kind = kind;
        this.actor = actor;
        this.world = world;
        this.strict = strict;
        this.bypass = bypass;
        this.reason = reason;
    }

    /** Whether each change is checked against regions. Copying reads only, so it is not. */
    boolean guarded() {
        return true;
    }

    /** The whole area the job may change, or null when it comes from a journal. */
    Cuboid area() {
        return null;
    }

    /** Runs off the region threads before anything is applied. */
    CompletableFuture<Void> prepare(EditStore store) {
        return CompletableFuture.completedFuture(null);
    }

    abstract int units();

    abstract int chunkX(int unit);

    abstract int chunkZ(int unit);

    abstract int size(int unit);

    abstract void position(int unit, int index, int[] out);

    /** The block that should be at this position, or null to leave it. Runs on the owning region thread. */
    abstract BlockData visit(int unit, int index, int x, int y, int z, BlockData old);

    abstract String params();

    /** Called once the job finished successfully. */
    void done() {
    }

    static String json(String... keyValues) {
        StringBuilder out = new StringBuilder("{");
        for (int i = 0; i < keyValues.length; i += 2) {
            out.append(i == 0 ? "" : ",").append('"').append(keyValues[i]).append("\":\"")
                    .append(keyValues[i + 1].replace("\\", "\\\\").replace("\"", "\\\"")).append('"');
        }
        return out.append('}').toString();
    }

    static String describe(Cuboid area) {
        return area.minX() + " " + area.minY() + " " + area.minZ() + " " + area.maxX() + " " + area.maxY() + " " + area.maxZ();
    }

    private static BlockData pick(BlockPattern pattern, BlockData[] blocks) {
        return blocks.length == 1 ? blocks[0] : blocks[pattern.pickIndex(ThreadLocalRandom.current())];
    }

    abstract static class AreaJob extends EditJob {

        private final Cuboid area;
        private List<Batch> batches = List.of();

        AreaJob(EditKind kind, Actor actor, World world, boolean strict, boolean bypass, String reason, Cuboid area) {
            super(kind, actor, world, strict, bypass, reason);
            this.area = area;
        }

        @Override
        Cuboid area() {
            return area;
        }

        @Override
        CompletableFuture<Void> prepare(EditStore store) {
            batches = BatchPlanner.plan(area, EditService.BATCH_BLOCKS);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        int units() {
            return batches.size();
        }

        @Override
        int chunkX(int unit) {
            return batches.get(unit).chunkX();
        }

        @Override
        int chunkZ(int unit) {
            return batches.get(unit).chunkZ();
        }

        @Override
        int size(int unit) {
            return batches.get(unit).size();
        }

        @Override
        void position(int unit, int index, int[] out) {
            batches.get(unit).position(index, out);
        }
    }

    static final class Fill extends AreaJob {

        private final String text;
        private final BlockPattern pattern;
        private final BlockData[] blocks;

        Fill(Actor actor, World world, boolean strict, boolean bypass, String reason, Cuboid area, String text,
             BlockPattern pattern, BlockData[] blocks) {
            super(EditKind.SET, actor, world, strict, bypass, reason, area);
            this.text = text;
            this.pattern = pattern;
            this.blocks = blocks;
        }

        @Override
        BlockData visit(int unit, int index, int x, int y, int z, BlockData old) {
            return pick(pattern, blocks);
        }

        @Override
        String params() {
            return json("area", describe(area()), "pattern", text);
        }
    }

    static final class Replace extends AreaJob {

        private final String from;
        private final String to;
        private final List<Predicate<BlockData>> mask;
        private final BlockPattern pattern;
        private final BlockData[] blocks;

        Replace(Actor actor, World world, boolean strict, boolean bypass, String reason, Cuboid area, String from,
                String to, List<Predicate<BlockData>> mask, BlockPattern pattern, BlockData[] blocks) {
            super(EditKind.REPLACE, actor, world, strict, bypass, reason, area);
            this.from = from;
            this.to = to;
            this.mask = mask;
            this.pattern = pattern;
            this.blocks = blocks;
        }

        /** A plain block type matches in any state; a type with a bracketed state must match that state. */
        static Predicate<BlockData> matcher(String token, BlockData data) {
            if (token.indexOf('[') >= 0) {
                return old -> old.matches(data);
            }
            Material material = data.getMaterial();
            return old -> old.getMaterial() == material;
        }

        @Override
        BlockData visit(int unit, int index, int x, int y, int z, BlockData old) {
            for (Predicate<BlockData> matches : mask) {
                if (matches.test(old)) {
                    return pick(pattern, blocks);
                }
            }
            return null;
        }

        @Override
        String params() {
            return json("area", describe(area()), "from", from, "to", to);
        }
    }

    static final class Paste extends AreaJob {

        private final Clipboard clipboard;
        private final Cuboid origin;
        private final BlockData[] cache;

        /** @param origin where the whole clipboard lands; {@code area} is the part of it inside the world */
        Paste(Actor actor, World world, boolean strict, boolean bypass, String reason, Cuboid area,
              Clipboard clipboard, Cuboid origin) {
            super(EditKind.PASTE, actor, world, strict, bypass, reason, area);
            this.clipboard = clipboard;
            this.origin = origin;
            this.cache = new BlockData[clipboard.palette().size()];
        }

        @Override
        BlockData visit(int unit, int index, int x, int y, int z, BlockData old) {
            int at = clipboard.paletteIndexAt(x - origin.minX(), y - origin.minY(), z - origin.minZ());
            if (cache[at] == null) {
                cache[at] = Bukkit.createBlockData(clipboard.palette().get(at));
            }
            return cache[at];
        }

        @Override
        String params() {
            return json("area", describe(area()));
        }
    }

    static final class Copy extends AreaJob {

        private final Clipboard.Builder builder;
        private final Consumer<Clipboard> sink;

        Copy(Actor actor, World world, Cuboid area, Clipboard.Builder builder, Consumer<Clipboard> sink) {
            super(EditKind.COPY, actor, world, false, false, null, area);
            this.builder = builder;
            this.sink = sink;
        }

        @Override
        boolean guarded() {
            return false;
        }

        @Override
        BlockData visit(int unit, int index, int x, int y, int z, BlockData old) {
            builder.set(x - area().minX(), y - area().minY(), z - area().minZ(), old.getAsString());
            state.addChanged(1);
            return null;
        }

        @Override
        void done() {
            sink.accept(builder.build());
        }

        @Override
        String params() {
            return json("area", describe(area()));
        }
    }

    static final class Undo extends EditJob {

        private final String target;
        private List<EditStore.JournalRow> rows = List.of();
        private final Map<String, BlockData> blocks = new ConcurrentHashMap<>();

        Undo(Actor actor, World world, boolean bypass, String reason, String target) {
            super(EditKind.UNDO, actor, world, false, bypass, reason);
            this.target = target;
        }

        String target() {
            return target;
        }

        @Override
        CompletableFuture<Void> prepare(EditStore store) {
            return store.readJournal(target).thenAccept(loaded -> rows = loaded);
        }

        @Override
        int units() {
            return rows.size();
        }

        @Override
        int chunkX(int unit) {
            return rows.get(unit).chunkX();
        }

        @Override
        int chunkZ(int unit) {
            return rows.get(unit).chunkZ();
        }

        @Override
        int size(int unit) {
            return rows.get(unit).entries().size();
        }

        @Override
        void position(int unit, int index, int[] out) {
            JournalCodec.Entry entry = rows.get(unit).entries().get(index);
            out[0] = entry.x();
            out[1] = entry.y();
            out[2] = entry.z();
        }

        @Override
        BlockData visit(int unit, int index, int x, int y, int z, BlockData old) {
            return blocks.computeIfAbsent(rows.get(unit).entries().get(index).data(), Bukkit::createBlockData);
        }

        @Override
        String params() {
            return json("target", target);
        }
    }
}
