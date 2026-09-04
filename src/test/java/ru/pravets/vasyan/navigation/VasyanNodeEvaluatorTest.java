package ru.pravets.vasyan.navigation;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.Node;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import ru.pravets.vasyan.config.VasyanConfig;
import ru.pravets.vasyan.entity.VasyanEntity;
import ru.pravets.vasyan.entity.VasyanInventory;
import ru.pravets.vasyan.testutil.AbstractMinecraftTest;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link VasyanNodeEvaluator} special edges (DIG / PLACE / PILLAR_UP) over a
 * stubbed {@link Level}: a {@link BlockPos}-keyed map of real block states,
 * everything unlisted reads as air. The evaluator's vanilla half is exercised
 * by {@code WalkNodeEvaluator} itself; these tests drive the package-visible
 * {@code addSpecialEdges} hook the same way {@code getNeighbors} does, without
 * standing up a {@link net.minecraft.world.level.PathNavigationRegion}.
 *
 * <p>Shared-mock note (same as {@code DigRulesTest}): mocking {@link Level}
 * per test exhausts the test JVM heap, so one class-level mock is reused and
 * each test installs its own block map.</p>
 */
class VasyanNodeEvaluatorTest extends AbstractMinecraftTest {

    private static final BlockPos BOT = new BlockPos(10, 70, 10);

    private static Level level;
    private static Map<BlockPos, BlockState> world;
    private static VasyanEntity mob;

    @BeforeAll
    static void stubLevelAndMob() {
        initBlockStateCaches();
        world = new HashMap<>();
        level = mock(Level.class);
        when(level.getBlockState(any(BlockPos.class)))
            .thenAnswer(invocation ->
                world.getOrDefault(invocation.getArgument(0), Blocks.AIR.defaultBlockState()));
        when(level.isOutsideBuildHeight(anyInt()))
            .thenAnswer(invocation -> (int) invocation.getArgument(0) > 320
                || (int) invocation.getArgument(0) < -64);
        // DigRules.isFallingBlock uses the BlockPos overload; without this stub the
        // mock answers false forever and the upward scan never terminates.
        when(level.isOutsideBuildHeight(any(BlockPos.class)))
            .thenAnswer(invocation -> {
                BlockPos pos = invocation.getArgument(0);
                return pos.getY() > 320 || pos.getY() < -64;
            });
        // One shared mock: Mockito's inline mock maker recompiles heavy method
        // graphs per created mock, and several Level/entity mocks in one JVM
        // exhaust the test heap (seen as OOM with per-test mocks).
        mob = mock(VasyanEntity.class);
    }

    /** Mirrors the server startup: leaves every BlockState cache unpopulated after plain bootstrap. */
    private static void initBlockStateCaches() {
        for (Block block : BuiltInRegistries.BLOCK) {
            for (BlockState state : block.getStateDefinition().getPossibleStates()) {
                state.initCache();
            }
        }
    }

    private static Level levelWith(Map<BlockPos, BlockState> states) {
        world.clear();
        world.putAll(states);
        return level;
    }

    /** Installs an inventory holding the given blocks as the shared mob's inventory. */
    private static void mobCarrying(Block... blocks) {
        VasyanInventory inventory = new VasyanInventory();
        for (Block block : blocks) {
            inventory.addItem(new ItemStack(block.asItem()));
        }
        when(mob.getInventory()).thenReturn(inventory);
    }

    private static Node find(Node[] nodes, int count, BlockPos pos) {
        for (int i = 0; i < count; i++) {
            if (nodes[i].x == pos.getX() && nodes[i].y == pos.getY() && nodes[i].z == pos.getZ()) {
                return nodes[i];
            }
        }
        return null;
    }

    @Test
    void generatesDigNeighborBeyondATwoBlockWall() {
        Map<BlockPos, BlockState> states = new HashMap<>();
        states.put(BOT.below(), Blocks.DIRT.defaultBlockState());
        states.put(BOT.east(), Blocks.DIRT.defaultBlockState());
        states.put(BOT.east().above(), Blocks.DIRT.defaultBlockState());
        states.put(BOT.east(2).below(), Blocks.DIRT.defaultBlockState());
        for (int x = 8; x <= 13; x++) {
            states.putIfAbsent(new BlockPos(x, 69, 10), Blocks.DIRT.defaultBlockState());
        }

        mobCarrying(Blocks.DIRT);
        VasyanNodeEvaluator evaluator = new VasyanNodeEvaluator(mob, levelWith(states));
        Node[] neighbors = new Node[32];
        int count = evaluator.addSpecialEdges(neighbors, 0, new Node(BOT.getX(), BOT.getY(), BOT.getZ()));

        Node dig = find(neighbors, count, BOT.east(2));
        assertNotNull(dig, "evaluator must offer a DIG edge landing beyond the wall");
        assertEquals(MoveType.DIG, evaluator.getMoveType(dig));
        assertTrue(dig.costMalus >= VasyanConfig.NAV_DIG_COST.get(),
            "DIG edge must carry at least the configured base dig cost");
    }

