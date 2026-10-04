package xyz.joogiebear.surveyor.core.region;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class FlagResolverTest {

    private static final UUID OWNER = UUID.randomUUID();
    private static final UUID MEMBER = UUID.randomUUID();
    private static final UUID STRANGER = UUID.randomUUID();
    private static final Cuboid BOX = new Cuboid(0, 0, 0, 10, 10, 10);

    private static Region region(long id, int priority, Map<Flag, FlagValue> flags) {
        return new Region(id, "world", "r" + id, BOX, priority,
                Map.of(OWNER, Role.OWNER, MEMBER, Role.MEMBER), flags);
    }

    private static FlagValue allow(Audience audience) {
        return new FlagValue(true, audience);
    }

    private static FlagValue deny(Audience audience) {
        return new FlagValue(false, audience);
    }

    @Test
    void noRegionsFallsBackToDefault() {
        assertTrue(FlagResolver.resolve(List.of(), Flag.BUILD, STRANGER, true));
        assertFalse(FlagResolver.resolve(List.of(), Flag.BUILD, STRANGER, false));
    }

    @Test
    void regionWithoutTheFlagIsTransparent() {
        Region region = region(1, 0, Map.of());
        assertFalse(FlagResolver.resolve(List.of(region), Flag.BUILD, STRANGER, false));
    }

    @Test
    void denyForNonMembersProtectsFromStrangersOnly() {
        Region region = region(1, 0, Map.of(Flag.BUILD, deny(Audience.NON_MEMBERS)));
        assertFalse(FlagResolver.resolve(List.of(region), Flag.BUILD, STRANGER, true));
        assertTrue(FlagResolver.resolve(List.of(region), Flag.BUILD, MEMBER, true));
        assertTrue(FlagResolver.resolve(List.of(region), Flag.BUILD, OWNER, true));
    }

    @Test
    void actorlessEventsCountAsNonMembers() {
        Region region = region(1, 0, Map.of(Flag.MOB_SPAWNING, deny(Audience.NON_MEMBERS)));
        assertFalse(FlagResolver.resolve(List.of(region), Flag.MOB_SPAWNING, null, true));
    }

    @Test
    void ownersAudienceExcludesPlainMembers() {
        Region region = region(1, 0, Map.of(Flag.INTERACT, allow(Audience.OWNERS)));
        assertTrue(FlagResolver.resolve(List.of(region), Flag.INTERACT, OWNER, false));
        assertFalse(FlagResolver.resolve(List.of(region), Flag.INTERACT, MEMBER, false));
    }

    @Test
    void membersAudienceIncludesOwners() {
        Region region = region(1, 0, Map.of(Flag.INTERACT, allow(Audience.MEMBERS)));
        assertTrue(FlagResolver.resolve(List.of(region), Flag.INTERACT, OWNER, false));
        assertTrue(FlagResolver.resolve(List.of(region), Flag.INTERACT, MEMBER, false));
        assertFalse(FlagResolver.resolve(List.of(region), Flag.INTERACT, STRANGER, false));
    }

    @Test
    void higherPriorityWins() {
        Region low = region(1, 0, Map.of(Flag.BUILD, deny(Audience.EVERYONE)));
        Region high = region(2, 5, Map.of(Flag.BUILD, allow(Audience.EVERYONE)));
        assertTrue(FlagResolver.resolve(List.of(low, high), Flag.BUILD, STRANGER, false));
        assertTrue(FlagResolver.resolve(List.of(high, low), Flag.BUILD, STRANGER, false));
    }

    @Test
    void equalPriorityDenyBeatsAllow() {
        Region a = region(1, 3, Map.of(Flag.BUILD, allow(Audience.EVERYONE)));
        Region b = region(2, 3, Map.of(Flag.BUILD, deny(Audience.EVERYONE)));
        assertFalse(FlagResolver.resolve(List.of(a, b), Flag.BUILD, STRANGER, true));
        assertFalse(FlagResolver.resolve(List.of(b, a), Flag.BUILD, STRANGER, true));
    }

    @Test
    void higherPriorityRegionNotApplyingToActorDoesNotShadowLowerOne() {
        Region low = region(1, 0, Map.of(Flag.BUILD, deny(Audience.NON_MEMBERS)));
        Region high = region(2, 9, Map.of(Flag.BUILD, allow(Audience.OWNERS)));
        assertFalse(FlagResolver.resolve(List.of(low, high), Flag.BUILD, STRANGER, true));
    }
}
