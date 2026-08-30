package ru.pravets.vasyan.navigation;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VerticalTraversalPlannerTest {

    private static final BlockPos BOT = new BlockPos(10, 70, 10);

    private static VerticalTraversalPlanner.WorldView world(Set<BlockPos> solid,
                                                            Set<BlockPos> breakable,
                                                            Set<BlockPos> unsafeLiquid,
                                                            Set<BlockPos> flowingWater) {
        return new VerticalTraversalPlanner.WorldView() {
            @Override
            public boolean isOpen(BlockPos pos) {
                return !solid.contains(pos) && !breakable.contains(pos)
                    && !unsafeLiquid.contains(pos) && !flowingWater.contains(pos);
            }

            @Override
            public boolean isSolidSupport(BlockPos pos) {
                return solid.contains(pos);
            }

            @Override
            public boolean isBreakable(BlockPos pos) {
                return breakable.contains(pos);
            }

            @Override
            public boolean isUnsafeLiquid(BlockPos pos) {
                return unsafeLiquid.contains(pos);
            }

            @Override
            public boolean isFlowingWater(BlockPos pos) {
                return flowingWater.contains(pos);
            }
        };
    }

    private static VerticalTraversalPlanner.WorldView world(Set<BlockPos> solid,
                                                            Set<BlockPos> breakable,
                                                            Set<BlockPos> unsafeLiquid) {
        return world(solid, breakable, unsafeLiquid, Set.of());
    }

    @Test
    void descendStepClearsFootBeforeMoving() {
        BlockPos candidate = new BlockPos(11, 69, 10);
        var world = world(
            Set.of(candidate.below()),
            Set.of(candidate),
            Set.of());

        Optional<VerticalTraversalPlanner.Step> step = VerticalTraversalPlanner.nextStep(
            BOT, new BlockPos(14, 66, 10), VerticalTraversalPlanner.Mode.DESCEND, world);

        assertTrue(step.isPresent());
        assertEquals(VerticalTraversalPlanner.Action.CLEAR, step.get().action());
        assertEquals(candidate, step.get().target());
    }

    @Test
    void descendStepPlacesSupportWhenFloorIsMissing() {
        BlockPos candidate = new BlockPos(11, 69, 10);
        var world = world(Set.of(), Set.of(), Set.of());

        Optional<VerticalTraversalPlanner.Step> step = VerticalTraversalPlanner.nextStep(
            BOT, new BlockPos(14, 66, 10), VerticalTraversalPlanner.Mode.DESCEND, world);

        assertTrue(step.isPresent());
        assertEquals(VerticalTraversalPlanner.Action.PLACE_SUPPORT, step.get().action());
        assertEquals(candidate.below(), step.get().target());
    }

    @Test
    void descendStepMovesWhenFootHeadAndFloorAreReady() {
        BlockPos candidate = new BlockPos(11, 69, 10);
        var world = world(Set.of(candidate.below()), Set.of(), Set.of());

        Optional<VerticalTraversalPlanner.Step> step = VerticalTraversalPlanner.nextStep(
            BOT, new BlockPos(14, 66, 10), VerticalTraversalPlanner.Mode.DESCEND, world);

        assertTrue(step.isPresent());
        assertEquals(VerticalTraversalPlanner.Action.MOVE, step.get().action());
        assertEquals(candidate, step.get().standPos());
    }

    @Test
    void plannerNeverDigsDirectlyBelowTheBot() {
        var world = world(Set.of(), Set.of(BOT.below()), Set.of());

        Optional<VerticalTraversalPlanner.Step> step = VerticalTraversalPlanner.nextStep(
            BOT, new BlockPos(10, 66, 10), VerticalTraversalPlanner.Mode.DESCEND, world);

        assertTrue(step.isPresent());
        assertTrue(step.get().target().getY() >= BOT.getY() - 2,
            "descent prepares a side step, never the block directly under the bot");
        assertTrue(step.get().target().getX() != BOT.getX() || step.get().target().getZ() != BOT.getZ(),
            "descent must not target the bot's own column");
    }

    @Test
    void descendRejectsLavaAtCandidate() {
        BlockPos candidate = new BlockPos(11, 69, 10);
        var world = world(Set.of(candidate.below()), Set.of(), Set.of(candidate));

        Optional<VerticalTraversalPlanner.Step> step = VerticalTraversalPlanner.nextStep(
            BOT, new BlockPos(14, 66, 10), VerticalTraversalPlanner.Mode.DESCEND, world);

        assertTrue(step.isEmpty() || step.get().action() != VerticalTraversalPlanner.Action.MOVE
            || !step.get().standPos().equals(candidate));
    }

    @Test
    void ascendStepPlacesAPillarUnderTheBot() {
        var world = world(Set.of(), Set.of(), Set.of());

        Optional<VerticalTraversalPlanner.Step> step = VerticalTraversalPlanner.nextStep(
            BOT, new BlockPos(14, 74, 10), VerticalTraversalPlanner.Mode.ASCEND, world);

        assertTrue(step.isPresent());
        assertEquals(VerticalTraversalPlanner.Action.PLACE_SUPPORT, step.get().action());
        assertEquals(BOT, step.get().target());
        assertEquals(BOT.above(), step.get().standPos());
    }

    @Test
    void ascendFallsBackToSideSupportWhenOwnColumnIsBlocked() {
        BlockPos sideTarget = new BlockPos(11, 70, 10);
        // Bot's own column is blocked by an unbreakable support.
        var world = world(Set.of(), Set.of(), Set.of(BOT));

        Optional<VerticalTraversalPlanner.Step> step = VerticalTraversalPlanner.nextStep(
            BOT, new BlockPos(14, 74, 10), VerticalTraversalPlanner.Mode.ASCEND, world);

        assertTrue(step.isPresent());
        assertEquals(VerticalTraversalPlanner.Action.PLACE_SUPPORT, step.get().action());
        assertEquals(sideTarget, step.get().target());
    }

    @Test
    void ascendRejectsFlowingWaterAtTarget() {
        BlockPos flowing = BOT.above();
        // Block all side escape routes so the only possible step would be into
        // the flowing-water column; that column must be rejected.
        var sideWalls = Set.of(
            new BlockPos(11, 71, 10),
            new BlockPos(9, 71, 10),
            new BlockPos(10, 71, 11),
            new BlockPos(10, 71, 9)
        );
        var world = world(sideWalls, Set.of(), Set.of(), Set.of(flowing));

        Optional<VerticalTraversalPlanner.Step> step = VerticalTraversalPlanner.nextStep(
            BOT, new BlockPos(14, 74, 10), VerticalTraversalPlanner.Mode.ASCEND, world);

        assertTrue(step.isEmpty(), "ascending into flowing water must be rejected");
    }

    @Test
    void ascendRejectsFlowingWaterAtHead() {
        BlockPos flowingHead = BOT.above().above();
        // Block all side head cells so the only possible head position would be
        // the one with flowing water; that column must be rejected.
        var sideHeads = Set.of(
            new BlockPos(11, 72, 10),
            new BlockPos(9, 72, 10),
            new BlockPos(10, 72, 11),
            new BlockPos(10, 72, 9)
        );
        var solidSideSupports = Set.of(
            new BlockPos(11, 70, 10),
            new BlockPos(9, 70, 10),
            new BlockPos(10, 70, 11),
            new BlockPos(10, 70, 9)
        );
        var solidBlocks = new HashSet<BlockPos>();
        solidBlocks.addAll(solidSideSupports);
        solidBlocks.addAll(sideHeads);
        var world = world(solidBlocks, Set.of(), Set.of(), Set.of(flowingHead));

        Optional<VerticalTraversalPlanner.Step> step = VerticalTraversalPlanner.nextStep(
            BOT, new BlockPos(14, 74, 10), VerticalTraversalPlanner.Mode.ASCEND, world);

        assertTrue(step.isEmpty(), "ascending into a column with flowing water at head must be rejected");
    }

    @Test
    void ascendAllowsNonFlowingWaterAtTarget() {
        // Still water is open and not flowing: the bot may occupy the cell.
        var world = world(Set.of(), Set.of(), Set.of(), Set.of());

        Optional<VerticalTraversalPlanner.Step> step = VerticalTraversalPlanner.nextStep(
            BOT, new BlockPos(14, 74, 10), VerticalTraversalPlanner.Mode.ASCEND, world);

        assertTrue(step.isPresent(), "ascending into non-flowing water must still be allowed");
    }
}
