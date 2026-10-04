package xyz.joogiebear.surveyor.paper.storage;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import xyz.joogiebear.surveyor.core.storage.Migration;
import xyz.joogiebear.surveyor.core.storage.MigrationPlan;

/**
 * One SQLite connection owned by one dedicated thread, so no database work ever runs on a region
 * thread and writes stay serialized.
 */
public final class Database implements AutoCloseable {

    private static final List<Migration> MIGRATIONS = List.of(
            new Migration(1, "baseline"), new Migration(2, "undo_marker"));

    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "Surveyor-Database");
        thread.setDaemon(true);
        return thread;
    });

    private Connection connection;
    private volatile String status = "not started";

    public String status() {
        return status;
    }

    public CompletableFuture<Void> open(Path file) {
        status = "starting";
        return CompletableFuture.runAsync(() -> {
            try {
                // Driver.connect directly: DriverManager would not see a driver from the library loader.
                connection = new org.sqlite.JDBC().connect("jdbc:sqlite:" + file, new Properties());
                try (Statement statement = connection.createStatement()) {
                    statement.execute("PRAGMA journal_mode=WAL");
                    statement.execute("PRAGMA foreign_keys=ON");
                    statement.execute("PRAGMA busy_timeout=5000");
                    statement.execute("CREATE TABLE IF NOT EXISTS schema_version (version INTEGER NOT NULL)");
                }
                int version = migrate();
                status = "ready (SQLite, WAL, schema v" + version + ")";
            } catch (SQLException | IOException | RuntimeException e) {
                status = "failed: " + e.getMessage();
                throw new IllegalStateException("database failed to start", e);
            }
        }, executor);
    }

    /** Runs a task on the database thread. Tasks queue behind {@link #open}, so order is preserved. */
    public <T> CompletableFuture<T> submit(SqlTask<T> task) {
        return CompletableFuture.supplyAsync(() -> {
            if (connection == null) {
                throw new IllegalStateException("database is not available");
            }
            try {
                return task.run(connection);
            } catch (SQLException e) {
                throw new CompletionException(e);
            }
        }, executor);
    }

    @FunctionalInterface
    public interface SqlTask<T> {
        T run(Connection connection) throws SQLException;
    }

    private int migrate() throws SQLException, IOException {
        int current;
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT COALESCE(MAX(version), 0) FROM schema_version")) {
            current = result.getInt(1);
        }
        int version = current;
        for (Migration migration : MigrationPlan.pending(MIGRATIONS, current)) {
            apply(migration);
            version = migration.version();
        }
        return version;
    }

    private void apply(Migration migration) throws SQLException, IOException {
        String resource = "/db/migration/%03d_%s.sql".formatted(migration.version(), migration.name());
        String sql;
        try (InputStream in = Database.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IOException("missing migration resource " + resource);
            }
            sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        connection.setAutoCommit(false);
        try (Statement statement = connection.createStatement()) {
            for (String part : sql.split(";")) {
                if (!part.isBlank()) {
                    statement.execute(part);
                }
            }
            statement.execute("INSERT INTO schema_version (version) VALUES (" + migration.version() + ")");
            connection.commit();
        } catch (SQLException e) {
            connection.rollback();
            throw e;
        } finally {
            connection.setAutoCommit(true);
        }
    }

    @Override
    public void close() {
        executor.execute(() -> {
            try {
                if (connection != null) {
                    connection.close();
                }
            } catch (SQLException ignored) {
                // shutting down; nothing useful left to do with the error
            }
        });
        executor.shutdown();
        try {
            executor.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
