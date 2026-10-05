package xyz.joogiebear.surveyor.core.edit;

import xyz.joogiebear.surveyor.core.region.Cuboid;

/** Decides whether the actor may change a block: token scope first, then region rights. */
@FunctionalInterface
public interface EditGuard {

    /** Why a position was refused. */
    enum Denial { NONE, OUT_OF_SCOPE, PROTECTED }

    record Counts(long outOfScope, long protectedBlocks) {

        public long total() {
            return outOfScope + protectedBlocks;
        }
    }

    EditGuard ALLOW_ALL = (x, y, z) -> true;

    boolean allows(int x, int y, int z);

    /** The reason a position is refused. A guard that does not distinguish reasons calls it protected. */
    default Denial check(int x, int y, int z) {
        return allows(x, y, z) ? Denial.NONE : Denial.PROTECTED;
    }

    /** How many positions of the area this guard refuses, by reason. */
    default Counts countDenials(Cuboid area) {
        long outOfScope = 0;
        long protectedBlocks = 0;
        for (int y = area.minY(); y <= area.maxY(); y++) {
            for (int z = area.minZ(); z <= area.maxZ(); z++) {
                for (int x = area.minX(); x <= area.maxX(); x++) {
                    switch (check(x, y, z)) {
                        case OUT_OF_SCOPE -> outOfScope++;
                        case PROTECTED -> protectedBlocks++;
                        case NONE -> {
                        }
                    }
                }
            }
        }
        return new Counts(outOfScope, protectedBlocks);
    }

    /** How many positions of the area this guard refuses. */
    default long countDenied(Cuboid area) {
        return countDenials(area).total();
    }
}
