package xyz.joogiebear.surveyor.core.edit;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import xyz.joogiebear.surveyor.core.region.Cuboid;

/**
 * A copied box of block data. The offset says where the box starts relative to the block the
 * copier stood on, so a paste puts that same relationship back at the paster's own block.
 */
public final class Clipboard {

    private final int sizeX;
    private final int sizeY;
    private final int sizeZ;
    private final int offsetX;
    private final int offsetY;
    private final int offsetZ;
    private final List<String> palette;
    private final int[] indices;

    private Clipboard(Builder b) {
        sizeX = b.sizeX;
        sizeY = b.sizeY;
        sizeZ = b.sizeZ;
        offsetX = b.offsetX;
        offsetY = b.offsetY;
        offsetZ = b.offsetZ;
        palette = List.copyOf(b.palette);
        indices = b.indices;
    }

    public static Builder builder(Cuboid copied, BlockPos anchor) {
        return new Builder(copied, anchor);
    }

    public int volume() {
        return indices.length;
    }

    public List<String> palette() {
        return palette;
    }

    /** Where this clipboard lands when pasted with its anchor at {@code anchor}. */
    public Cuboid areaAt(BlockPos anchor) {
        int x = anchor.x() + offsetX;
        int y = anchor.y() + offsetY;
        int z = anchor.z() + offsetZ;
        return new Cuboid(x, y, z, x + sizeX - 1, y + sizeY - 1, z + sizeZ - 1);
    }

    /** Palette index of the block at a position given relative to the pasted area's minimum corner. */
    public int paletteIndexAt(int dx, int dy, int dz) {
        return indices[(dy * sizeZ + dz) * sizeX + dx];
    }

    public static final class Builder {

        private final int sizeX;
        private final int sizeY;
        private final int sizeZ;
        private final int offsetX;
        private final int offsetY;
        private final int offsetZ;
        private final Map<String, Integer> lookup = new HashMap<>();
        private final List<String> palette = new ArrayList<>();
        private final int[] indices;
        private int filled;

        private Builder(Cuboid copied, BlockPos anchor) {
            sizeX = copied.maxX() - copied.minX() + 1;
            sizeY = copied.maxY() - copied.minY() + 1;
            sizeZ = copied.maxZ() - copied.minZ() + 1;
            offsetX = copied.minX() - anchor.x();
            offsetY = copied.minY() - anchor.y();
            offsetZ = copied.minZ() - anchor.z();
            indices = new int[Math.toIntExact(copied.volume())];
            Arrays.fill(indices, -1);
        }

        /** Not thread safe; callers hand the builder from one region thread to the next in order. */
        public void set(int dx, int dy, int dz, String data) {
            int at = (dy * sizeZ + dz) * sizeX + dx;
            if (indices[at] == -1) {
                filled++;
            }
            indices[at] = lookup.computeIfAbsent(data, d -> {
                palette.add(d);
                return palette.size() - 1;
            });
        }

        public Clipboard build() {
            if (filled != indices.length) {
                throw new IllegalStateException("clipboard is missing " + (indices.length - filled) + " blocks");
            }
            return new Clipboard(this);
        }
    }
}
