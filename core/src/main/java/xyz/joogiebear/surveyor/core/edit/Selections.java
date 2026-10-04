package xyz.joogiebear.surveyor.core.edit;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** One selection per actor per world. Safe to call from any thread. */
public final class Selections<K> {

    private final Map<K, Map<String, Selection>> byActor = new ConcurrentHashMap<>();

    public Selection get(K actor, String world) {
        return byActor.getOrDefault(actor, Map.of()).getOrDefault(world, Selection.EMPTY);
    }

    public Selection setPos1(K actor, String world, BlockPos pos) {
        return byActor.computeIfAbsent(actor, k -> new ConcurrentHashMap<>())
                .compute(world, (w, current) -> (current == null ? Selection.EMPTY : current).withPos1(pos));
    }

    public Selection setPos2(K actor, String world, BlockPos pos) {
        return byActor.computeIfAbsent(actor, k -> new ConcurrentHashMap<>())
                .compute(world, (w, current) -> (current == null ? Selection.EMPTY : current).withPos2(pos));
    }

    public void clear(K actor, String world) {
        Map<String, Selection> worlds = byActor.get(actor);
        if (worlds != null) {
            worlds.remove(world);
        }
    }

    public void forget(K actor) {
        byActor.remove(actor);
    }
}
