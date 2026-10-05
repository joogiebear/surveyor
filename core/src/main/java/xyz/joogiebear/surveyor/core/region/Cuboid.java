package xyz.joogiebear.surveyor.core.region;

public record Cuboid(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {

    public Cuboid {
        if (minX > maxX || minY > maxY || minZ > maxZ) {
            throw new IllegalArgumentException("cuboid minimum must not exceed its maximum");
        }
    }

    /** Builds a cuboid from any two opposite corners, in any order. */
    public static Cuboid of(int x1, int y1, int z1, int x2, int y2, int z2) {
        return new Cuboid(Math.min(x1, x2), Math.min(y1, y2), Math.min(z1, z2),
                Math.max(x1, x2), Math.max(y1, y2), Math.max(z1, z2));
    }

    /** The number of blocks, or {@link Long#MAX_VALUE} when it would not fit, so a size limit still refuses it. */
    public long volume() {
        return saturatedProduct(saturatedProduct((long) maxX - minX + 1, (long) maxY - minY + 1), (long) maxZ - minZ + 1);
    }

    private static long saturatedProduct(long a, long b) {
        return Math.multiplyHigh(a, b) != 0 || a * b < 0 ? Long.MAX_VALUE : a * b;
    }

    public boolean contains(int x, int y, int z) {
        return x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ;
    }

    public boolean contains(Cuboid other) {
        return other.minX >= minX && other.maxX <= maxX && other.minY >= minY && other.maxY <= maxY
                && other.minZ >= minZ && other.maxZ <= maxZ;
    }

    public boolean intersects(Cuboid other) {
        return other.minX <= maxX && other.maxX >= minX && other.minY <= maxY && other.maxY >= minY
                && other.minZ <= maxZ && other.maxZ >= minZ;
    }
}
