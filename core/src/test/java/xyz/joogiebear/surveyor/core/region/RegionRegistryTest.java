package xyz.joogiebear.surveyor.core.region;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RegionRegistryTest {

    private static Region region(long id, String world, String name, Cuboid bounds) {
        return new Region(id, world, name, bounds, 0, Map.of(), Map.of());
    }

    @Test
    void findsRegionsContainingAPointAcrossNegativeCoordinates() {
        RegionRegistry registry = new RegionRegistry();
        registry.put(region(1, "w", "spawn", new Cuboid(-20, 0, -20, 20, 50, 20)));
        RegionRegistry.Snapshot snapshot = registry.snapshot();
        assertEquals(1, snapshot.at("w", -20, 0, -20).size());
        assertEquals(1, snapshot.at("w", 20, 50, 20).size());
        assertTrue(snapshot.at("w", 21, 0, 0).isEmpty());
        assertTrue(snapshot.at("w", 0, 51, 0).isEmpty());
        assertTrue(snapshot.at("other", 0, 0, 0).isEmpty());
    }

    @Test
    void overlappingRegionsAreAllReturned() {
        RegionRegistry registry = new RegionRegistry();
        registry.put(region(1, "w", "a", new Cuboid(0, 0, 0, 10, 10, 10)));
        registry.put(region(2, "w", "b", new Cuboid(5, 5, 5, 15, 15, 15)));
        assertEquals(2, registry.snapshot().at("w", 7, 7, 7).size());
        assertEquals(1, registry.snapshot().at("w", 2, 2, 2).size());
    }

    @Test
    void hugeRegionsAreStillFoundWithoutFillingTheGrid() {
        RegionRegistry registry = new RegionRegistry();
        registry.put(region(1, "w", "world-wide", new Cuboid(-30_000_000, -64, -30_000_000, 30_000_000, 320, 30_000_000)));
        assertEquals(1, registry.snapshot().at("w", 12_345_678, 70, -29_999_999).size());
    }

    @Test
    void nameLookupIgnoresCaseAndIsPerWorld() {
        RegionRegistry registry = new RegionRegistry();
        registry.put(region(1, "w", "Spawn", new Cuboid(0, 0, 0, 1, 1, 1)));
        registry.put(region(2, "nether", "Spawn", new Cuboid(0, 0, 0, 1, 1, 1)));
        assertEquals(1, registry.snapshot().find("w", "sPAWN").id());
        assertEquals(2, registry.snapshot().find("nether", "spawn").id());
        assertNull(registry.snapshot().find("end", "spawn"));
    }

    @Test
    void putReplacesAndRemoveDeletes() {
        RegionRegistry registry = new RegionRegistry();
        Region original = region(1, "w", "a", new Cuboid(0, 0, 0, 1, 1, 1));
        registry.put(original);
        registry.put(original.withPriority(7));
        assertEquals(7, registry.snapshot().get(1).priority());
        assertEquals(1, registry.snapshot().size());
        registry.remove(1);
        assertNull(registry.snapshot().get(1));
        assertTrue(registry.snapshot().at("w", 0, 0, 0).isEmpty());
    }

    @Test
    void oldSnapshotsStayUnchangedAfterWrites() {
        RegionRegistry registry = new RegionRegistry();
        RegionRegistry.Snapshot before = registry.snapshot();
        registry.put(region(1, "w", "a", new Cuboid(0, 0, 0, 1, 1, 1)));
        assertEquals(0, before.size());
        assertNotNull(registry.snapshot().get(1));
    }

    @Test
    void listIsOrderedByNameAndFilteredByWorld() {
        RegionRegistry registry = new RegionRegistry();
        registry.put(region(1, "w", "beta", new Cuboid(0, 0, 0, 1, 1, 1)));
        registry.put(region(2, "w", "Alpha", new Cuboid(0, 0, 0, 1, 1, 1)));
        registry.put(region(3, "nether", "gamma", new Cuboid(0, 0, 0, 1, 1, 1)));
        assertEquals(List.of("Alpha", "beta"), registry.snapshot().list("w").stream().map(Region::name).toList());
        assertEquals(3, registry.snapshot().list(null).size());
    }
}
