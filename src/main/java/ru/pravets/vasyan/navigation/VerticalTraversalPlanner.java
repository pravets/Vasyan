package ru.pravets.vasyan.navigation;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.jetbrains.annotations.Nullable;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Pure one-step vertical traversal planner: prepares the next safe staircase cell one block
 * below (DESCEND) or above (ASCEND) the bot. No world mutation and no Forge state - the glue
 * supplies a tiny world view and executes the returned action.
 *
 * <p>The planner deliberately never targets the bot's own column for digging. Descending by
 * breaking the block underfoot turns into uncontrolled falls; every safe descent starts with
 * a side step whose foot/head cells and support are prepared first.</p>
 */
public final class VerticalTraversalPlanner {

    /** Vertical traversal direction. */
    public enum Mode {
        DESCEND,
        ASCEND
    }

    /** What the server glue should do for the selected step. */
    public enum Action {
        /** Break a breakable block that occupies the step. */
        CLEAR,
        /** Place a full-cube support block for the step. */
        PLACE_SUPPORT,
        /** Move onto the prepared step. */
        MOVE
    }

    /** Minimal read-only world view needed by the planner. */
    public interface WorldView {
        /** Whether the bot can occupy this cell (air, liquid-free replaceable, etc.). */
        boolean isOpen(BlockPos pos);

        /** Whether this cell is a solid full-block support to stand on. */
        boolean isSolidSupport(BlockPos pos);

        /** Whether this cell may be cleared by digging. */
        boolean isBreakable(BlockPos pos);

        /** Whether the cell holds a dangerous liquid (lava). */
        boolean isUnsafeLiquid(BlockPos pos);

        /** Whether the cell holds FLOWING water (a waterfall column): the bot cannot
         *  stand or carve a staircase step in it - ascending into it just loses the
         *  fight against the current. Default false keeps existing test fixtures. */
        default boolean isFlowingWater(BlockPos pos) {
            return false;
        }
    }

    /**
     * One prepared vertical move.
     *
     * @param mode    traversal direction
     * @param action  action the glue must execute
     * @param target  block to clear/place, or the movement target for MOVE
     * @param standPos cell the bot should occupy after the step
     */
    public record Step(Mode mode, Action action, BlockPos target, BlockPos standPos) {
    }

    private static final List<Direction> HORIZONTAL = List.of(
        Direction.EAST, Direction.SOUTH, Direction.WEST, Direction.NORTH);

    private VerticalTraversalPlanner() {
    }

    /**
     * Plans one safe step towards {@code goal}. Returns empty when every adjacent step is
     * blocked, unsafe, or otherwise not preparable in one action.
     *
     * @param botPos current bot position
     * @param goal   target position the step should approach
     * @param mode   traversal direction (ascend or descend)
     * @param world  read-only world view used to evaluate candidate cells
     * @return the prepared next step, or empty if no safe step exists
     */
    public static Optional<Step> nextStep(BlockPos botPos, BlockPos goal, Mode mode, WorldView world) {
        int stepY = botPos.getY() + (mode == Mode.DESCEND ? -1 : 1);
        // For ascending, pillar-up directly under the bot is preferred: it is
        // always supported by the floor already under the bot and cannot be dug
        // away as a side scaffold can. Only if the bot's own column is blocked
        // do we try a side staircase.
        if (mode == Mode.ASCEND) {
            Step own = prepare(botPos, goal, mode, world, botPos.above());
            if (own != null) {
                return Optional.of(own);
            }
        }
        return HORIZONTAL.stream()
            .map(direction -> new BlockPos(
                botPos.getX() + direction.getStepX(), stepY, botPos.getZ() + direction.getStepZ()))
            .sorted(Comparator.comparingLong(candidate -> horizontalDistanceSqr(candidate, goal)))
            .map(candidate -> prepare(botPos, goal, mode, world, candidate))
            .filter(step -> step != null)
            .findFirst();
    }

    @Nullable
    private static Step prepare(BlockPos botPos, BlockPos goal, Mode mode, WorldView world,
                                BlockPos standPos) {
        BlockPos head = standPos.above();
        BlockPos support = standPos.below();

        if (world.isUnsafeLiquid(standPos) || world.isUnsafeLiquid(head) || world.isUnsafeLiquid(support)) {
            return null;
        }

        // A waterfall column is not a staircase: you cannot stand in flowing
        // water, so ascending into it just stalls the climb against the current.
        if (mode == Mode.ASCEND
                && (world.isFlowingWater(standPos) || world.isFlowingWater(head))) {
            return null;
        }

        if (world.isBreakable(standPos)) {
            return new Step(mode, Action.CLEAR, standPos, standPos);
        }
        if (world.isBreakable(head)) {
            return new Step(mode, Action.CLEAR, head, standPos);
        }
        if (!world.isOpen(standPos) || !world.isOpen(head)) {
            return null;
        }

        if (!world.isSolidSupport(support)) {
            if (world.isBreakable(support)) {
                return new Step(mode, Action.CLEAR, support, standPos);
            }
            if (world.isOpen(support)) {
                return new Step(mode, Action.PLACE_SUPPORT, support, standPos);
            }
            return null;
        }

        // Defensive: never return a step that digs or builds in the bot's own column.
        if (standPos.getX() == botPos.getX() && standPos.getZ() == botPos.getZ()) {
            return null;
        }
        return new Step(mode, Action.MOVE, standPos, standPos);
    }

    private static long horizontalDistanceSqr(BlockPos a, BlockPos b) {
        int dx = a.getX() - b.getX();
        int dz = a.getZ() - b.getZ();
        return (long) dx * dx + (long) dz * dz;
    }
}
