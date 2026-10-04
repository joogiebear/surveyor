package xyz.joogiebear.surveyor.core.edit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import xyz.joogiebear.surveyor.core.region.Cuboid;

class BatchPlannerTest {

    /** Every position the batches visit, asserting none is visited twice and all stay in their chunk. */
    private static Set<BlockPos> visit(List<Batch> batches, int maxBlocks) {
        Set<BlockPos> seen = new HashSet<>();
        int[] p = new int[3];
        for (Batch batch : batches) {
            assertTrue(batch.size() <= maxBlocks);
            for (int i = 0; i < batch.size(); i++) {
                batch.position(i, p);
                assertEquals(batch.chunkX(), p[0] >> 4);
                assertEquals(batch.chunkZ(), p[2] >> 4);
                assertTrue(batch.area().contains(p[0], p[1], p[2]));
                assertTrue(seen.add(new BlockPos(p[0], p[1], p[2])), "visited twice");
            }
        }
        return seen;
    }

    @Test
    void coversAnAreaSpanningChunksExactlyOnce() {
        Cuboid area = new Cuboid(-20, -5, -3, 17, 40, 30);
        List<Batch> batches = BatchPlanner.plan(area, 1000);
        assertEquals(area.volume(), visit(batches, 1000).size());
    }

    @Test
    void singleBlock() {
        List<Batch> batches = BatchPlanner.plan(new Cuboid(5, 5, 5, 5, 5, 5), 256);
        assertEquals(1, batches.size());
        assertEquals(1, batches.get(0).size());
    }

    @Test
    void tallColumnIsSlicedByHeight() {
        Cuboid area = new Cuboid(0, 0, 0, 15, 99, 15);
        List<Batch> batches = BatchPlanner.plan(area, 1024);
        assertEquals(25, batches.size());
        assertEquals(area.volume(), visit(batches, 1024).size());
    }

    @Test
    void batchesAreNumberedInOrderAndGroupedByChunk() {
        List<Batch> batches = BatchPlanner.plan(new Cuboid(0, 0, 0, 31, 63, 15), 4096);
        for (int i = 0; i < batches.size(); i++) {
            assertEquals(i, batches.get(i).seq());
        }
        assertEquals(0, batches.get(0).chunkX());
        assertEquals(1, batches.get(batches.size() - 1).chunkX());
    }

    @Test
    void rejectsBatchesSmallerThanAColumn() {
        assertThrows(IllegalArgumentException.class, () -> BatchPlanner.plan(new Cuboid(0, 0, 0, 1, 1, 1), 255));
    }

    @Test
    void aMaxSizeEditSpansManyBatches() {
        // About 250,000 blocks at 4096 per batch: the shape that the executor must spread over many ticks.
        Cuboid area = new Cuboid(0, 0, 0, 62, 62, 62);
        List<Batch> batches = BatchPlanner.plan(area, 4096);
        assertEquals(250_047L, area.volume());
        assertTrue(batches.size() > 50);
        assertEquals(250_047L, batches.stream().mapToLong(Batch::size).sum());
    }

    @Test
    void clampY() {
        Cuboid area = new Cuboid(0, -100, 0, 3, 500, 3);
        assertEquals(new Cuboid(0, -64, 0, 3, 319, 3), BatchPlanner.clampY(area, -64, 319).orElseThrow());
        assertEquals(Optional.empty(), BatchPlanner.clampY(new Cuboid(0, 400, 0, 1, 450, 1), -64, 319));
        assertEquals(area, BatchPlanner.clampY(area, -1000, 1000).orElseThrow());
    }
}
