package xyz.joogiebear.surveyor.paper.storage;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import xyz.joogiebear.surveyor.core.region.Cuboid;
import xyz.joogiebear.surveyor.core.region.Flag;
import xyz.joogiebear.surveyor.core.region.FlagValue;
import xyz.joogiebear.surveyor.core.region.Region;
import xyz.joogiebear.surveyor.core.region.RegionRegistry;
import xyz.joogiebear.surveyor.core.region.Role;

/**
 * Writes regions to the database and the in-memory registry together. Every change runs on the
 * database thread, so changes are applied one at a time and read-modify-write never races. The
 * registry is only updated after the row is saved.
 */
public final class RegionStore {

    private final Database database;
    private final RegionRegistry registry = new RegionRegistry();
    private volatile boolean loaded;

    public RegionStore(Database database) {
        this.database = database;
    }

    public RegionRegistry.Snapshot snapshot() {
        return registry.snapshot();
    }

    /** False until the first load finishes, and stays false if it failed. */
    public boolean loaded() {
        return loaded;
    }

    public CompletableFuture<Integer> load() {
        return database.submit(RegionRepository::loadAll).thenApply(regions -> {
            registry.replaceAll(regions);
            loaded = true;
            return regions.size();
        });
    }

    public CompletableFuture<Region> create(String world, String name, Cuboid bounds, UUID owner) {
        return database.submit(c -> {
            if (registry.snapshot().find(world, name) != null) {
                throw new RegionException("region-exists");
            }
            long id = RegionRepository.insert(c, world, name, bounds, owner);
            Region region = new Region(id, world, name, bounds, 0,
                    owner == null ? Map.of() : Map.of(owner, Role.OWNER), Map.of());
            registry.put(region);
            return region;
        });
    }

    public CompletableFuture<Region> delete(long id) {
        return database.submit(c -> {
            Region region = require(id);
            RegionRepository.delete(c, id);
            registry.remove(id);
            return region;
        });
    }

    public CompletableFuture<Region> setPriority(long id, int priority) {
        return change(id, (c, region) -> {
            RegionRepository.setPriority(c, id, priority);
            return region.withPriority(priority);
        });
    }

    public CompletableFuture<Region> setMember(long id, UUID player, Role role) {
        return change(id, (c, region) -> {
            RegionRepository.upsertMember(c, id, player, role);
            return region.withMember(player, role);
        });
    }

    public CompletableFuture<Region> removeMember(long id, UUID player) {
        return change(id, (c, region) -> {
            if (region.roleOf(player) == null) {
                throw new RegionException("not-a-member");
            }
            RegionRepository.deleteMember(c, id, player);
            return region.withoutMember(player);
        });
    }

    public CompletableFuture<Region> setFlag(long id, Flag flag, FlagValue value) {
        return change(id, (c, region) -> {
            RegionRepository.upsertFlag(c, id, flag, value);
            return region.withFlag(flag, value);
        });
    }

    public CompletableFuture<Region> clearFlag(long id, Flag flag) {
        return change(id, (c, region) -> {
            RegionRepository.deleteFlag(c, id, flag);
            return region.withoutFlag(flag);
        });
    }

    /** Queues an audit row. Failures are reported to the caller's handler, never thrown. */
    public CompletableFuture<Void> audit(String actorType, String actorId, String action, String target, String result) {
        return database.submit(c -> {
            RegionRepository.audit(c, actorType, actorId, action, target, result);
            return null;
        });
    }

    private CompletableFuture<Region> change(long id, Change change) {
        return database.submit(c -> {
            Region next = change.apply(c, require(id));
            registry.put(next);
            return next;
        });
    }

    private Region require(long id) {
        Region region = registry.snapshot().get(id);
        if (region == null) {
            throw new RegionException("region-gone");
        }
        return region;
    }

    @FunctionalInterface
    private interface Change {
        Region apply(Connection connection, Region current) throws SQLException;
    }
}
