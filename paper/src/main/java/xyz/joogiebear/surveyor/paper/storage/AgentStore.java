package xyz.joogiebear.surveyor.paper.storage;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import xyz.joogiebear.surveyor.core.agent.AgentScope;
import xyz.joogiebear.surveyor.core.agent.AgentToken;

/** SQL for agent tokens, idempotency keys and audit queries. Everything runs on the database thread. */
public final class AgentStore {

    public record StoredResult(String requestHash, String responseJson, long createdAt) {
    }

    /** One audit row. {@code actorName} is the token name for agents, null otherwise. */
    public record AuditRow(long id, long at, String actorType, String actorId, String actorName, String action,
                           String target, String operationId, String result, String reason) {
    }

    /** Which rows an audit query wants; null fields do not filter. */
    public record AuditFilter(String actorType, String actorId, String operationId, String targetContains,
                              String action, int limit) {
    }

    private final Database database;

    public AgentStore(Database database) {
        this.database = database;
    }

    public CompletableFuture<List<AgentToken>> loadTokens() {
        return database.submit(c -> {
            List<AgentToken> tokens = new ArrayList<>();
            try (Statement s = c.createStatement();
                 ResultSet r = s.executeQuery("SELECT id, name, secret_hash, secret_prefix, scope_json, created_by, "
                         + "created_at, expires_at, revoked_at FROM agent_tokens ORDER BY id")) {
                while (r.next()) {
                    long expires = r.getLong(8);
                    Long expiresAt = r.wasNull() ? null : expires;
                    long revoked = r.getLong(9);
                    Long revokedAt = r.wasNull() ? null : revoked;
                    tokens.add(new AgentToken(r.getLong(1), r.getString(2), r.getString(3), r.getString(4),
                            AgentScope.fromJson(r.getString(5)), r.getString(6), r.getLong(7), expiresAt, revokedAt));
                }
            }
            return tokens;
        });
    }

    /** @throws RegionException with key {@code token-exists} if the name is taken */
    public CompletableFuture<AgentToken> create(String name, String hash, String prefix, String createdBy, Long expiresAt) {
        return database.submit(c -> {
            try (PreparedStatement s = c.prepareStatement("SELECT 1 FROM agent_tokens WHERE name = ? COLLATE NOCASE")) {
                s.setString(1, name);
                try (ResultSet r = s.executeQuery()) {
                    if (r.next()) {
                        throw new RegionException("token-exists");
                    }
                }
            }
            long now = System.currentTimeMillis();
            try (PreparedStatement s = c.prepareStatement("INSERT INTO agent_tokens (name, secret_hash, secret_prefix, "
                    + "scope_json, created_by, created_at, expires_at) VALUES (?, ?, ?, ?, ?, ?, ?)", Statement.RETURN_GENERATED_KEYS)) {
                s.setString(1, name);
                s.setString(2, hash);
                s.setString(3, prefix);
                s.setString(4, AgentScope.NONE.toJson());
                s.setString(5, createdBy);
                s.setLong(6, now);
                if (expiresAt == null) {
                    s.setNull(7, java.sql.Types.INTEGER);
                } else {
                    s.setLong(7, expiresAt);
                }
                s.executeUpdate();
                try (ResultSet keys = s.getGeneratedKeys()) {
                    keys.next();
                    return new AgentToken(keys.getLong(1), name, hash, prefix, AgentScope.NONE, createdBy, now, expiresAt, null);
                }
            }
        });
    }

    public CompletableFuture<Void> setScope(long id, AgentScope scope) {
        return database.submit(c -> {
            try (PreparedStatement s = c.prepareStatement("UPDATE agent_tokens SET scope_json = ? WHERE id = ?")) {
                s.setString(1, scope.toJson());
                s.setLong(2, id);
                s.executeUpdate();
            }
            return null;
        });
    }

    public CompletableFuture<Void> revoke(long id, long at) {
        return database.submit(c -> {
            try (PreparedStatement s = c.prepareStatement("UPDATE agent_tokens SET revoked_at = ? WHERE id = ? AND revoked_at IS NULL")) {
                s.setLong(1, at);
                s.setLong(2, id);
                s.executeUpdate();
            }
            return null;
        });
    }

