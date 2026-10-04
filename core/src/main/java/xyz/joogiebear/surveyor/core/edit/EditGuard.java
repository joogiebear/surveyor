package xyz.joogiebear.surveyor.core.edit;

import xyz.joogiebear.surveyor.core.region.Cuboid;

/** Decides whether the actor may change a block. Extended with token scope when agents arrive. */
@FunctionalInterface
public interface EditGuard {

    EditGuard ALLOW_ALL = (x, y, z) -> true;

    boolean allows(int x, int y, int z);

    /** How many positions of the area this guard refuses. */
    default long countDenied(Cuboid area) {
        long denied = 0;
        for (int y = area.minY(); y <= area.maxY(); y++) {
            for (int z = area.minZ(); z <= area.maxZ(); z++) {
                for (int x = area.minX(); x <= area.maxX(); x++) {
                    if (!allows(x, y, z)) {
                        denied++;
                    }
                }
            }
        }
        return denied;
    }
}
