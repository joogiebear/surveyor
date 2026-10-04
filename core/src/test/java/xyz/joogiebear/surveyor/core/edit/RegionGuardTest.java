package xyz.joogiebear.surveyor.core.edit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import xyz.joogiebear.surveyor.core.region.Audience;
import xyz.joogiebear.surveyor.core.region.Cuboid;
import xyz.joogiebear.surveyor.core.region.Flag;
import xyz.joogiebear.surveyor.core.region.FlagValue;
import xyz.joogiebear.surveyor.core.region.Region;
import xyz.joogiebear.surveyor.core.region.RegionRegistry;
import xyz.joogiebear.surveyor.core.region.Role;

class RegionGuardTest {

    private static final UUID OWNER = UUID.randomUUID();
    private static final UUID STRANGER = UUID.randomUUID();

    private static RegionRegistry.Snapshot snapshot() {
        RegionRegistry registry = new RegionRegistry();
        registry.replaceAll(List.of(new Region(1, "world", "spawn", new Cuboid(0, 0, 0, 9, 9, 9), 0,
                Map.of(OWNER, Role.OWNER), Map.of(Flag.BUILD, new FlagValue(false, Audience.NON_MEMBERS)))));
        return registry.snapshot();
    }

    @Test
    void strangerIsDeniedInsideAndAllowedOutside() {
        EditGuard guard = new RegionGuard(snapshot(), "world", STRANGER, true);
        assertFalse(guard.allows(5, 5, 5));
        assertFalse(guard.allows(0, 0, 0));
        assertFalse(guard.allows(9, 9, 9));
        assertTrue(guard.allows(10, 5, 5));
        assertTrue(guard.allows(5, 5, -1));
    }

    @Test
    void ownerIsAllowedInside() {
        assertTrue(new RegionGuard(snapshot(), "world", OWNER, true).allows(5, 5, 5));
    }

    @Test
    void otherWorldsAreUnaffected() {
        assertTrue(new RegionGuard(snapshot(), "nether", STRANGER, true).allows(5, 5, 5));
    }

    @Test
    void defaultDecidesWhereNoRegionSpeaks() {
        assertFalse(new RegionGuard(snapshot(), "world", STRANGER, false).allows(50, 5, 5));
    }

    @Test
    void countsDeniedPositionsInAnArea() {
        EditGuard guard = new RegionGuard(snapshot(), "world", STRANGER, true);
        // 5x5x5 area overlapping the region by 3x3x3.
        assertEquals(27, guard.countDenied(new Cuboid(7, 7, 7, 11, 11, 11)));
        assertEquals(0, EditGuard.ALLOW_ALL.countDenied(new Cuboid(0, 0, 0, 20, 20, 20)));
    }
}