    /** Null when the key was never used (or its result has been pruned). */
    public CompletableFuture<StoredResult> findResult(long tokenId, String key) {
        return database.submit(c -> {
            try (PreparedStatement s = c.prepareStatement("SELECT request_hash, response_json, created_at FROM idempotency "
                    + "WHERE token_id = ? AND key = ?")) {
                s.setLong(1, tokenId);
                s.setString(2, key);
                try (ResultSet r = s.executeQuery()) {
                    return r.next() ? new StoredResult(r.getString(1), r.getString(2), r.getLong(3)) : null;
                }
            }
        });
    }

    public CompletableFuture<Void> storeResult(long tokenId, String key, String requestHash, String responseJson) {
        return database.submit(c -> {
            try (PreparedStatement s = c.prepareStatement("INSERT OR REPLACE INTO idempotency "
                    + "(token_id, key, request_hash, response_json, created_at) VALUES (?, ?, ?, ?, ?)")) {
                s.setLong(1, tokenId);
                s.setString(2, key);
                s.setString(3, requestHash);
                s.setString(4, responseJson);
                s.setLong(5, System.currentTimeMillis());
                s.executeUpdate();
            }
            return null;
        });
    }

    public CompletableFuture<Integer> pruneResults(long olderThanMillis) {
        return database.submit(c -> {
            try (PreparedStatement s = c.prepareStatement("DELETE FROM idempotency WHERE created_at < ?")) {
                s.setLong(1, olderThanMillis);
                return s.executeUpdate();
            }
        });
    }

    /** Deletes up to {@code limit} audit rows older than the cutoff. Call again until it returns 0. */
    public CompletableFuture<Integer> pruneAudit(long olderThanMillis, int limit) {
        return database.submit(c -> {
            try (PreparedStatement s = c.prepareStatement("DELETE FROM audit_log WHERE id IN "
                    + "(SELECT id FROM audit_log WHERE at < ? ORDER BY id LIMIT ?)")) {
                s.setLong(1, olderThanMillis);
                s.setInt(2, limit);
                return s.executeUpdate();
            }
        });
    }

    /** Newest first. */
    public CompletableFuture<List<AuditRow>> audit(AuditFilter filter) {
        return database.submit(c -> {
            StringBuilder sql = new StringBuilder("SELECT a.id, a.at, a.actor_type, a.actor_id, t.name, a.action, a.target, "
                    + "a.operation_id, a.result, a.reason FROM audit_log a "
                    + "LEFT JOIN agent_tokens t ON a.actor_type = 'agent' AND CAST(t.id AS TEXT) = a.actor_id WHERE 1 = 1");
            List<String> args = new ArrayList<>();
            if (filter.actorType() != null) {
                sql.append(" AND a.actor_type = ?");
                args.add(filter.actorType());
            }
            if (filter.actorId() != null) {
                sql.append(" AND a.actor_id = ?");
                args.add(filter.actorId());
            }
            if (filter.operationId() != null) {
                sql.append(" AND a.operation_id = ?");
                args.add(filter.operationId());
            }
            if (filter.targetContains() != null) {
                sql.append(" AND instr(lower(a.target), lower(?)) > 0");
                args.add(filter.targetContains());
            }
            if (filter.action() != null) {
                sql.append(" AND a.action = ?");
                args.add(filter.action());
            }
            sql.append(" ORDER BY a.id DESC LIMIT ").append(filter.limit());
            List<AuditRow> rows = new ArrayList<>();
            try (PreparedStatement s = c.prepareStatement(sql.toString())) {
                for (int i = 0; i < args.size(); i++) {
                    s.setString(i + 1, args.get(i));
                }
                try (ResultSet r = s.executeQuery()) {
                    while (r.next()) {
                        rows.add(new AuditRow(r.getLong(1), r.getLong(2), r.getString(3), r.getString(4), r.getString(5),
                                r.getString(6), r.getString(7), r.getString(8), r.getString(9), r.getString(10)));
                    }
                }
            }
            return rows;
        });
    }
}
