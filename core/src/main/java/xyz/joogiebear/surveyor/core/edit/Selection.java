package xyz.joogiebear.surveyor.core.edit;

import java.util.Optional;
import xyz.joogiebear.surveyor.core.region.Cuboid;

/** Two corner positions, either of which may still be unset. */
public record Selection(BlockPos pos1, BlockPos pos2) {

    public static final Selection EMPTY = new Selection(null, null);

    public Selection withPos1(BlockPos pos) {
        return new Selection(pos, pos2);
    }

    public Selection withPos2(BlockPos pos) {
        return new Selection(pos1, pos);
    }

    public boolean complete() {
        return pos1 != null && pos2 != null;
    }

    public Optional<Cuboid> cuboid() {
        if (!complete()) {
            return Optional.empty();
        }
        return Optional.of(Cuboid.of(pos1.x(), pos1.y(), pos1.z(), pos2.x(), pos2.y(), pos2.z()));
    }
}
