package xyz.joogiebear.surveyor.core.storage;

import java.util.ArrayList;
import java.util.List;

/** Decides which migrations still have to run. Migrations only move forward, never back. */
public final class MigrationPlan {

    private MigrationPlan() {
    }

    /**
     * @param known   every migration this build ships, in any order
     * @param current the version recorded in the database, 0 for a fresh one
     * @return the migrations to apply, ordered by version
     * @throws IllegalArgumentException if the known versions have gaps or duplicates, or the
     *         database is newer than this build
     */
    public static List<Migration> pending(List<Migration> known, int current) {
        List<Migration> ordered = new ArrayList<>(known);
        ordered.sort((a, b) -> Integer.compare(a.version(), b.version()));
        for (int i = 0; i < ordered.size(); i++) {
            if (ordered.get(i).version() != i + 1) {
                throw new IllegalArgumentException(
                        "migration versions must run 1.." + ordered.size() + " without gaps or duplicates");
            }
        }
        if (current > ordered.size()) {
            throw new IllegalArgumentException("database is at version " + current
                    + " but this build only knows up to " + ordered.size());
        }
        return ordered.subList(current, ordered.size());
    }
}
