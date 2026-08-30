package ru.pravets.vasyan.memory;

import ru.pravets.vasyan.entity.VasyanEntity;
import ru.pravets.vasyan.testutil.AbstractMinecraftTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Vision rules: logs are visible even when surrounded, but ores must have an
 * exposed face so the bot does not x-ray through the ground. Leaves count as
 * exposed for ores (an ore face touching leaves is legitimately visible).
 *
 * <p>The no-LOS nearby scan for ores ({@link VisionScanner#findNearbyExposedBlocks})
 * adds a passable-approach requirement on top of the exposed-face rule: the
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
        // Mockito forbids creating/stubbing mocks inside thenReturn(...)
        // (UnfinishedStubbingException) - precompute the states first.
        BlockState stone = solid(Blocks.STONE);
        BlockState coal = solid(Blocks.COAL_ORE);
        BlockState airFace = open();
        BlockState airHeadroom = open();
        Level level = mock(Level.class);
        when(level.hasChunkAt(any())).thenReturn(true);
        when(level.getBlockState(any())).thenReturn(stone);
        when(level.getBlockState(ore)).thenReturn(coal);
        when(level.getBlockState(ore.east())).thenReturn(airFace);
        when(level.getBlockState(ore.east().above())).thenReturn(airHeadroom);

        List<BlockPos> found = VisionScanner.findNearbyExposedBlocks(
            vasyanAt(level, center), 5, Set.of(Blocks.COAL_ORE));
        assertTrue(found.contains(ore),
            "exposed ore with a passable approach cell must be discoverable without LOS");
    }

    @Test
    void nearbyExposedScanIgnoresFullyBuriedOre() {
        BlockPos center = new BlockPos(0, 64, 0);
        BlockPos ore = new BlockPos(3, 64, 0);
        BlockState stone = solid(Blocks.STONE);
        BlockState coal = solid(Blocks.COAL_ORE);
        Level level = mock(Level.class);
        when(level.hasChunkAt(any())).thenReturn(true);
        when(level.getBlockState(any())).thenReturn(stone);
        when(level.getBlockState(ore)).thenReturn(coal);

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
        BlockState stone = solid(Blocks.STONE);
        BlockState coal = solid(Blocks.COAL_ORE);
        BlockState airEast = open();
        BlockState airBelow = open();
        Level level = mock(Level.class);
        when(level.hasChunkAt(any())).thenReturn(true);
        when(level.getBlockState(any())).thenReturn(stone);
        when(level.getBlockState(ore)).thenReturn(coal);
        // Open cells around the ore, but no headroom anywhere.
        when(level.getBlockState(ore.east())).thenReturn(airEast);
        when(level.getBlockState(ore.below())).thenReturn(airBelow);

        List<BlockPos> found = VisionScanner.findNearbyExposedBlocks(
            vasyanAt(level, center), 5, Set.of(Blocks.COAL_ORE));
        assertTrue(found.isEmpty(),
            "exposed but unreachable ore (no headroom) must not be a target");
    }

    // ---- vertical scan coverage (Y is always step 1) ----

    @Test
    void coarseScanNeverSkipsYLayers() {
        // Ore on an ODD dy layer (+3) with a horizontal step of 2: the old
        // cube grid (dy += step) skipped whole Y layers and missed exactly
        // these blocks; the vertical axis must always be scanned at step 1.
        BlockPos center = new BlockPos(0, 64, 0);
        BlockPos ore = new BlockPos(4, 67, 0); // dx=4 (on grid), dy=+3 (between layers)
        BlockState stone = solid(Blocks.STONE);
        BlockState coal = solid(Blocks.COAL_ORE);
        Level level = mock(Level.class);
        when(level.hasChunkAt(any())).thenReturn(true);
        when(level.getBlockState(any())).thenReturn(stone);
        when(level.getBlockState(ore)).thenReturn(coal);

        var candidates = new HashMap<Block, Set<BlockPos>>();
        VisionScanner.collectCandidates(level, center, 8, 2, candidates);

        assertTrue(candidates.getOrDefault(Blocks.COAL_ORE, Set.of()).contains(ore),
            "a coarse horizontal step must never skip a Y layer (cliff-face vein blindness)");
    }

    @Test
    void budgetGuardFitsXZAreaTimesFullY() {
        // radius 32, configured step 2: old cube estimate (65^3 = 274k) doubled
        // the step to 4; the XZ-area x full-Y estimate (33^2 * 65 = 71k) fits.
        assertEquals(2, VisionScanner.effectiveStep(32, 2),
            "radius 32 must keep step 2 now that the Y axis is always scanned fully");
        // A huge radius still doubles until the estimate fits the 100k budget.
        assertTrue(VisionScanner.effectiveStep(64, 1) >= 2,
            "the budget guard still grows the step when the estimate exceeds 100k");
    }

    @Test
    void scanTargetsVerticalColumnRespectsRequestedTargets() {
        // A non-INTERESTING target (stone) should not be polluted by COAL_ORE
        // that collectVerticalColumn added just because coal is "interesting".
        BlockPos center = new BlockPos(0, 64, 0);
        BlockPos coal = center.above();

        BlockState stone = solid(Blocks.STONE);
        BlockState coalOre = solid(Blocks.COAL_ORE);
        BlockState air = open();

        Level level = mock(Level.class);
        when(level.hasChunkAt(any())).thenReturn(true);
        when(level.getBlockState(any())).thenReturn(stone);
        when(level.getBlockState(center)).thenReturn(air);
        when(level.getBlockState(coal)).thenReturn(coalOre);
        when(level.clip(any())).thenReturn(BlockHitResult.miss(Vec3.ZERO, Direction.UP, BlockPos.ZERO));

        VasyanEntity vasyan = vasyanAt(level, center);
        when(vasyan.getEyePosition(anyFloat())).thenReturn(Vec3.atCenterOf(center).add(0, 1.5, 0));

        List<BlockPos> found = VisionScanner.findVisible(vasyan, Set.of(Blocks.STONE));

        assertFalse(found.contains(coal),
            "vertical column scan must not return blocks outside the requested target set");
    }
}
