package xyz.joogiebear.surveyor.core.edit;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import xyz.joogiebear.surveyor.core.region.Cuboid;

public final class BatchPlanner {

    /** A full 16x16 column, the smallest slab that cannot be split further. */
    public static final int MIN_BATCH_BLOCKS = 256;

    private BatchPlanner() {
    }

    /** Cuts the area to the world's build height, or empty when nothing of it is inside. */
    public static Optional<Cuboid> clampY(Cuboid area, int minY, int maxY) {
        int low = Math.max(area.minY(), minY);
        int high = Math.min(area.maxY(), maxY);
        if (low > high) {
            return Optional.empty();
        }
        return Optional.of(new Cuboid(area.minX(), low, area.minZ(), area.maxX(), high, area.maxZ()));
    }

    /**
     * Splits the area into per-chunk slabs of at most {@code maxBlocks} blocks, ordered by chunk
     * then height. The slabs do not overlap and together cover the area exactly.
     */
    public static List<Batch> plan(Cuboid area, int maxBlocks) {
        if (maxBlocks < MIN_BATCH_BLOCKS) {
            throw new IllegalArgumentException("a batch must hold at least " + MIN_BATCH_BLOCKS + " blocks");
        }
        List<Batch> batches = new ArrayList<>();
        int seq = 0;
        for (int cx = area.minX() >> 4; cx <= area.maxX() >> 4; cx++) {
            for (int cz = area.minZ() >> 4; cz <= area.maxZ() >> 4; cz++) {
                int x0 = Math.max(area.minX(), cx << 4);
                int x1 = Math.min(area.maxX(), (cx << 4) + 15);
                int z0 = Math.max(area.minZ(), cz << 4);
                int z1 = Math.min(area.maxZ(), (cz << 4) + 15);
                int layers = maxBlocks / ((x1 - x0 + 1) * (z1 - z0 + 1));
                for (long y = area.minY(); y <= area.maxY(); y += layers) {
                    int top = (int) Math.min(area.maxY(), y + layers - 1);
                    batches.add(new Batch(cx, cz, seq++, new Cuboid(x0, (int) y, z0, x1, top, z1)));
                }
            }
        }
        return batches;
    }
}
