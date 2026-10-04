package xyz.joogiebear.surveyor.paper.storage;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import xyz.joogiebear.surveyor.core.edit.EditKind;
import xyz.joogiebear.surveyor.core.edit.JournalCodec;
import xyz.joogiebear.surveyor.core.edit.OperationStatus;

/** SQL for operations and the undo journal. Everything runs on the database thread. */
public final class EditStore {

    public record JournalRow(int chunkX, int chunkZ, int seq, List<JournalCodec.Entry> entries) {
    }

    public record Operation(String id, String actorType, String actorId, EditKind kind, String world,
                            OperationStatus status, String undoneBy) {
    }

    private final Database database;

    public EditStore(Database database) {
        this.database = database;
    }

    public CompletableFuture<Void> insert(String id, String actorType, String actorId, EditKind kind, String world,
                                          String paramsJson) {
        return database.submit(c -> {
            try (PreparedStatement s = c.prepareStatement("INSERT INTO operations "
                    + "(id, actor_type, actor_id, kind, world, status, params_json, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) {
                s.setString(1, id);
                s.setString(2, actorType);
                s.setString(3, actorId);
                s.setString(4, kind.key());
                s.setString(5, world);
                s.setString(6, OperationStatus.PLANNED.key());
                s.setString(7, paramsJson);
                s.setLong(8, System.currentTimeMillis());
                s.executeUpdate();
            }
            return null;
        });
    }

    public CompletableFuture<Void> setStatus(String id, OperationStatus status, long blocksChanged) {
        return database.submit(c -> {
            try (PreparedStatement s = c.prepareStatement(
                    "UPDATE operations SET status = ?, blocks_changed = ?, finished_at = ? WHERE id = ?")) {
                s.setString(1, status.key());
                s.setLong(2, blocksChanged);
                if (status.terminal()) {
                    s.setLong(3, System.currentTimeMillis());
                } else {
                    s.setNull(3, java.sql.Types.INTEGER);
                }
                s.setString(4, id);
                s.executeUpdate();
            }
            return null;
        });
    }

    /** Encodes and commits one batch's previous block states. Completes only once the row is stored. */
    public CompletableFuture<Void> writeJournal(String id, int chunkX, int chunkZ, int seq,
                                                List<JournalCodec.Entry> entries) {
        return database.submit(c -> {
            byte[] data = JournalCodec.encode(chunkX, chunkZ, entries);
            try (PreparedStatement s = c.prepareStatement(
                    "INSERT INTO undo_journal (operation_id, chunk_x, chunk_z, seq, data) VALUES (?, ?, ?, ?, ?)")) {
                s.setString(1, id);
                s.setInt(2, chunkX);
                s.setInt(3, chunkZ);
                s.setInt(4, seq);
                s.setBytes(5, data);
                s.executeUpdate();
            }
            return null;
        });
    }

    /** Every journal row of an operation, newest batch first, already decoded. */
    public CompletableFuture<List<JournalRow>> readJournal(String id) {
        return database.submit(c -> {
            List<JournalRow> rows = new ArrayList<>();
            try (PreparedStatement s = c.prepareStatement(
                    "SELECT chunk_x, chunk_z, seq, data FROM undo_journal WHERE operation_id = ? ORDER BY seq DESC")) {
                s.setString(1, id);
                try (ResultSet r = s.executeQuery()) {
                    while (r.next()) {
                        int cx = r.getInt(1);
                        int cz = r.getInt(2);
                        rows.add(new JournalRow(cx, cz, r.getInt(3), JournalCodec.decode(cx, cz, r.getBytes(4))));
                    }
                }
            }
            return rows;
        });
    }

    /** Null when there is no such operation. */
    public CompletableFuture<Operation> find(String id) {
        return database.submit(c -> {
            try (PreparedStatement s = c.prepareStatement("SELECT id, actor_type, actor_id, kind, world, status, undone_by "
                    + "FROM operations WHERE id = ?")) {
                s.setString(1, id);
                try (ResultSet r = s.executeQuery()) {
                    return r.next() ? read(r) : null;
                }
            }
        });
    }

    /** The actor's most recent journaled operation that has not been undone, or null. */
    public CompletableFuture<Operation> latestUndoable(String actorType, String actorId) {
        return database.submit(c -> {
            try (PreparedStatement s = c.prepareStatement("SELECT id, actor_type, actor_id, kind, world, status, undone_by "
                    + "FROM operations WHERE actor_type = ? AND actor_id = ? AND undone_by IS NULL "
                    + "AND kind IN ('set', 'replace', 'paste') "
                    + "AND EXISTS (SELECT 1 FROM undo_journal j WHERE j.operation_id = operations.id) "
                    + "ORDER BY created_at DESC, id DESC LIMIT 1")) {
                s.setString(1, actorType);
                s.setString(2, actorId);
                try (ResultSet r = s.executeQuery()) {
                    return r.next() ? read(r) : null;
                }
            }
        });
    }

    public CompletableFuture<Void> markUndone(String id, String undoId) {
        return database.submit(c -> {
            try (PreparedStatement s = c.prepareStatement("UPDATE operations SET undone_by = ? WHERE id = ?")) {
                s.setString(1, undoId);
                s.setString(2, id);
                s.executeUpdate();
            }
            return null;
        });
    }

    /**
     * Operations still marked planned or running were cut off by a crash or restart. Marks them
     * failed and returns their ids. Whatever batches they had applied are covered by the journal.
     */
    public CompletableFuture<List<String>> recoverInterrupted() {
        return database.submit(c -> {
            List<String> ids = new ArrayList<>();
            try (PreparedStatement s = c.prepareStatement(
                    "SELECT id FROM operations WHERE status IN ('planned', 'running') ORDER BY created_at");
                 ResultSet r = s.executeQuery()) {
                while (r.next()) {
                    ids.add(r.getString(1));
                }
            }
            try (PreparedStatement s = c.prepareStatement("UPDATE operations SET status = 'failed', finished_at = ? "
                    + "WHERE status IN ('planned', 'running')")) {
                s.setLong(1, System.currentTimeMillis());
                s.executeUpdate();
            }
            return ids;
        });
    }

    public CompletableFuture<Void> audit(String actorType, String actorId, String action, String target,
                                         String operationId, String result) {
        return database.submit(c -> {
            try (PreparedStatement s = c.prepareStatement("INSERT INTO audit_log "
                    + "(at, actor_type, actor_id, action, target, operation_id, result) VALUES (?, ?, ?, ?, ?, ?, ?)")) {
                s.setLong(1, System.currentTimeMillis());
                s.setString(2, actorType);
                s.setString(3, actorId);
                s.setString(4, action);
                s.setString(5, target);
                s.setString(6, operationId);
                s.setString(7, result);
                s.executeUpdate();
            }
            return null;
        });
    }

    private static Operation read(ResultSet r) throws SQLException {
        return new Operation(r.getString(1), r.getString(2), r.getString(3),
                EditKind.valueOf(r.getString(4).toUpperCase(java.util.Locale.ROOT)), r.getString(5),
                OperationStatus.valueOf(r.getString(6).toUpperCase(java.util.Locale.ROOT)), r.getString(7));
    }
}
