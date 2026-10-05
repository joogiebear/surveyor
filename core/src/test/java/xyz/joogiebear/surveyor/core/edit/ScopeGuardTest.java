package xyz.joogiebear.surveyor.core.edit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import xyz.joogiebear.surveyor.core.agent.AgentScope;
import xyz.joogiebear.surveyor.core.region.Audience;
import xyz.joogiebear.surveyor.core.region.Cuboid;
import xyz.joogiebear.surveyor.core.region.Flag;
import xyz.joogiebear.surveyor.core.region.FlagValue;
import xyz.joogiebear.surveyor.core.region.Region;
import xyz.joogiebear.surveyor.core.region.RegionRegistry;

class ScopeGuardTest {

    // The token may work in x 0..9; a protected region covers x 5..14.
    private static final AgentScope SCOPE = new AgentScope(Set.of("world"),
            new AgentScope.Box("world", new Cuboid(0, 0, 0, 9, 9, 9)), Set.of("edit.*"), null, null);

    private static EditGuard guard() {
        RegionRegistry registry = new RegionRegistry();
        registry.replaceAll(List.of(new Region(1, "world", "spawn", new Cuboid(5, 0, 0, 14, 9, 9), 0, Map.of(),
                Map.of(Flag.BUILD, new FlagValue(false, Audience.NON_MEMBERS)))));
        // An agent is not a player, so regions see it as a non-member.
        return new ScopeGuard(SCOPE, "world", new RegionGuard(registry.snapshot(), "world", null, true));
    }

    @Test
    void scopeIsAskedBeforeRegions() {
        EditGuard guard = guard();
        assertEquals(EditGuard.Denial.NONE, guard.check(2, 2, 2));
        assertEquals(EditGuard.Denial.PROTECTED, guard.check(6, 2, 2));
        // Outside the box and inside the region: scope speaks first.
        assertEquals(EditGuard.Denial.OUT_OF_SCOPE, guard.check(12, 2, 2));
        assertEquals(EditGuard.Denial.OUT_OF_SCOPE, guard.check(2, 20, 2));
    }

    @Test
    void allowsIsTrueOnlyWhenNothingDenies() {
        EditGuard guard = guard();
        assertTrue(guard.allows(2, 2, 2));
        assertFalse(guard.allows(6, 2, 2));
        assertFalse(guard.allows(12, 2, 2));
    }

    @Test
    void countsEachReasonSeparately() {
        // x 0..14, y 0, z 0: x 0..4 free (5), x 5..9 protected (5), x 10..14 out of scope (5).
        EditGuard.Counts counts = guard().countDenials(new Cuboid(0, 0, 0, 14, 0, 0));
        assertEquals(5, counts.outOfScope());
        assertEquals(5, counts.protectedBlocks());
        assertEquals(10, counts.total());
        assertEquals(10, guard().countDenied(new Cuboid(0, 0, 0, 14, 0, 0)));
    }

    @Test
    void aWorldOutsideTheScopeIsEntirelyOutOfScope() {
        EditGuard other = new ScopeGuard(SCOPE, "nether", EditGuard.ALLOW_ALL);
        assertEquals(100, other.countDenials(new Cuboid(0, 0, 0, 9, 0, 9)).outOfScope());
    }

    @Test
    void plainGuardsReportProtected() {
        EditGuard deny = (x, y, z) -> false;
        assertEquals(EditGuard.Denial.PROTECTED, deny.check(0, 0, 0));
        assertEquals(EditGuard.Denial.NONE, EditGuard.ALLOW_ALL.check(0, 0, 0));
    }
}
