package xyz.joogiebear.surveyor.core.region;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Immutable lookup of the regions of one world. Regions are bucketed by 16x16 column so a lookup
 * is a hash probe plus the few regions in that column. Regions spanning too many columns go in a
 * short list that is scanned every time, which keeps the grid small.
 */
final class RegionIndex {

    private static final int MAX_CELLS = 1024;

    private final Map<Long, List<Region>> cells = new HashMap<>();
    private final List<Region> large = new ArrayList<>();

    RegionIndex(Collection<Region> regions) {
        for (Region region : regions) {
            Cuboid b = region.bounds();
            long columnsX = (b.maxX() >> 4) - (b.minX() >> 4) + 1L;
            long columnsZ = (b.maxZ() >> 4) - (b.minZ() >> 4) + 1L;
            if (columnsX * columnsZ > MAX_CELLS) {
                large.add(region);
                continue;
            }
            for (int x = b.minX() >> 4; x <= b.maxX() >> 4; x++) {
                for (int z = b.minZ() >> 4; z <= b.maxZ() >> 4; z++) {
                    cells.computeIfAbsent(key(x, z), k -> new ArrayList<>()).add(region);
                }
            }
        }
    }

    List<Region> at(int x, int y, int z) {
        List<Region> found = new ArrayList<>(2);
        for (Region region : cells.getOrDefault(key(x >> 4, z >> 4), List.of())) {
            if (region.bounds().contains(x, y, z)) {
                found.add(region);
            }
        }
        for (Region region : large) {
            if (region.bounds().contains(x, y, z)) {
                found.add(region);
            }
        }
        return found;
    }

    private static long key(int cellX, int cellZ) {
        return ((long) cellX << 32) | (cellZ & 0xffffffffL);
    }
}
