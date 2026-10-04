package xyz.joogiebear.surveyor.core.edit;

import xyz.joogiebear.surveyor.core.region.Cuboid;

/** A slab of one chunk column, small enough to be journaled and applied as one unit. */
public record Batch(int chunkX, int chunkZ, int seq, Cuboid area) {

    public int size() {
        return (int) area.volume();
    }

    /** Writes the i-th position (layer by layer, then row by row) into {@code out} as x, y, z. */
    public void position(int i, int[] out) {
        int width = area.maxX() - area.minX() + 1;
        int depth = area.maxZ() - area.minZ() + 1;
        int layer = width * depth;
        int inLayer = i % layer;
        out[0] = area.minX() + inLayer % width;
        out[1] = area.minY() + i / layer;
        out[2] = area.minZ() + inLayer / width;
    }
}
