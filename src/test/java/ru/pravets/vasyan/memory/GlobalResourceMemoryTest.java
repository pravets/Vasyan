package ru.pravets.vasyan.memory;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure unit tests for {@link GlobalResourceMemory}.
 * Only uses {@link BlockPos}, no Minecraft bootstrap.
 */
class GlobalResourceMemoryTest {

    @Test
    void remembersEmptyStationForTheSameResource() {
        GlobalResourceMemory.rememberEmptyStation("coal", new BlockPos(100, 64, 100), 0);
        assertTrue(GlobalResourceMemory.isEmptyStation("coal", new BlockPos(100, 64, 100), 0, 8));
    }

    @Test
    void emptyStationBlocksNearbyStations() {
        GlobalResourceMemory.rememberEmptyStation("coal", new BlockPos(100, 64, 100), 0);
        assertTrue(GlobalResourceMemory.isEmptyStation("coal", new BlockPos(104, 70, 98), 0, 8));
        assertFalse(GlobalResourceMemory.isEmptyStation("coal", new BlockPos(110, 64, 100), 0, 8));
    }

    @Test
    void differentResourcesDoNotShareMemory() {
        GlobalResourceMemory.rememberEmptyStation("coal", new BlockPos(100, 64, 100), 0);
        assertFalse(GlobalResourceMemory.isEmptyStation("iron", new BlockPos(100, 64, 100), 0, 8));
    }

    @Test
    void pruneRemovesStaleEntries() {
        GlobalResourceMemory.rememberEmptyStation("coal", new BlockPos(100, 64, 100), 0);
        GlobalResourceMemory.prune(100_000, 1_000);
        assertFalse(GlobalResourceMemory.isEmptyStation("coal", new BlockPos(100, 64, 100), 100_000, 8));
    }

    @Test
    void unreachableClustersAreSharedAcrossResources() {
        GlobalResourceMemory.rememberUnreachable("coal", new BlockPos(200, 64, 200), 0);
        assertTrue(GlobalResourceMemory.isUnreachable("coal", new BlockPos(202, 65, 199), 0, 8));
    }
}
