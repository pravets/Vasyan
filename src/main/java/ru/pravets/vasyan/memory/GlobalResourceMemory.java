package ru.pravets.vasyan.memory;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Shared memory of empty/unreachable resource zones across all Vasyans.
 *
 * <p>The cache is keyed by the requested resource name (e.g. {@code "coal"}, {@code "wood"})
 * and the current dimension so memory from one world does not pollute another.
 * Entries expire after a TTL so the cache does not grow forever and stale data is forgotten
 * when the world changes.</p>
 *
 * <p>Both empty stations and unreachable clusters are stored in insertion-ordered
 * {@link LinkedHashSet}s; when a cap is exceeded the oldest entries (the head of the
 * iterator) are removed first.</p>
 */
public final class GlobalResourceMemory {

    private record ZoneMemory(long lastUsedTick, Set<BlockPos> emptyStations,
                              Set<BlockPos> unreachableClusters) {}

    /** resource|dimension key -> memory for that resource in that dimension */
    private static final Map<String, ZoneMemory> MEMORY = new ConcurrentHashMap<>();

    private static final int MAX_EMPTY_STATIONS = 64;
    private static final int MAX_CLUSTERS = 64;

    private GlobalResourceMemory() {}

    /**
     * Marks a look-out station as empty for the given resource: the bot searched here and
     * found nothing reachable.
     */
    public static void rememberEmptyStation(String resource, ResourceKey<Level> dimension,
                                              BlockPos station, long currentTick) {
        ZoneMemory mem = getOrCreate(resource, dimension, currentTick);
        mem.emptyStations.add(station.immutable());
        if (mem.emptyStations.size() > MAX_EMPTY_STATIONS) {
            removeOldest(mem.emptyStations, MAX_EMPTY_STATIONS / 2);
        }
    }

    /**
     * Marks a resource cluster as unreachable (cliff, pit, lava, etc.) so other bots do not
     * keep walking into the same dead end.
     */
    public static void rememberUnreachable(String resource, ResourceKey<Level> dimension,
                                             BlockPos center, long currentTick) {
        ZoneMemory mem = getOrCreate(resource, dimension, currentTick);
        mem.unreachableClusters.add(center.immutable());
        if (mem.unreachableClusters.size() > MAX_CLUSTERS) {
            removeOldest(mem.unreachableClusters, MAX_CLUSTERS / 2);
        }
    }

    /** Whether the given station is inside a remembered empty zone. */
    public static boolean isEmptyStation(String resource, ResourceKey<Level> dimension,
                                         BlockPos station, long currentTick, int radius) {
        ZoneMemory mem = MEMORY.get(key(resource, dimension));
        if (mem == null || isExpired(mem, currentTick)) {
            return false;
        }
        long radiusSq = (long) radius * radius;
        synchronized (mem.emptyStations) {
            for (BlockPos p : mem.emptyStations) {
                if (p.distSqr(station) <= radiusSq) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Whether the given position is inside a remembered unreachable cluster. */
    public static boolean isUnreachable(String resource, ResourceKey<Level> dimension,
                                        BlockPos pos, long currentTick, int radius) {
        ZoneMemory mem = MEMORY.get(key(resource, dimension));
        if (mem == null || isExpired(mem, currentTick)) {
            return false;
        }
        long radiusSq = (long) radius * radius;
        synchronized (mem.unreachableClusters) {
            for (BlockPos p : mem.unreachableClusters) {
                if (p.distSqr(pos) <= radiusSq) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Drops expired entries so the cache does not grow indefinitely. Call when a gather
     * action starts.
     */
    public static void prune(long currentTick, long ttlTicks) {
        Iterator<Map.Entry<String, ZoneMemory>> it = MEMORY.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, ZoneMemory> entry = it.next();
            if (currentTick - entry.getValue().lastUsedTick > ttlTicks) {
                it.remove();
            }
        }
    }

    /** Clears all cached memory. Useful on server stop and between tests. */
    public static void clear() {
        MEMORY.clear();
    }

    private static String key(String resource, ResourceKey<Level> dimension) {
        return resource + "|" + dimension.location();
    }

    private static ZoneMemory getOrCreate(String resource, ResourceKey<Level> dimension,
                                          long currentTick) {
        String key = key(resource, dimension);
        ZoneMemory mem = MEMORY.get(key);
        if (mem == null || isExpired(mem, currentTick)) {
            mem = new ZoneMemory(currentTick,
                Collections.synchronizedSet(new LinkedHashSet<>()),
                Collections.synchronizedSet(new LinkedHashSet<>()));
            MEMORY.put(key, mem);
            return mem;
        }
        // refresh last used tick on access and write it back so TTL counts from last use
        ZoneMemory refreshed = new ZoneMemory(currentTick, mem.emptyStations, mem.unreachableClusters);
        MEMORY.put(key, refreshed);
        return refreshed;
    }

    private static boolean isExpired(ZoneMemory mem, long currentTick) {
        return currentTick < mem.lastUsedTick; // time went backwards (dimension change)
    }

    private static void removeOldest(Set<BlockPos> set, int removeCount) {
        synchronized (set) {
            Iterator<BlockPos> it = set.iterator();
            int removed = 0;
            while (it.hasNext() && removed < removeCount) {
                it.next();
                it.remove();
                removed++;
            }
        }
    }
}
