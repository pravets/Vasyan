package ru.pravets.vasyan.memory;

import ru.pravets.vasyan.testutil.AbstractMinecraftTest;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure unit tests for {@link GlobalResourceMemory}.
 * Uses {@link BlockPos} and {@link Level} dimension keys.
 */
class GlobalResourceMemoryTest extends AbstractMinecraftTest {

    @BeforeEach
    void setUp() {
        GlobalResourceMemory.clear();
    }

    @Test
    void remembersEmptyStationForTheSameResource() {
        GlobalResourceMemory.rememberEmptyStation("coal", Level.OVERWORLD, new BlockPos(100, 64, 100), 0);
        assertTrue(GlobalResourceMemory.isEmptyStation("coal", Level.OVERWORLD, new BlockPos(100, 64, 100), 0, 8));
    }

    @Test
    void emptyStationBlocksNearbyStations() {
        GlobalResourceMemory.rememberEmptyStation("coal", Level.OVERWORLD, new BlockPos(100, 64, 100), 0);
        assertTrue(GlobalResourceMemory.isEmptyStation("coal", Level.OVERWORLD, new BlockPos(104, 70, 98), 0, 8));
        assertFalse(GlobalResourceMemory.isEmptyStation("coal", Level.OVERWORLD, new BlockPos(110, 64, 100), 0, 8));
    }

    @Test
    void emptyStationsAreIsolatedByResource() {
        GlobalResourceMemory.rememberEmptyStation("coal", Level.OVERWORLD, new BlockPos(100, 64, 100), 0);
        assertFalse(GlobalResourceMemory.isEmptyStation("iron", Level.OVERWORLD, new BlockPos(100, 64, 100), 0, 8));
    }

    @Test
    void unreachableClustersAreIsolatedByResource() {
        GlobalResourceMemory.rememberUnreachable("coal", Level.OVERWORLD, new BlockPos(200, 64, 200), 0);
        assertFalse(GlobalResourceMemory.isUnreachable("iron", Level.OVERWORLD, new BlockPos(202, 65, 199), 0, 8));
    }

    @Test
    void pruneRemovesStaleEntries() {
        GlobalResourceMemory.rememberEmptyStation("coal", Level.OVERWORLD, new BlockPos(100, 64, 100), 0);
        GlobalResourceMemory.prune(100_000, 1_000);
        assertFalse(GlobalResourceMemory.isEmptyStation("coal", Level.OVERWORLD, new BlockPos(100, 64, 100), 100_000, 8));
    }

    @Test
    void accessRefreshesLastUsedTick() {
        BlockPos p = new BlockPos(100, 64, 100);
        BlockPos p2 = new BlockPos(120, 64, 120);
        GlobalResourceMemory.rememberEmptyStation("coal", Level.OVERWORLD, p, 0);
        GlobalResourceMemory.rememberUnreachable("coal", Level.OVERWORLD, p2, 5_000);
        GlobalResourceMemory.prune(6_000, 2_000);
        assertTrue(GlobalResourceMemory.isEmptyStation("coal", Level.OVERWORLD, p, 6_000, 16));
    }

    @Test
    void dimensionsAreIsolated() {
        GlobalResourceMemory.rememberEmptyStation("coal", Level.OVERWORLD, new BlockPos(100, 64, 100), 0);
        assertFalse(GlobalResourceMemory.isEmptyStation("coal", Level.NETHER, new BlockPos(100, 64, 100), 0, 8));

        GlobalResourceMemory.rememberUnreachable("coal", Level.OVERWORLD, new BlockPos(200, 64, 200), 0);
        assertFalse(GlobalResourceMemory.isUnreachable("coal", Level.NETHER, new BlockPos(202, 65, 199), 0, 8));
    }

    @Test
    void unreachableClustersAreSharedAcrossResources() {
        GlobalResourceMemory.rememberUnreachable("coal", Level.OVERWORLD, new BlockPos(200, 64, 200), 0);
        assertTrue(GlobalResourceMemory.isUnreachable("coal", Level.OVERWORLD, new BlockPos(202, 65, 199), 0, 8));
    }

    @Test
    void clearResetsEverything() {
        GlobalResourceMemory.rememberEmptyStation("coal", Level.OVERWORLD, new BlockPos(100, 64, 100), 0);
        GlobalResourceMemory.rememberUnreachable("iron", Level.OVERWORLD, new BlockPos(200, 64, 200), 0);
        GlobalResourceMemory.clear();
        assertFalse(GlobalResourceMemory.isEmptyStation("coal", Level.OVERWORLD, new BlockPos(100, 64, 100), 0, 8));
        assertFalse(GlobalResourceMemory.isUnreachable("iron", Level.OVERWORLD, new BlockPos(200, 64, 200), 0, 8));
    }
}
