package ru.pravets.vasyan.memory;

import net.minecraft.core.BlockPos;

import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Shared memory of empty/unreachable resource zones across all Vasyans.
 *
 * <p>The cache is keyed by the requested resource name (e.g. {@code "coal"}, {@code "wood"})
 * so memory from one bot helps another when they are asked to gather the same thing.
 * Entries expire after a TTL so the cache does not grow forever and stale data is forgotten
 * when the world changes.</p>
 */
public final class GlobalResourceMemory {

    private record ZoneMemory(long lastUsedTick, Set<BlockPos> emptyStations,
                              Set<BlockPos> unreachableClusters) {}

    /** resource key -> memory for that resource */
    private static final Map<String, ZoneMemory> MEMORY = new ConcurrentHashMap<>();

    private static final int MAX_EMPTY_STATIONS = 64;
    private static final int MAX_CLUSTERS = 64;

    private GlobalResourceMemory() {}

    /**
     * Marks a look-out station as empty for the given resource: the bot searched here and
     * found nothing reachable.
     */
    public static void rememberEmptyStation(String resource, BlockPos station, long currentTick) {
        ZoneMemory mem = getOrCreate(resource, currentTick);
        mem.emptyStations.add(station.immutable());
        if (mem.emptyStations.size() > MAX_EMPTY_STATIONS) {
            removeOldest(mem.emptyStations, MAX_EMPTY_STATIONS / 2);
        }
    }

    /**
     * Marks a resource cluster as unreachable (cliff, pit, lava, etc.) so other bots do not
     * keep walking into the same dead end.
     */
    public static void rememberUnreachable(String resource, BlockPos center, long currentTick) {
        ZoneMemory mem = getOrCreate(resource, currentTick);
        mem.unreachableClusters.add(center.immutable());
        if (mem.unreachableClusters.size() > MAX_CLUSTERS) {
            removeOldest(mem.unreachableClusters, MAX_CLUSTERS / 2);
        }
    }

    /** Whether the given station is inside a remembered empty zone. */
    public static boolean isEmptyStation(String resource, BlockPos station, long currentTick,
                                         int radius) {
        ZoneMemory mem = MEMORY.get(resource);
        if (mem == null || isExpired(mem, currentTick)) {
            return false;
        }
        long radiusSq = (long) radius * radius;
        for (BlockPos p : mem.emptyStations) {
            if (p.distSqr(station) <= radiusSq) {
                return true;
            }
        }
        return false;
    }

    /** Whether the given position is inside a remembered unreachable cluster. */
    public static boolean isUnreachable(String resource, BlockPos pos, long currentTick,
                                        int radius) {
        ZoneMemory mem = MEMORY.get(resource);
        if (mem == null || isExpired(mem, currentTick)) {
            return false;
        }
        long radiusSq = (long) radius * radius;
        for (BlockPos p : mem.unreachableClusters) {
            if (p.distSqr(pos) <= radiusSq) {
                return true;
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

    private static ZoneMemory getOrCreate(String resource, long currentTick) {
        ZoneMemory mem = MEMORY.get(resource);
        if (mem == null || isExpired(mem, currentTick)) {
            mem = new ZoneMemory(currentTick,
                Collections.synchronizedSet(new HashSet<>()),
                Collections.synchronizedSet(new HashSet<>()));
            MEMORY.put(resource, mem);
        }
        // refresh last used tick on access
        return new ZoneMemory(currentTick, mem.emptyStations, mem.unreachableClusters);
    }

    private static boolean isExpired(ZoneMemory mem, long currentTick) {
        return currentTick < mem.lastUsedTick; // time went backwards (dimension change)
    }

    private static void removeOldest(Set<BlockPos> set, int removeCount) {
        Iterator<BlockPos> it = set.iterator();
        int removed = 0;
        while (it.hasNext() && removed < removeCount) {
            it.next();
            it.remove();
            removed++;
        }
    }
}