    @Test
    void generatesPlaceNeighborAtTheNearEdgeOfADeepPit() {
        Map<BlockPos, BlockState> states = new HashMap<>();
        states.put(BOT.below(), Blocks.DIRT.defaultBlockState());
        // Foot cell east is open, then a 4-block-deep air shaft: deeper than NAV_MAX_DROP_DOWN (3).
        states.put(new BlockPos(11, 65, 10), Blocks.STONE.defaultBlockState());
        states.put(new BlockPos(12, 69, 10), Blocks.DIRT.defaultBlockState());
        states.put(new BlockPos(13, 69, 10), Blocks.DIRT.defaultBlockState());

        mobCarrying(Blocks.DIRT);
        VasyanNodeEvaluator evaluator = new VasyanNodeEvaluator(mob, levelWith(states));
        Node[] neighbors = new Node[32];
        int count = evaluator.addSpecialEdges(neighbors, 0, new Node(BOT.getX(), BOT.getY(), BOT.getZ()));

        Node place = find(neighbors, count, BOT.east());
        assertNotNull(place, "evaluator must offer a PLACE edge at the near side of a too-deep gap");
        assertEquals(MoveType.PLACE, evaluator.getMoveType(place));
        assertEquals(VasyanConfig.NAV_PLACE_COST.get(), place.costMalus, 0.001f);
    }

    @Test
    void generatesNoPlaceNeighborWhenPitIsShallowEnoughToDropInto() {
        Map<BlockPos, BlockState> states = new HashMap<>();
        states.put(BOT.below(), Blocks.DIRT.defaultBlockState());
        // Only a 2-block drop east: within NAV_MAX_DROP_DOWN, vanilla walk-down covers it.
        states.put(new BlockPos(11, 68, 10), Blocks.STONE.defaultBlockState());

        mobCarrying(Blocks.DIRT);
        VasyanNodeEvaluator evaluator = new VasyanNodeEvaluator(mob, levelWith(states));
        Node[] neighbors = new Node[32];
        int count = evaluator.addSpecialEdges(neighbors, 0, new Node(BOT.getX(), BOT.getY(), BOT.getZ()));

        assertNull(find(neighbors, count, BOT.east()),
            "a gap within maxDropDown must stay a plain walk-down edge, not a PLACE edge");
    }

    @Test
    void generatesPillarUpNeighborWhenColumnAboveIsOpen() {
        Map<BlockPos, BlockState> states = new HashMap<>();
        states.put(BOT.below(), Blocks.DIRT.defaultBlockState());
        // A 2-block-high cliff east: pillar up first, then walk across at y+1.
        states.put(BOT.east().above(2), Blocks.DIRT.defaultBlockState());
        states.put(BOT.east().above(3), Blocks.DIRT.defaultBlockState());
        states.put(BOT.east(2).above(2), Blocks.DIRT.defaultBlockState());

        mobCarrying(Blocks.DIRT);
        VasyanNodeEvaluator evaluator = new VasyanNodeEvaluator(mob, levelWith(states));
        Node[] neighbors = new Node[32];
        int count = evaluator.addSpecialEdges(neighbors, 0, new Node(BOT.getX(), BOT.getY(), BOT.getZ()));

        Node pillar = find(neighbors, count, BOT.above());
        assertNotNull(pillar, "evaluator must offer a PILLAR_UP edge one block up");
        assertEquals(MoveType.PILLAR_UP, evaluator.getMoveType(pillar));
        assertEquals(VasyanConfig.NAV_PLACE_COST.get() + DigPlaceCosts.walkCost(), pillar.costMalus, 0.001f);
    }

    @Test
    void generatesNoPillarUpNeighborWithoutScaffoldBlocks() {
        Map<BlockPos, BlockState> states = new HashMap<>();
        states.put(BOT.below(), Blocks.DIRT.defaultBlockState());

        mobCarrying();
        VasyanNodeEvaluator evaluator = new VasyanNodeEvaluator(mob, levelWith(states));
        Node[] neighbors = new Node[32];
        int count = evaluator.addSpecialEdges(neighbors, 0, new Node(BOT.getX(), BOT.getY(), BOT.getZ()));

        assertNull(find(neighbors, count, BOT.above()),
            "no scaffold block in the inventory means no PILLAR_UP edge");
    }

    @Test
    void unknownNodeDefaultsToWalkMoveType() {
        mobCarrying(Blocks.DIRT);
        VasyanNodeEvaluator evaluator = new VasyanNodeEvaluator(mob, levelWith(Map.of()));

        assertEquals(MoveType.WALK, evaluator.getMoveType(new Node(123, 45, 678)));
    }
}
