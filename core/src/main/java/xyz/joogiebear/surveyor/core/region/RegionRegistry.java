package xyz.joogiebear.surveyor.core.region;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Holds every region. Readers take an immutable snapshot and never block, which matters because
 * Folia calls them from many region threads at once. Writers are serialized and rebuild the
 * snapshot, which is fine as regions change rarely and are read constantly.
 */
public final class RegionRegistry {

    private volatile Snapshot snapshot = new Snapshot(Map.of());

    public Snapshot snapshot() {
        return snapshot;
    }

    public synchronized void replaceAll(Collection<Region> regions) {
        Map<Long, Region> byId = new HashMap<>();
        regions.forEach(region -> byId.put(region.id(), region));
        snapshot = new Snapshot(byId);
    }

    public synchronized void put(Region region) {
        Map<Long, Region> byId = new HashMap<>(snapshot.byId);
        byId.put(region.id(), region);
        snapshot = new Snapshot(byId);
    }

    public synchronized void remove(long id) {
        Map<Long, Region> byId = new HashMap<>(snapshot.byId);
        byId.remove(id);
        snapshot = new Snapshot(byId);
    }

    public static final class Snapshot {

        private final Map<Long, Region> byId;
        private final Map<String, RegionIndex> indexes = new HashMap<>();
        private final Map<String, Map<String, Region>> names = new HashMap<>();

        private Snapshot(Map<Long, Region> byId) {
            this.byId = byId;
            Map<String, List<Region>> perWorld = byId.values().stream()
                    .collect(Collectors.groupingBy(Region::world));
            perWorld.forEach((world, regions) -> {
                indexes.put(world, new RegionIndex(regions));
                Map<String, Region> byName = new HashMap<>();
                regions.forEach(region -> byName.put(nameKey(region.name()), region));
                names.put(world, byName);
            });
        }

        public int size() {
            return byId.size();
        }

        public Region get(long id) {
            return byId.get(id);
        }

        public Region find(String world, String name) {
            return names.getOrDefault(world, Map.of()).get(nameKey(name));
        }

        public List<Region> at(String world, int x, int y, int z) {
            RegionIndex index = indexes.get(world);
            return index == null ? List.of() : index.at(x, y, z);
        }

        /** Regions of one world, or of every world when {@code world} is null, ordered by name. */
        public List<Region> list(String world) {
            List<Region> regions = new ArrayList<>();
            for (Region region : byId.values()) {
                if (world == null || region.world().equals(world)) {
                    regions.add(region);
                }
            }
            regions.sort(Comparator.comparing(Region::world).thenComparing(r -> nameKey(r.name())));
            return regions;
        }

        private static String nameKey(String name) {
            return name.toLowerCase(Locale.ROOT);
        }
    }
}
