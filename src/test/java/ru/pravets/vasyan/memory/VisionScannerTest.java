package ru.pravets.vasyan.memory;

import ru.pravets.vasyan.entity.VasyanEntity;
import ru.pravets.vasyan.testutil.AbstractMinecraftTest;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Vision rules: logs are visible even when surrounded, but ores must have an
 * exposed face so the bot does not x-ray through the ground. Leaves count as
 * exposed for ores (an ore face touching leaves is legitimately visible).
 *
 * <p>The no-LOS nearby scan for ores ({@link VisionScanner#findNearbyExposedBlocks})
 * adds a standable-approach requirement on top of the exposed-face rule: the
 * bot must be able to physically stand next to the exposed face, so ore under
 * a one-block floor (no headroom) stays hidden.</p>
 */
class VisionScannerTest extends AbstractMinecraftTest {

    private static Level levelWithNeighbors(BlockPos... exposedNeighbors) {
        Level level = mock(Level.class);
        BlockState stone = mock(BlockState.class);
        when(stone.isSolid()).thenReturn(true);
        when(stone.getBlock()).thenReturn(Blocks.STONE);
        when(level.getBlockState(any())).thenReturn(stone);
        for (BlockPos neighbor : exposedNeighbors) {
            BlockState air = mock(BlockState.class);
            when(air.isSolid()).thenReturn(false);
            when(air.getBlock()).thenReturn(Blocks.AIR);
            when(level.getBlockState(neighbor)).thenReturn(air);
        }
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
        BlockState stone = mock(BlockState.class);
        when(stone.isSolid()).thenReturn(true);
        when(stone.getBlock()).thenReturn(Blocks.STONE);
        when(level.getBlockState(any())).thenReturn(stone);
        BlockState leaves = mock(BlockState.class);
        when(leaves.isSolid()).thenReturn(true);
        when(leaves.getBlock()).thenReturn(Blocks.OAK_LEAVES);
        when(level.getBlockState(ore.above())).thenReturn(leaves);
        assertTrue(VisionScanner.isExposedForMining(level, ore, Blocks.COAL_ORE),
            "leaves are transparent, so an ore touching leaves is visible");
    }

    // ---- nearby exposed scan (no-LOS ore discovery with anti-xray filter) ----

    private static BlockState solid(net.minecraft.world.level.block.Block block) {
        BlockState state = mock(BlockState.class);
        when(state.isSolid()).thenReturn(true);
        when(state.getBlock()).thenReturn(block);
        return state;
    }

    private static BlockState open() {
        BlockState state = mock(BlockState.class);
        when(state.isSolid()).thenReturn(false);
        when(state.getBlock()).thenReturn(Blocks.AIR);
        return state;
    }

    private static VasyanEntity vasyanAt(Level level, BlockPos pos) {
        VasyanEntity vasyan = mock(VasyanEntity.class);
        when(vasyan.level()).thenReturn(level);
        when(vasyan.blockPosition()).thenReturn(pos);
        return vasyan;
    }

    @Test
    void nearbyExposedScanFindsOreAroundATerrainLip() {
        // Coal 4 blocks east, its east face exposed to an open cell with
        // headroom - but the bot stands west of a ridge, so the eye ray never
        // reaches the block center. The exposed-face nearby scan finds it.
        BlockPos center = new BlockPos(0, 64, 0);
        BlockPos ore = new BlockPos(4, 64, 0);
        Level level = mock(Level.class);
        when(level.getBlockState(any())).thenReturn(solid(Blocks.STONE));
        when(level.getBlockState(ore)).thenReturn(solid(Blocks.COAL_ORE));
        when(level.getBlockState(ore.east())).thenReturn(open());
        when(level.getBlockState(ore.east().above())).thenReturn(open());

        List<BlockPos> found = VisionScanner.findNearbyExposedBlocks(
            vasyanAt(level, center), 5, Set.of(Blocks.COAL_ORE));
        assertTrue(found.contains(ore),
            "exposed ore with a standable approach cell must be discoverable without LOS");
    }

    @Test
    void nearbyExposedScanIgnoresFullyBuriedOre() {
        BlockPos center = new BlockPos(0, 64, 0);
        BlockPos ore = new BlockPos(3, 64, 0);
        Level level = mock(Level.class);
        when(level.getBlockState(any())).thenReturn(solid(Blocks.STONE));
        when(level.getBlockState(ore)).thenReturn(solid(Blocks.COAL_ORE));

        List<BlockPos> found = VisionScanner.findNearbyExposedBlocks(
            vasyanAt(level, center), 5, Set.of(Blocks.COAL_ORE));
        assertTrue(found.isEmpty(), "buried ore must stay invisible (anti-xray)");
    }

    @Test
    void nearbyExposedScanRejectsExposedOreWithoutStandableApproach() {
        // Exposed, but every open neighbor has a solid block above it (e.g.
        // under the one-block floor of a floating platform): the bot can never
        // stand there, so the ore must not become a gather target.
        BlockPos center = new BlockPos(0, 64, 0);
        BlockPos ore = new BlockPos(3, 64, 0);
        Level level = mock(Level.class);
        when(level.getBlockState(any())).thenReturn(solid(Blocks.STONE));
        when(level.getBlockState(ore)).thenReturn(solid(Blocks.COAL_ORE));
        // Open cells around the ore, but no headroom anywhere.
        when(level.getBlockState(ore.east())).thenReturn(open());
        when(level.getBlockState(ore.below())).thenReturn(open());

        List<BlockPos> found = VisionScanner.findNearbyExposedBlocks(
            vasyanAt(level, center), 5, Set.of(Blocks.COAL_ORE));
        assertTrue(found.isEmpty(),
            "exposed but unreachable ore (no headroom) must not be a target");
    }
}
