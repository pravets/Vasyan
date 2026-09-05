package ru.pravets.vasyan.navigation;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.PathNavigationRegion;
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
import java.util.List;
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
 * {@code getEdges} hook the same way {@code getNeighbors} does, without
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

    private static VasyanEdge find(List<VasyanEdge> edges, BlockPos pos, MoveType type) {
        for (VasyanEdge edge : edges) {
            if (edge.to().asBlockPos().equals(pos) && edge.moveType() == type) {
                return edge;
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
        List<VasyanEdge> edges = evaluator.getEdges(new Node(BOT.getX(), BOT.getY(), BOT.getZ()));

        VasyanEdge dig = find(edges, BOT.east(2), MoveType.DIG);
        assertNotNull(dig, "evaluator must offer a DIG edge landing beyond the wall");
        assertEquals(BOT.east(), dig.digFoot());
        assertEquals(BOT.east().above(), dig.digHead());
        assertTrue(dig.cost() >= VasyanConfig.NAV_DIG_COST.get(),
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
        List<VasyanEdge> edges = evaluator.getEdges(new Node(BOT.getX(), BOT.getY(), BOT.getZ()));

        VasyanEdge place = find(edges, BOT.east(), MoveType.PLACE);
        assertNotNull(place, "evaluator must offer a PLACE edge at the near side of a too-deep gap");
        assertEquals(BOT.east().below(), place.placePosition());
        assertEquals(VasyanConfig.NAV_PLACE_COST.get(), place.cost(), 0.001f);
    }

    @Test
    void generatesNoPlaceNeighborWhenPitIsShallowEnoughToDropInto() {
        Map<BlockPos, BlockState> states = new HashMap<>();
        states.put(BOT.below(), Blocks.DIRT.defaultBlockState());
        // Only a 2-block drop east: within NAV_MAX_DROP_DOWN, vanilla walk-down covers it.
        states.put(new BlockPos(11, 68, 10), Blocks.STONE.defaultBlockState());

        mobCarrying(Blocks.DIRT);
        VasyanNodeEvaluator evaluator = new VasyanNodeEvaluator(mob, levelWith(states));
        List<VasyanEdge> edges = evaluator.getEdges(new Node(BOT.getX(), BOT.getY(), BOT.getZ()));

        assertNull(find(edges, BOT.east(), MoveType.PLACE),
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
        List<VasyanEdge> edges = evaluator.getEdges(new Node(BOT.getX(), BOT.getY(), BOT.getZ()));

        VasyanEdge pillar = find(edges, BOT.above(), MoveType.PILLAR_UP);
        assertNotNull(pillar, "evaluator must offer a PILLAR_UP edge one block up");
        assertEquals(BOT, pillar.placePosition());
        assertEquals(VasyanConfig.NAV_PLACE_COST.get() + DigPlaceCosts.walkCost(), pillar.cost(), 0.001f);
    }

    @Test
    void doesNotGeneratePillarUpWhenItIncreasesDistanceToSameLevelTarget() {
        Map<BlockPos, BlockState> states = new HashMap<>();
        states.put(BOT.below(), Blocks.DIRT.defaultBlockState());

        mobCarrying(Blocks.DIRT);
        VasyanNodeEvaluator evaluator = new VasyanNodeEvaluator(mob, levelWith(states));
        evaluator.setNavigationTarget(BOT.east(3));

        List<VasyanEdge> edges = evaluator.getEdges(new Node(BOT.getX(), BOT.getY(), BOT.getZ()));

        assertNull(find(edges, BOT.above(), MoveType.PILLAR_UP),
            "a same-level approach must not climb when climbing increases target distance");
    }

    @Test
    void generatesNoDigNeighborWhenFootObstacleIsUnbreakable() {
        Map<BlockPos, BlockState> states = new HashMap<>();
        states.put(BOT.below(), Blocks.DIRT.defaultBlockState());
        // Bedrock at foot, dirt at head: clearing the head still leaves a solid foot cell.
        states.put(BOT.east(), Blocks.BEDROCK.defaultBlockState());
        states.put(BOT.east().above(), Blocks.DIRT.defaultBlockState());

        mobCarrying(Blocks.DIRT);
        VasyanNodeEvaluator evaluator = new VasyanNodeEvaluator(mob, levelWith(states));
        List<VasyanEdge> edges = evaluator.getEdges(new Node(BOT.getX(), BOT.getY(), BOT.getZ()));

        assertNull(find(edges, BOT.east(2), MoveType.DIG),
            "a foot-level unbreakable obstacle must not produce a DIG edge");
    }

    @Test
    void generatesNoDigNeighborWhenHeadObstacleIsUnbreakable() {
        Map<BlockPos, BlockState> states = new HashMap<>();
        states.put(BOT.below(), Blocks.DIRT.defaultBlockState());
        // Dirt at foot, bedrock at head: clearing the foot still leaves a solid head cell.
        states.put(BOT.east(), Blocks.DIRT.defaultBlockState());
        states.put(BOT.east().above(), Blocks.BEDROCK.defaultBlockState());

        mobCarrying(Blocks.DIRT);
        VasyanNodeEvaluator evaluator = new VasyanNodeEvaluator(mob, levelWith(states));
        List<VasyanEdge> edges = evaluator.getEdges(new Node(BOT.getX(), BOT.getY(), BOT.getZ()));

        assertNull(find(edges, BOT.east(2), MoveType.DIG),
            "a head-level unbreakable obstacle must not produce a DIG edge");
    }

    @Test
    void generatesNoDigNeighborIntoUnbreakableBeyondCell() {
        Map<BlockPos, BlockState> states = new HashMap<>();
        states.put(BOT.below(), Blocks.DIRT.defaultBlockState());
        states.put(BOT.east(), Blocks.DIRT.defaultBlockState());
        states.put(BOT.east().above(), Blocks.DIRT.defaultBlockState());
        // The destination itself is bedrock: never route the bot into a cell it cannot enter.
        states.put(BOT.east(2), Blocks.BEDROCK.defaultBlockState());

        mobCarrying(Blocks.DIRT);
        VasyanNodeEvaluator evaluator = new VasyanNodeEvaluator(mob, levelWith(states));
        List<VasyanEdge> edges = evaluator.getEdges(new Node(BOT.getX(), BOT.getY(), BOT.getZ()));

        assertNull(find(edges, BOT.east(2), MoveType.DIG),
            "an unbreakable destination must not be force-marked walkable");
    }

    @Test
    void generatesNoDigNeighborIntoLavaBeyondCell() {
        Map<BlockPos, BlockState> states = new HashMap<>();
        states.put(BOT.below(), Blocks.DIRT.defaultBlockState());
        states.put(BOT.east(), Blocks.DIRT.defaultBlockState());
        states.put(BOT.east().above(), Blocks.DIRT.defaultBlockState());
        states.put(BOT.east(2), Blocks.LAVA.defaultBlockState());

        mobCarrying(Blocks.DIRT);
        VasyanNodeEvaluator evaluator = new VasyanNodeEvaluator(mob, levelWith(states));
        List<VasyanEdge> edges = evaluator.getEdges(new Node(BOT.getX(), BOT.getY(), BOT.getZ()));

        assertNull(find(edges, BOT.east(2), MoveType.DIG),
            "a lava destination is not a passable DIG target");
    }

    @Test
    void doesNotReAddNeighborsAlreadyPresentOrDoubleTheirCost() {
        Map<BlockPos, BlockState> states = new HashMap<>();
        states.put(BOT.below(), Blocks.DIRT.defaultBlockState());
        states.put(BOT.east(), Blocks.DIRT.defaultBlockState());
        states.put(BOT.east().above(), Blocks.DIRT.defaultBlockState());

        mobCarrying(Blocks.DIRT);
        VasyanNodeEvaluator evaluator = new VasyanNodeEvaluator(mob, levelWith(states));
        Node current = new Node(BOT.getX(), BOT.getY(), BOT.getZ());
        List<VasyanEdge> first = evaluator.getEdges(current);
        VasyanEdge dig = find(first, BOT.east(2), MoveType.DIG);
        assertNotNull(dig);
        assertEquals(0, dig.to().costMalus, 0.001f, "edge cost must not mutate shared node malus");
        assertEquals(dig.cost(), find(evaluator.getEdges(current), BOT.east(2), MoveType.DIG).cost(), 0.001f);
    }

    @Test
    void respectsTheNeighborArrayCapacity() {
        Map<BlockPos, BlockState> states = new HashMap<>();
        states.put(BOT.below(), Blocks.DIRT.defaultBlockState());
        states.put(BOT.east(), Blocks.DIRT.defaultBlockState());
        states.put(BOT.east().above(), Blocks.DIRT.defaultBlockState());

        mobCarrying(Blocks.DIRT);
        VasyanNodeEvaluator evaluator = new VasyanNodeEvaluator(mob, levelWith(states));
        assertTrue(evaluator.getEdges(new Node(BOT.getX(), BOT.getY(), BOT.getZ())).size() <= 9);
    }

    @Test
    void generatesNoPlaceNeighborWithoutScaffoldBlocks() {
        Map<BlockPos, BlockState> states = new HashMap<>();
        states.put(BOT.below(), Blocks.DIRT.defaultBlockState());
        states.put(new BlockPos(11, 65, 10), Blocks.STONE.defaultBlockState());

        mobCarrying();
        VasyanNodeEvaluator evaluator = new VasyanNodeEvaluator(mob, levelWith(states));
        List<VasyanEdge> edges = evaluator.getEdges(new Node(BOT.getX(), BOT.getY(), BOT.getZ()));

        assertNull(find(edges, BOT.east(), MoveType.PLACE),
            "no scaffold block in the inventory means no PLACE edge across a deep gap");
    }

    @Test
    void generatesNoPlaceNeighborWhenScaffoldIsNotWhitelisted() {
        Map<BlockPos, BlockState> states = new HashMap<>();
        states.put(BOT.below(), Blocks.DIRT.defaultBlockState());
        states.put(new BlockPos(11, 65, 10), Blocks.STONE.defaultBlockState());

        mobCarrying(Blocks.GLASS);
        VasyanNodeEvaluator evaluator = new VasyanNodeEvaluator(mob, levelWith(states));
        List<VasyanEdge> edges = evaluator.getEdges(new Node(BOT.getX(), BOT.getY(), BOT.getZ()));

        assertNull(find(edges, BOT.east(), MoveType.PLACE),
            "a full-cube block outside NAV_SCAFFOLD_WHITELIST must not plan a PLACE edge");
    }

    @Test
    void generatesNoPillarUpNeighborWhenScaffoldIsNotWhitelisted() {
        Map<BlockPos, BlockState> states = new HashMap<>();
        states.put(BOT.below(), Blocks.DIRT.defaultBlockState());

        mobCarrying(Blocks.GLASS);
        VasyanNodeEvaluator evaluator = new VasyanNodeEvaluator(mob, levelWith(states));
        List<VasyanEdge> edges = evaluator.getEdges(new Node(BOT.getX(), BOT.getY(), BOT.getZ()));

        assertNull(find(edges, BOT.above(), MoveType.PILLAR_UP),
            "a full-cube block outside NAV_SCAFFOLD_WHITELIST must not plan a PILLAR_UP edge");
    }

    @Test
    void stillPlansScaffoldEdgesWhenWhitelistedBlockIsAmongOthers() {
        Map<BlockPos, BlockState> states = new HashMap<>();
        states.put(BOT.below(), Blocks.DIRT.defaultBlockState());
        states.put(new BlockPos(11, 65, 10), Blocks.STONE.defaultBlockState());

        mobCarrying(Blocks.GLASS, Blocks.DIRT);
        VasyanNodeEvaluator evaluator = new VasyanNodeEvaluator(mob, levelWith(states));
        List<VasyanEdge> edges = evaluator.getEdges(new Node(BOT.getX(), BOT.getY(), BOT.getZ()));

        assertNotNull(find(edges, BOT.east(), MoveType.PLACE),
            "a whitelisted block carried next to non-whitelisted ones still plans a PLACE edge");
    }

    @Test
    void walkEdgeIntoLiquidCarriesTheLiquidSurcharge() {
        Map<BlockPos, BlockState> states = new HashMap<>();
        states.put(BOT.east(), Blocks.WATER.defaultBlockState());
        states.put(BOT.west(), Blocks.STONE.defaultBlockState());
        VasyanNodeEvaluator evaluator = new VasyanNodeEvaluator(mob, levelWith(states));

        Node current = new Node(BOT.getX(), BOT.getY(), BOT.getZ());
        Node waterNode = new Node(BOT.getX() + 1, BOT.getY(), BOT.getZ());
        Node dryNode = new Node(BOT.getX() - 1, BOT.getY(), BOT.getZ());
        List<VasyanEdge> edges = evaluator.getEdges(current, new Node[]{waterNode, dryNode}, 2);

        VasyanEdge intoWater = find(edges, BOT.east(), MoveType.WALK);
        VasyanEdge ontoLand = find(edges, BOT.west(), MoveType.WALK);
        assertNotNull(intoWater);
        assertNotNull(ontoLand);
        assertEquals(DigPlaceCosts.walkCost() + VasyanConfig.NAV_LIQUID_COST.get(), intoWater.cost(), 0.001f,
            "a WALK edge whose destination cell is liquid must carry the NAV_LIQUID_COST surcharge");
        assertEquals(DigPlaceCosts.walkCost(), ontoLand.cost(), 0.001f,
            "a WALK edge onto a dry cell must stay at the base walk cost");
    }

    @Test
    void generatesNoPillarUpNeighborWithoutScaffoldBlocks() {
        Map<BlockPos, BlockState> states = new HashMap<>();
        states.put(BOT.below(), Blocks.DIRT.defaultBlockState());

        mobCarrying();
        VasyanNodeEvaluator evaluator = new VasyanNodeEvaluator(mob, levelWith(states));
        List<VasyanEdge> edges = evaluator.getEdges(new Node(BOT.getX(), BOT.getY(), BOT.getZ()));

        assertNull(find(edges, BOT.above(), MoveType.PILLAR_UP),
            "no scaffold block in the inventory means no PILLAR_UP edge");
    }

    @Test
    void freshComputationsDoNotRetainSpecialMetadata() {
        Map<BlockPos, BlockState> states = new HashMap<>();
        states.put(BOT.below(), Blocks.DIRT.defaultBlockState());
        states.put(BOT.east(), Blocks.DIRT.defaultBlockState());
        states.put(BOT.east().above(), Blocks.DIRT.defaultBlockState());
        mobCarrying(Blocks.DIRT);
        VasyanNodeEvaluator evaluator = new VasyanNodeEvaluator(mob, levelWith(states));
        VasyanEdge first = find(evaluator.getEdges(new Node(BOT.getX(), BOT.getY(), BOT.getZ())),
            BOT.east(2), MoveType.DIG);
        assertNotNull(first);
        MoveType firstType = first.moveType();
        BlockPos firstFoot = first.digFoot();
        BlockPos firstHead = first.digHead();
        float firstCost = first.cost();

        world.clear();
        mobCarrying();
        VasyanEdge second = find(evaluator.getEdges(new Node(BOT.getX(), BOT.getY(), BOT.getZ())),
            BOT.east(2), MoveType.DIG);
        assertNull(second);
        assertTrue(evaluator.getEdges(new Node(BOT.getX(), BOT.getY(), BOT.getZ())).stream()
            .noneMatch(edge -> edge.moveType() != MoveType.WALK));
        assertEquals(firstType, first.moveType());
        assertEquals(firstFoot, first.digFoot());
        assertEquals(firstHead, first.digHead());
        assertEquals(firstCost, first.cost());
    }

    @Test
    void specialEdgesFromDifferentParentsKeepIndependentCosts() {
        Map<BlockPos, BlockState> states = new HashMap<>();
        states.put(BOT.below(), Blocks.DIRT.defaultBlockState());
        states.put(BOT.east(), Blocks.DIRT.defaultBlockState());
        states.put(BOT.east().above(), Blocks.DIRT.defaultBlockState());
        states.put(BOT.east(3), Blocks.DIRT.defaultBlockState());
        states.put(BOT.east(3).above(), Blocks.DIRT.defaultBlockState());
        states.put(BOT.east(2).below(), Blocks.DIRT.defaultBlockState());
        states.put(BOT.east(3).below(), Blocks.DIRT.defaultBlockState());
        states.put(BOT.east(2).below(), Blocks.DIRT.defaultBlockState());
        mobCarrying(Blocks.DIRT);
        VasyanNodeEvaluator evaluator = new VasyanNodeEvaluator(mob, levelWith(states));

        VasyanEdge east = find(evaluator.getEdges(new Node(BOT.getX(), BOT.getY(), BOT.getZ())),
            BOT.east(2), MoveType.DIG);
        VasyanEdge west = find(evaluator.getEdges(new Node(BOT.getX() + 4, BOT.getY(), BOT.getZ())),
            BOT.east(2), MoveType.DIG);

        assertEquals(east.cost(), west.cost(), 0.001f);
        assertEquals(0, east.to().costMalus, 0.001f);
        assertEquals(0, west.to().costMalus, 0.001f);
    }

    @Test
    void edgeApiKeepsWalkAndSpecialCandidatesAtTheSameCoordinateDistinct() {
        Map<BlockPos, BlockState> states = new HashMap<>();
        states.put(BOT.below(), Blocks.DIRT.defaultBlockState());
        states.put(BOT.east(), Blocks.DIRT.defaultBlockState());
        states.put(BOT.east().above(), Blocks.DIRT.defaultBlockState());
        mobCarrying(Blocks.DIRT);
        VasyanNodeEvaluator evaluator = new VasyanNodeEvaluator(mob, levelWith(states)) {
            @Override
            protected int getVanillaNeighbors(Node[] neighbors, Node current) {
                neighbors[0] = new Node(BOT.getX() + 2, BOT.getY(), BOT.getZ());
                return 1;
            }
        };
        Node current = new Node(BOT.getX(), BOT.getY(), BOT.getZ());
        PathNavigationRegion region = mock(PathNavigationRegion.class);
        List<VasyanEdge> edges = evaluator.getEdges(region, current);

        assertTrue(edges.stream().anyMatch(edge -> edge.moveType() == MoveType.DIG
            && edge.to().asBlockPos().equals(BOT.east(2))));
        assertTrue(edges.stream().anyMatch(edge -> edge.moveType() == MoveType.WALK
            && edge.to().asBlockPos().equals(BOT.east(2))));
        assertTrue(edges.stream().allMatch(edge -> edge.moveType() == MoveType.WALK
            || edge.moveType() == MoveType.DIG || edge.moveType() == MoveType.PLACE
            || edge.moveType() == MoveType.PILLAR_UP));
    }
}
