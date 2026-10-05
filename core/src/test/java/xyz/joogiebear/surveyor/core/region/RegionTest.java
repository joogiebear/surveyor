package xyz.joogiebear.surveyor.core.region;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RegionTest {

    private static Region region() {
        return new Region(1, "w", "spawn", new Cuboid(0, 0, 0, 5, 5, 5), 0, Map.of(), Map.of());
    }

    @Test
    void cuboidNormalizesCornersInAnyOrder() {
        Cuboid box = Cuboid.of(10, 20, 30, -10, -20, -30);
        assertEquals(new Cuboid(-10, -20, -30, 10, 20, 30), box);
        assertTrue(box.contains(0, 0, 0));
        assertFalse(box.contains(11, 0, 0));
    }

    @Test
    void volumeSaturatesInsteadOfWrappingAround() {
        assertEquals(1000, new Cuboid(0, 0, 0, 9, 9, 9).volume());
        Cuboid everything = new Cuboid(Integer.MIN_VALUE, -64, Integer.MIN_VALUE, Integer.MAX_VALUE, 319, Integer.MAX_VALUE);
        assertEquals(Long.MAX_VALUE, everything.volume());
        // 2^32 * 2^32 * 1 overflows a long on its own.
        assertEquals(Long.MAX_VALUE, new Cuboid(Integer.MIN_VALUE, 0, Integer.MIN_VALUE, Integer.MAX_VALUE, 0, Integer.MAX_VALUE).volume());
    }

    @Test
    void cuboidIntersectionAndContainment() {
        Cuboid box = new Cuboid(0, 0, 0, 9, 9, 9);
        assertTrue(box.intersects(new Cuboid(9, 9, 9, 20, 20, 20)));
        assertTrue(box.intersects(new Cuboid(-5, -5, -5, 0, 0, 0)));
        assertFalse(box.intersects(new Cuboid(10, 0, 0, 20, 9, 9)));
        assertFalse(box.intersects(new Cuboid(0, 10, 0, 9, 20, 9)));
        assertTrue(box.contains(new Cuboid(1, 1, 1, 9, 9, 9)));
        assertTrue(box.contains(box));
        assertFalse(box.contains(new Cuboid(1, 1, 1, 10, 9, 9)));
    }

    @Test
    void cuboidWithMinAboveMaxIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new Cuboid(1, 0, 0, 0, 0, 0));
    }

    @Test
    void nameRules() {
        assertTrue(Region.isValidName("spawn_1-a"));
        assertFalse(Region.isValidName(""));
        assertFalse(Region.isValidName("has space"));
        assertFalse(Region.isValidName("x".repeat(33)));
        assertFalse(Region.isValidName("world:spawn"));
        assertFalse(Region.isValidName(null));
        assertThrows(IllegalArgumentException.class,
                () -> new Region(1, "w", "bad name", new Cuboid(0, 0, 0, 1, 1, 1), 0, Map.of(), Map.of()));
    }

    @Test
    void withMethodsLeaveTheOriginalUntouched() {
        UUID player = UUID.randomUUID();
        Region original = region();
        Region changed = original.withMember(player, Role.OWNER)
                .withFlag(Flag.PVP, new FlagValue(false, Audience.EVERYONE))
                .withPriority(4);
        assertTrue(original.members().isEmpty());
        assertTrue(original.flags().isEmpty());
        assertEquals(Role.OWNER, changed.roleOf(player));
        assertEquals(4, changed.priority());
        assertNull(changed.withoutMember(player).roleOf(player));
        assertTrue(changed.withoutFlag(Flag.PVP).flags().isEmpty());
    }

    @Test
    void roleOfNullActorIsNull() {
        assertNull(region().roleOf(null));
    }

    @Test
    void keysParseCaseInsensitively() {
        assertEquals(Flag.MOB_SPAWNING, Flag.fromKey("Mob-Spawning").orElseThrow());
        assertEquals(Audience.NON_MEMBERS, Audience.fromKey("NON-MEMBERS").orElseThrow());
        assertEquals(Role.OWNER, Role.fromKey("Owner").orElseThrow());
        assertTrue(Flag.fromKey("fly").isEmpty());
    }
}
