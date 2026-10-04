package xyz.joogiebear.surveyor.core.edit;

import java.util.UUID;
import xyz.joogiebear.surveyor.core.region.Flag;
import xyz.joogiebear.surveyor.core.region.FlagResolver;
import xyz.joogiebear.surveyor.core.region.RegionRegistry;

/** The region rules for the `build` flag, resolved against one registry snapshot. */
public record RegionGuard(RegionRegistry.Snapshot regions, String world, UUID actor, boolean defaultAllow)
        implements EditGuard {

    @Override
    public boolean allows(int x, int y, int z) {
        return FlagResolver.resolve(regions.at(world, x, y, z), Flag.BUILD, actor, defaultAllow);
    }
}
