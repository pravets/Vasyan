package ru.pravets.vasyan.navigation;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.PathNavigationRegion;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.BlockPathTypes;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.level.pathfinder.Target;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import ru.pravets.vasyan.entity.VasyanEntity;
import ru.pravets.vasyan.entity.VasyanInventory;
import ru.pravets.vasyan.testutil.AbstractMinecraftTest;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Regression for CI Scenario K: a real-evaluator search over the wall/gap/cliff
 * course must find a route containing DIG, PLACE and PILLAR_UP edges. */
class ScenarioKSearchTest extends AbstractMinecraftTest {

    private static final BlockPos START = new BlockPos(126, 201, 40);
    private static final BlockPos TARGET = new BlockPos(171, 201, 40);

    private static Level level;
    private static Map<BlockPos, BlockState> world;
    private static VasyanEntity mob;

    @BeforeAll
    static void stubLevelAndMob() {
        for (Block block : BuiltInRegistries.BLOCK) {
            for (BlockState state : block.getStateDefinition().getPossibleStates()) {
                state.initCache();
            }
        }
        world = new HashMap<>();
        buildScenarioK(world);
        level = mock(Level.class);
        when(level.getBlockState(any(BlockPos.class)))
            .thenAnswer(invocation ->
                world.getOrDefault(invocation.getArgument(0), Blocks.AIR.defaultBlockState()));
        when(level.isOutsideBuildHeight(any(int.class)))
            .thenAnswer(invocation -> (int) invocation.getArgument(0) > 320
                || (int) invocation.getArgument(0) < -64);
        when(level.isOutsideBuildHeight(any(BlockPos.class)))
            .thenAnswer(invocation -> {
                BlockPos pos = invocation.getArgument(0);
                return pos.getY() > 320 || pos.getY() < -64;
            });
        mob = mock(VasyanEntity.class);
        VasyanInventory inventory = new VasyanInventory();
        inventory.addItem(new ItemStack(Blocks.DIRT.asItem(), 64));
        when(mob.getInventory()).thenReturn(inventory);
    }

    private static void buildScenarioK(Map<BlockPos, BlockState> w) {
        for (int x = 121; x <= 181; x++) {
            for (int z = 37; z <= 43; z++) {
                w.put(new BlockPos(x, 200, z), Blocks.SMOOTH_STONE.defaultBlockState());
            }
        }
        // Side walls y=201..204 plus the full roof y=205..206: nothing may walk
        // over the course, so the wall/gap/cliff below must be crossed directly.
        for (int x = 121; x <= 181; x++) {
            for (int y = 201; y <= 204; y++) {
                w.put(new BlockPos(x, y, 36), Blocks.SMOOTH_STONE.defaultBlockState());
                w.put(new BlockPos(x, y, 44), Blocks.SMOOTH_STONE.defaultBlockState());
            }
            for (int z = 36; z <= 44; z++) {
                for (int y = 205; y <= 206; y++) {
                    w.put(new BlockPos(x, y, z), Blocks.SMOOTH_STONE.defaultBlockState());
                }
            }
        }
        // (a) 4-block-high dirt wall x=135, y=201..204: tall enough that no
        // walk-over fits under the roof, so a DIG tunnel is the only way through.
        for (int z = 37; z <= 43; z++) {
            for (int y = 201; y <= 204; y++) {
                w.put(new BlockPos(135, y, z), Blocks.DIRT.defaultBlockState());
            }
        }
        // (b) gap x=147..149: floor at 195, air 196..200
        for (int x = 147; x <= 149; x++) {
            for (int z = 37; z <= 43; z++) {
                w.remove(new BlockPos(x, 200, z));
                w.put(new BlockPos(x, 195, z), Blocks.SMOOTH_STONE.defaultBlockState());
            }
        }
        // (c) cliff x=161..163, y=201..202
        for (int x = 161; x <= 163; x++) {
            for (int z = 37; z <= 43; z++) {
                w.put(new BlockPos(x, 201, z), Blocks.SMOOTH_STONE.defaultBlockState());
                w.put(new BlockPos(x, 202, z), Blocks.SMOOTH_STONE.defaultBlockState());
            }
        }
    }

