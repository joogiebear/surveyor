package xyz.joogiebear.surveyor.core.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class MigrationPlanTest {

    private static final Migration ONE = new Migration(1, "baseline");
    private static final Migration TWO = new Migration(2, "second");
    private static final Migration THREE = new Migration(3, "third");

    @Test
    void freshDatabaseGetsEverythingInOrder() {
        assertEquals(List.of(ONE, TWO, THREE), MigrationPlan.pending(List.of(THREE, ONE, TWO), 0));
    }

    @Test
    void partiallyMigratedDatabaseGetsOnlyTheRest() {
        assertEquals(List.of(TWO, THREE), MigrationPlan.pending(List.of(ONE, TWO, THREE), 1));
    }

    @Test
    void upToDateDatabaseGetsNothing() {
        assertTrue(MigrationPlan.pending(List.of(ONE, TWO), 2).isEmpty());
    }

    @Test
    void databaseNewerThanThisBuildIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> MigrationPlan.pending(List.of(ONE), 2));
    }

    @Test
    void gapInKnownVersionsIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> MigrationPlan.pending(List.of(ONE, THREE), 0));
    }

    @Test
    void duplicateVersionIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> MigrationPlan.pending(List.of(ONE, new Migration(1, "again")), 0));
    }

    @Test
    void versionBelowOneIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new Migration(0, "bad"));
    }
}
