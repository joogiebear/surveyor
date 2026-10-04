package xyz.joogiebear.surveyor.paper.storage;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import xyz.joogiebear.surveyor.core.region.Audience;
import xyz.joogiebear.surveyor.core.region.Cuboid;
import xyz.joogiebear.surveyor.core.region.Flag;
import xyz.joogiebear.surveyor.core.region.FlagValue;
import xyz.joogiebear.surveyor.core.region.Region;
import xyz.joogiebear.surveyor.core.region.Role;

/** SQL for regions. Every method runs on the database thread and takes that thread's connection. */
final class RegionRepository {

    private static final String PLAYER = "player";

    private RegionRepository() {
    }

    static List<Region> loadAll(Connection c) throws SQLException {
        Map<Long, Map<UUID, Role>> members = new HashMap<>();
        Map<Long, Map<Flag, FlagValue>> flags = new HashMap<>();
        try (Statement s = c.createStatement()) {
            try (ResultSet r = s.executeQuery("SELECT region_id, subject_id, role FROM region_members WHERE subject_type = '"
                    + PLAYER + "'")) {
                while (r.next()) {
                    Role role = Role.fromKey(r.getString("role"))
                            .orElseThrow(() -> new SQLException("unknown member role in database"));
                    members.computeIfAbsent(r.getLong("region_id"), k -> new HashMap<>())
                            .put(UUID.fromString(r.getString("subject_id")), role);
                }
            }
            try (ResultSet r = s.executeQuery("SELECT region_id, flag, value, audience FROM region_flags")) {
                while (r.next()) {
                    Flag flag = Flag.fromKey(r.getString("flag"))
                            .orElseThrow(() -> new SQLException("unknown flag in database"));
                    Audience audience = Audience.fromKey(r.getString("audience") == null ? "everyone" : r.getString("audience"))
                            .orElseThrow(() -> new SQLException("unknown audience in database"));
                    flags.computeIfAbsent(r.getLong("region_id"), k -> new EnumMap<>(Flag.class))
                            .put(flag, new FlagValue("allow".equals(r.getString("value")), audience));
                }
            }
            List<Region> regions = new ArrayList<>();
            try (ResultSet r = s.executeQuery("SELECT id, world, name, min_x, min_y, min_z, max_x, max_y, max_z, priority FROM regions")) {
                while (r.next()) {
                    long id = r.getLong("id");
                    regions.add(new Region(id, r.getString("world"), r.getString("name"),
                            new Cuboid(r.getInt("min_x"), r.getInt("min_y"), r.getInt("min_z"),
                                    r.getInt("max_x"), r.getInt("max_y"), r.getInt("max_z")),
                            r.getInt("priority"),
                            members.getOrDefault(id, Map.of()), flags.getOrDefault(id, Map.of())));
                }
            }
            return regions;
        }
    }

    /** Inserts the region and its first owner in one transaction and returns the new region id. */
    static long insert(Connection c, String world, String name, Cuboid b, UUID owner) throws SQLException {
        c.setAutoCommit(false);
        try {
            long id;
            try (PreparedStatement p = c.prepareStatement(
                    "INSERT INTO regions (world, name, min_x, min_y, min_z, max_x, max_y, max_z, priority, created_at)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, 0, ?)", Statement.RETURN_GENERATED_KEYS)) {
                p.setString(1, world);
                p.setString(2, name);
                p.setInt(3, b.minX());
                p.setInt(4, b.minY());
                p.setInt(5, b.minZ());
                p.setInt(6, b.maxX());
                p.setInt(7, b.maxY());
                p.setInt(8, b.maxZ());
                p.setLong(9, System.currentTimeMillis());
                p.executeUpdate();
                try (ResultSet keys = p.getGeneratedKeys()) {
                    keys.next();
                    id = keys.getLong(1);
                }
            }
            if (owner != null) {
                upsertMember(c, id, owner, Role.OWNER);
            }
            c.commit();
            return id;
        } catch (SQLException e) {
            c.rollback();
            throw e;
        } finally {
            c.setAutoCommit(true);
        }
    }

    static void delete(Connection c, long id) throws SQLException {
        update(c, "DELETE FROM regions WHERE id = ?", id);
    }

    static void setPriority(Connection c, long id, int priority) throws SQLException {
        try (PreparedStatement p = c.prepareStatement("UPDATE regions SET priority = ? WHERE id = ?")) {
            p.setInt(1, priority);
            p.setLong(2, id);
            p.executeUpdate();
        }
    }

    static void upsertMember(Connection c, long id, UUID player, Role role) throws SQLException {
        try (PreparedStatement p = c.prepareStatement(
                "INSERT INTO region_members (region_id, subject_type, subject_id, role) VALUES (?, ?, ?, ?)"
                        + " ON CONFLICT (region_id, subject_type, subject_id) DO UPDATE SET role = excluded.role")) {
            p.setLong(1, id);
            p.setString(2, PLAYER);
            p.setString(3, player.toString());
            p.setString(4, role.key());
            p.executeUpdate();
        }
    }

    static void deleteMember(Connection c, long id, UUID player) throws SQLException {
        try (PreparedStatement p = c.prepareStatement(
                "DELETE FROM region_members WHERE region_id = ? AND subject_type = ? AND subject_id = ?")) {
            p.setLong(1, id);
            p.setString(2, PLAYER);
            p.setString(3, player.toString());
            p.executeUpdate();
        }
    }

    static void upsertFlag(Connection c, long id, Flag flag, FlagValue value) throws SQLException {
        try (PreparedStatement p = c.prepareStatement(
                "INSERT INTO region_flags (region_id, flag, value, audience) VALUES (?, ?, ?, ?)"
                        + " ON CONFLICT (region_id, flag) DO UPDATE SET value = excluded.value, audience = excluded.audience")) {
            p.setLong(1, id);
            p.setString(2, flag.key());
            p.setString(3, value.allow() ? "allow" : "deny");
            p.setString(4, value.audience().key());
            p.executeUpdate();
        }
    }

    static void deleteFlag(Connection c, long id, Flag flag) throws SQLException {
        try (PreparedStatement p = c.prepareStatement("DELETE FROM region_flags WHERE region_id = ? AND flag = ?")) {
            p.setLong(1, id);
            p.setString(2, flag.key());
            p.executeUpdate();
        }
    }

    static void audit(Connection c, String actorType, String actorId, String action, String target, String result)
            throws SQLException {
        try (PreparedStatement p = c.prepareStatement(
                "INSERT INTO audit_log (at, actor_type, actor_id, action, target, result) VALUES (?, ?, ?, ?, ?, ?)")) {
            p.setLong(1, System.currentTimeMillis());
            p.setString(2, actorType);
            p.setString(3, actorId);
            p.setString(4, action);
            p.setString(5, target);
            p.setString(6, result);
            p.executeUpdate();
        }
    }

    private static void update(Connection c, String sql, long id) throws SQLException {
        try (PreparedStatement p = c.prepareStatement(sql)) {
            p.setLong(1, id);
            p.executeUpdate();
        }
    }
}