    @Test
    void findsCourseRouteWithAllPlannedEdges() {
        VasyanNodeEvaluator evaluator = new ReproEvaluator(mob, level);
        VasyanPathFinder finder = new VasyanPathFinder(evaluator, 1000);
        Path path = finder.findPath(null, mob, Set.of(TARGET), 48, 0, 1.0F);
        assertNotNull(path, "search must reach the course target instead of dying in the void");
        assertTrue(path instanceof VasyanPath, "search must return an edge-aware path");
        List<MoveType> types = ((VasyanPath) path).transitions().stream()
            .map(VasyanEdge::moveType).toList();
        assertTrue(types.contains(MoveType.DIG), "route must dig through the wall, got " + types);
        assertTrue(types.contains(MoveType.PLACE), "route must bridge the gap, got " + types);
        assertTrue(types.contains(MoveType.PILLAR_UP), "route must pillar up the cliff, got " + types);
        assertEquals(TARGET, ((VasyanPath) path).getTarget());
    }

    private static final class ReproEvaluator extends VasyanNodeEvaluator {
        ReproEvaluator(Mob mob, Level level) {
            super(mob, level);
        }

        @Override
        public Node getStart() {
            return new Node(START.getX(), START.getY(), START.getZ());
        }

        @Override
        public void prepare(PathNavigationRegion region, Mob mob) {
        }

        @Override
        public void done() {
        }

        @Override
        public Target getGoal(double x, double y, double z) {
            return new Target((int) x, (int) y, (int) z);
        }

        @Override
        public BlockPathTypes getBlockPathType(net.minecraft.world.level.BlockGetter l, int x, int y, int z, Mob m) {
            return BlockPathTypes.OPEN;
        }

        @Override
        public BlockPathTypes getBlockPathType(net.minecraft.world.level.BlockGetter l, int x, int y, int z) {
            return BlockPathTypes.OPEN;
        }

        @Override
        protected int getVanillaNeighbors(Node[] neighbors, Node current) {
            int count = 0;
            BlockPos pos = current.asBlockPos();
            for (Direction dir : Direction.Plane.HORIZONTAL) {
                count = tryWalk(neighbors, count, current, pos.relative(dir));
                count = tryWalk(neighbors, count, current,
                    pos.relative(dir).relative(dir.getClockWise()));
            }
            return count;
        }

        private int tryWalk(Node[] neighbors, int count, Node current, BlockPos feet) {
            // flat, up 1, or down 1..3 onto supported open cells
            for (int dy = -3; dy <= 1; dy++) {
                BlockPos candidate = feet.above(dy);
                if (isOpenCell(candidate) && isOpenCell(candidate.above())
                        && !isOpenCell(candidate.below())) {
                    Node node = getNode(candidate.getX(), candidate.getY(), candidate.getZ());
                    if (count < neighbors.length && !containsNode(neighbors, count, node)) {
                        node.type = BlockPathTypes.WALKABLE;
                        node.costMalus = 1;
                        neighbors[count] = node;
                        count++;
                    }
                    return count;
                }
            }
            return count;
        }

        private boolean isOpenCell(BlockPos pos) {
            BlockState state = world.getOrDefault(pos, Blocks.AIR.defaultBlockState());
            return state.isAir() || state.canBeReplaced() || !state.getFluidState().isEmpty();
        }

        private static boolean containsNode(Node[] neighbors, int count, Node node) {
            for (int i = 0; i < count; i++) {
                if (neighbors[i].x == node.x && neighbors[i].y == node.y && neighbors[i].z == node.z) {
                    return true;
                }
            }
            return false;
        }
    }
}
