package xyz.joogiebear.surveyor.core.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import org.junit.jupiter.api.Test;
import xyz.joogiebear.surveyor.core.region.Cuboid;

class AgentScopeTest {

    private static final AgentScope BOXED = new AgentScope(Set.of("world", "nether"),
            new AgentScope.Box("world", new Cuboid(0, 0, 0, 9, 9, 9)), Set.of("edit.*", "region.get"), 500, 10);

    @Test
    void aNewTokenCanDoNothing() {
        assertFalse(AgentScope.NONE.allowsAction("world.list"));
        assertFalse(AgentScope.NONE.allowsWorld("world"));
        assertFalse(AgentScope.NONE.contains("world", 0, 0, 0));
    }

    @Test
    void actionsMatchByNameGroupOrWildcard() {
        assertTrue(BOXED.allowsAction("edit.set"));
        assertTrue(BOXED.allowsAction("edit.undo"));
        assertTrue(BOXED.allowsAction("region.get"));
        assertFalse(BOXED.allowsAction("region.delete"));
        assertFalse(BOXED.allowsAction("editor.set"));
        assertTrue(new AgentScope(Set.of("world"), null, Set.of("*"), null, null).allowsAction("audit.query"));
    }

    @Test
    void aBoxConfinesTheTokenToItsWorldAndArea() {
        assertTrue(BOXED.contains("world", 5, 5, 5));
        assertTrue(BOXED.contains("world", 9, 9, 9));
        assertFalse(BOXED.contains("world", 10, 5, 5));
        assertFalse(BOXED.contains("nether", 5, 5, 5));
        assertFalse(BOXED.contains("end", 5, 5, 5));
    }

    @Test
    void withoutABoxEveryAllowedWorldIsOpen() {
        AgentScope open = BOXED.withBox(null);
        assertTrue(open.contains("nether", 1000, 5, -1000));
        assertFalse(open.contains("end", 0, 0, 0));
    }

    @Test
    void touchesAndCoversAreaChecks() {
        assertTrue(BOXED.touches("world", new Cuboid(8, 8, 8, 20, 20, 20)));
        assertFalse(BOXED.covers("world", new Cuboid(8, 8, 8, 20, 20, 20)));
        assertTrue(BOXED.covers("world", new Cuboid(1, 1, 1, 9, 9, 9)));
        assertFalse(BOXED.touches("world", new Cuboid(10, 0, 0, 20, 9, 9)));
        assertFalse(BOXED.touches("end", new Cuboid(0, 0, 0, 1, 1, 1)));
    }

    @Test
    void jsonRoundTrips() {
        assertEquals(BOXED, AgentScope.fromJson(BOXED.toJson()));
        AgentScope plain = new AgentScope(Set.of("world"), null, Set.of("world.list"), null, null);
        AgentScope back = AgentScope.fromJson(plain.toJson());
        assertEquals(plain, back);
        assertNull(back.maxBlocks());
    }

    @Test
    void missingFieldsMeanNothingAllowed() {
        assertEquals(AgentScope.NONE, AgentScope.fromJson("{}"));
    }

    @Test
    void rejectsBadScopes() {
        assertThrows(IllegalArgumentException.class, () -> AgentScope.fromJson("not json"));
        assertThrows(IllegalArgumentException.class, () -> AgentScope.fromJson("{\"actions\":[\"edit.explode\"]}"));
        assertThrows(IllegalArgumentException.class, () -> AgentScope.fromJson("{\"maxBlocks\":0}"));
        assertThrows(IllegalArgumentException.class,
                () -> AgentScope.fromJson("{\"box\":{\"world\":\"w\",\"min\":[1,2],\"max\":[1,2,3]}}"));
        assertThrows(IllegalArgumentException.class, () -> AgentScope.fromJson("{\"worlds\":\"world\"}"));
    }

    @Test
    void actionNamesAreValidated() {
        assertTrue(AgentActions.isValid("edit.*"));
        assertTrue(AgentActions.isValid("*"));
        assertTrue(AgentActions.isValid("audit.query"));
        assertFalse(AgentActions.isValid("edit.explode"));
        assertFalse(AgentActions.isValid("bogus.*"));
    }
}
