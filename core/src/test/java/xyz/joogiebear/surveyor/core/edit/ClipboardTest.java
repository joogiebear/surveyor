package xyz.joogiebear.surveyor.core.edit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import xyz.joogiebear.surveyor.core.region.Cuboid;

class ClipboardTest {

    private static Clipboard copy2x2x2(Cuboid area, BlockPos anchor) {
        Clipboard.Builder builder = Clipboard.builder(area, anchor);
        for (int y = 0; y < 2; y++) {
            for (int z = 0; z < 2; z++) {
                for (int x = 0; x < 2; x++) {
                    builder.set(x, y, z, "block_" + x + y + z);
                }
            }
        }
        return builder.build();
    }

    @Test
    void pasteKeepsTheOffsetFromTheAnchor() {
        // Copied cube sits 3 east, 1 up and 2 north of where the copier stood.
        Clipboard clipboard = copy2x2x2(new Cuboid(13, 71, 18, 14, 72, 19), new BlockPos(10, 70, 20));
        assertEquals(new Cuboid(103, 11, 8, 104, 12, 9), clipboard.areaAt(new BlockPos(100, 10, 10)));
        assertEquals(new Cuboid(13, 71, 18, 14, 72, 19), clipboard.areaAt(new BlockPos(10, 70, 20)));
    }

    @Test
    void blocksAreAddressedRelativeToTheMinimumCorner() {
        Clipboard clipboard = copy2x2x2(new Cuboid(0, 0, 0, 1, 1, 1), new BlockPos(0, 0, 0));
        assertEquals("block_101", clipboard.palette().get(clipboard.paletteIndexAt(1, 0, 1)));
        assertEquals("block_010", clipboard.palette().get(clipboard.paletteIndexAt(0, 1, 0)));
        assertEquals(8, clipboard.volume());
    }

    @Test
    void paletteSharesRepeatedBlocks() {
        Clipboard.Builder builder = Clipboard.builder(new Cuboid(0, 0, 0, 2, 0, 0), new BlockPos(0, 0, 0));
        builder.set(0, 0, 0, "stone");
        builder.set(1, 0, 0, "dirt");
        builder.set(2, 0, 0, "stone");
        assertEquals(2, builder.build().palette().size());
    }

    @Test
    void incompleteCopyCannotBeBuilt() {
        Clipboard.Builder builder = Clipboard.builder(new Cuboid(0, 0, 0, 1, 0, 0), new BlockPos(0, 0, 0));
        builder.set(0, 0, 0, "stone");
        assertThrows(IllegalStateException.class, builder::build);
    }
}
