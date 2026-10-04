package xyz.joogiebear.surveyor.core.edit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import xyz.joogiebear.surveyor.core.region.Cuboid;

class SelectionsTest {

    private static final UUID ALICE = UUID.randomUUID();
    private static final UUID BOB = UUID.randomUUID();

    @Test
    void incompleteUntilBothPositionsAreSet() {
        Selections<UUID> selections = new Selections<>();
        selections.setPos1(ALICE, "world", new BlockPos(1, 2, 3));
        assertFalse(selections.get(ALICE, "world").complete());
        assertTrue(selections.get(ALICE, "world").cuboid().isEmpty());
        selections.setPos2(ALICE, "world", new BlockPos(-4, 5, 6));
        assertEquals(Cuboid.of(-4, 2, 3, 1, 5, 6), selections.get(ALICE, "world").cuboid().orElseThrow());
    }

    @Test
    void actorsAndWorldsAreSeparate() {
        Selections<UUID> selections = new Selections<>();
        selections.setPos1(ALICE, "world", new BlockPos(1, 1, 1));
        assertEquals(Selection.EMPTY, selections.get(BOB, "world"));
        assertEquals(Selection.EMPTY, selections.get(ALICE, "nether"));
    }

    @Test
    void settingOnePositionKeepsTheOther() {
        Selections<UUID> selections = new Selections<>();
        selections.setPos1(ALICE, "world", new BlockPos(1, 1, 1));
        selections.setPos2(ALICE, "world", new BlockPos(2, 2, 2));
        selections.setPos1(ALICE, "world", new BlockPos(0, 0, 0));
        assertEquals(new BlockPos(2, 2, 2), selections.get(ALICE, "world").pos2());
    }

    @Test
    void clearAndForget() {
        Selections<UUID> selections = new Selections<>();
        selections.setPos1(ALICE, "world", new BlockPos(1, 1, 1));
        selections.setPos1(ALICE, "nether", new BlockPos(1, 1, 1));
        selections.clear(ALICE, "world");
        assertEquals(Selection.EMPTY, selections.get(ALICE, "world"));
        assertEquals(new BlockPos(1, 1, 1), selections.get(ALICE, "nether").pos1());
        selections.forget(ALICE);
        assertEquals(Selection.EMPTY, selections.get(ALICE, "nether"));
    }
}
