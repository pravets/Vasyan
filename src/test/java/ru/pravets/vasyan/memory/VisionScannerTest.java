package ru.pravets.vasyan.memory;

import ru.pravets.vasyan.testutil.AbstractMinecraftTest;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Vision rules: logs are visible even when surrounded, but ores must have an
 * exposed face so the bot does not x-ray through the ground. Leaves count as
 * exposed for ores (an ore face touching leaves is legitimately visible).
 */
class VisionScannerTest extends AbstractMinecraftTest {

    private static Level levelWithNeighbors(BlockPos target, BlockPos... exposedNeighbors) {
        Level level = mock(Level.class);
        when(level.getBlockState(any())).thenReturn(Blocks.STONE.defaultBlockState());
        for (BlockPos neighbor : exposedNeighbors) {
            when(level.getBlockState(neighbor)).thenReturn(Blocks.AIR.defaultBlockState());
        }
        // target itself stays stone by default so non-logs are treated as surrounded
        return level;
    }

    @Test
    void fullyBuriedOreIsNotExposedForMining() {
        BlockPos ore = new BlockPos(0, 64, 0);
        Level level = levelWithNeighbors(ore);
        assertFalse(VisionScanner.isExposedForMining(level, ore, Blocks.COAL_ORE),
            "ore with no air/leaves neighbor must be hidden from vision");
    }

    @Test
    void oreWithAirNeighborIsExposedForMining() {
        BlockPos ore = new BlockPos(0, 64, 0);
        Level level = levelWithNeighbors(ore, ore.above());
        assertTrue(VisionScanner.isExposedForMining(level, ore, Blocks.COAL_ORE),
            "ore touching air on any face is visible");
    }

    @Test
    void oreNextToLeavesIsExposedForMining() {
        BlockPos ore = new BlockPos(0, 64, 0);
        Level level = mock(Level.class);
        when(level.getBlockState(any())).thenReturn(Blocks.STONE.defaultBlockState());
        when(level.getBlockState(ore.above())).thenReturn(Blocks.OAK_LEAVES.defaultBlockState());
        assertTrue(VisionScanner.isExposedForMining(level, ore, Blocks.COAL_ORE),
            "leaves are transparent, so an ore touching leaves is visible");
    }

    @Test
    void logsDoNotRequireExposedFace() {
        BlockPos log = new BlockPos(0, 64, 0);
        Level level = mock(Level.class);
        when(level.getBlockState(any())).thenReturn(Blocks.STONE.defaultBlockState());
        assertTrue(VisionScanner.isExposedForMining(level, log, Blocks.OAK_LOG),
            "logs must be discoverable even when surrounded by leaves/stone");
    }
}
