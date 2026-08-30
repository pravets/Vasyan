package ru.pravets.vasyan.memory;

import ru.pravets.vasyan.test.McTestBootstrap;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure unit tests for {@link GlobalResourceMemory}.
 * Uses {@link BlockPos} and {@link Level} dimension keys.
 */
class GlobalResourceMemoryTest {

    private static ResourceKey<Level> OW;
    private static ResourceKey<Level> NETHER;

    @BeforeAll
    static void bootstrap() {
        McTestBootstrap.bootstrap();
        OW = Level.OVERWORLD;
        NETHER = Level.NETHER;
    }

    @BeforeEach
    void setUp() {
        GlobalResourceMemory.clear();
    }

    @Test
    void remembersEmptyStationForTheSameResource() {
        GlobalResourceMemory.rememberEmptyStation("coal", OW, new BlockPos(100, 64, 100), 0);
        assertTrue(GlobalResourceMemory.isEmptyStation("coal", OW, new BlockPos(100, 64, 100), 0, 8));
    }

    @Test
    void emptyStationBlocksNearbyStations() {
        GlobalResourceMemory.rememberEmptyStation("coal", OW, new BlockPos(100, 64, 100), 0);
        assertTrue(GlobalResourceMemory.isEmptyStation("coal", OW, new BlockPos(104, 70, 98), 0, 8));
        assertFalse(GlobalResourceMemory.isEmptyStation("coal", OW, new BlockPos(110, 64, 100), 0, 8));
    }

    @Test
    void emptyStationsAreIsolatedByResource() {
        GlobalResourceMemory.rememberEmptyStation("coal", OW, new BlockPos(100, 64, 100), 0);
        assertFalse(GlobalResourceMemory.isEmptyStation("iron", OW, new BlockPos(100, 64, 100), 0, 8));
    }

    @Test
    void unreachableClustersAreIsolatedByResource() {
        GlobalResourceMemory.rememberUnreachable("coal", OW, new BlockPos(200, 64, 200), 0);
        assertFalse(GlobalResourceMemory.isUnreachable("iron", OW, new BlockPos(202, 65, 199), 0, 8));
    }

    @Test
    void pruneRemovesStaleEntries() {
        GlobalResourceMemory.rememberEmptyStation("coal", OW, new BlockPos(100, 64, 100), 0);
        GlobalResourceMemory.prune(100_000, 1_000);
        assertFalse(GlobalResourceMemory.isEmptyStation("coal", OW, new BlockPos(100, 64, 100), 100_000, 8));
    }

    @Test
    void accessRefreshesLastUsedTick() {
        BlockPos p = new BlockPos(100, 64, 100);
        BlockPos p2 = new BlockPos(120, 64, 120);
        GlobalResourceMemory.rememberEmptyStation("coal", OW, p, 0);
        GlobalResourceMemory.rememberUnreachable("coal", OW, p2, 5_000);
        GlobalResourceMemory.prune(6_000, 2_000);
        assertTrue(GlobalResourceMemory.isEmptyStation("coal", OW, p, 6_000, 16));
    }

    @Test
    void dimensionsAreIsolated() {
        GlobalResourceMemory.rememberEmptyStation("coal", OW, new BlockPos(100, 64, 100), 0);
        assertFalse(GlobalResourceMemory.isEmptyStation("coal", NETHER, new BlockPos(100, 64, 100), 0, 8));

        GlobalResourceMemory.rememberUnreachable("coal", OW, new BlockPos(200, 64, 200), 0);
        assertFalse(GlobalResourceMemory.isUnreachable("coal", NETHER, new BlockPos(202, 65, 199), 0, 8));
    }

    @Test
    void unreachableClustersAreSharedAcrossResources() {
        GlobalResourceMemory.rememberUnreachable("coal", OW, new BlockPos(200, 64, 200), 0);
        assertTrue(GlobalResourceMemory.isUnreachable("coal", OW, new BlockPos(202, 65, 199), 0, 8));
    }

    @Test
    void clearResetsEverything() {
        GlobalResourceMemory.rememberEmptyStation("coal", OW, new BlockPos(100, 64, 100), 0);
        GlobalResourceMemory.rememberUnreachable("iron", OW, new BlockPos(200, 64, 200), 0);
        GlobalResourceMemory.clear();
        assertFalse(GlobalResourceMemory.isEmptyStation("coal", OW, new BlockPos(100, 64, 100), 0, 8));
        assertFalse(GlobalResourceMemory.isUnreachable("iron", OW, new BlockPos(200, 64, 200), 0, 8));
    }
}
