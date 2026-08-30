package ru.pravets.vasyan.navigation;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pure unit tests for the {@link VasyanGoal} hierarchy.
 * Only uses {@link BlockPos}, which is safe outside the Minecraft bootstrap
 * (same precedent as {@code ResourceSearchPlannerTest}).
 */
class VasyanGoalTest {

    private static final BlockPos ORIGIN = new BlockPos(10, 64, 10);

    @Test
    void nearOutsideRangeIsNotReached() {
        // Distance 3 with range 2 -> not reached.
        var goal = VasyanGoal.near(new BlockPos(13, 64, 10), 2);
        assertFalse(goal.hasReached(ORIGIN));
    }

    @Test
    void nearAtExactRangeBoundaryIsReached() {
        // Distance 2 with range 2 -> reached (inclusive boundary).
        var goal = VasyanGoal.near(new BlockPos(12, 64, 10), 2);
        assertTrue(goal.hasReached(ORIGIN));
    }

    @Test
    void nearCountsVerticalDistanceToo() {
        // 2 blocks straight up is within range 2, 3 up is not.
        assertTrue(VasyanGoal.near(new BlockPos(10, 66, 10), 2).hasReached(ORIGIN));
        assertFalse(VasyanGoal.near(new BlockPos(10, 67, 10), 2).hasReached(ORIGIN));
    }

    @Test
    void adjacentSideNeighborIsReached() {
        // Side neighbor: manhattan XZ == 1 at equal height.
        var goal = VasyanGoal.adjacent(new BlockPos(11, 64, 10));
        assertTrue(goal.hasReached(ORIGIN));
        assertTrue(VasyanGoal.adjacent(new BlockPos(10, 64, 9)).hasReached(ORIGIN));
    }

    @Test
    void adjacentStandingOnTopOfBlockIsNotReached() {
        // Standing ON the block (y+1) is NOT adjacency (review #5 semantics).
        var goal = VasyanGoal.adjacent(new BlockPos(10, 63, 10));
        assertFalse(goal.hasReached(new BlockPos(10, 64, 10)));
    }

    @Test
    void adjacentDiagonalIsNotReached() {
        // Diagonal neighbor: manhattan XZ == 2 -> not adjacency.
        var goal = VasyanGoal.adjacent(new BlockPos(11, 64, 11));
        assertFalse(goal.hasReached(ORIGIN));
    }

    @Test
    void adjacentDifferentHeightIsNotReached() {
        // Same XZ column but different height -> not side adjacency.
        assertFalse(VasyanGoal.adjacent(new BlockPos(11, 65, 10)).hasReached(ORIGIN));
    }

    @Test
    void xzIgnoresHeight() {
        // Within XZ tolerance regardless of Y difference.
        var goal = VasyanGoal.xz(10, 10);
        assertTrue(goal.hasReached(new BlockPos(11, 40, 10)));
        assertTrue(goal.hasReached(new BlockPos(9, 200, 9)));
        assertFalse(goal.hasReached(new BlockPos(12, 64, 10)));
        assertFalse(goal.hasReached(new BlockPos(10, 64, 12)));
    }

    @Test
    void yToleratesOneBlockDifference() {
        var goal = VasyanGoal.y(64);
        assertTrue(goal.hasReached(new BlockPos(0, 65, 0)));
        assertTrue(goal.hasReached(new BlockPos(50, 63, -7)));
        assertFalse(goal.hasReached(new BlockPos(0, 66, 0)));
        assertFalse(goal.hasReached(new BlockPos(0, 62, 0)));
    }

    @Test
    void anyIsReachedWhenFirstSubgoalReached() {
        // any(X, Y) reached when X reached even though Y is not.
        var x = VasyanGoal.xz(10, 10);
        var y = VasyanGoal.y(999);
        var goal = VasyanGoal.any(x, y);
        assertTrue(goal.hasReached(ORIGIN));
    }

    @Test
    void anyRequiresAtLeastOneReachedSubgoal() {
        var unreachable = VasyanGoal.y(999);
        var goal = VasyanGoal.any(unreachable, VasyanGoal.xz(-100, -100));
        assertFalse(goal.hasReached(ORIGIN));
    }

    @Test
    void describeIsNonEmptyForAllGoals() {
        assertAll(
                () -> assertFalse(VasyanGoal.near(ORIGIN, 2).describe().isBlank()),
                () -> assertFalse(VasyanGoal.adjacent(ORIGIN).describe().isBlank()),
                () -> assertFalse(VasyanGoal.xz(1, 2).describe().isBlank()),
                () -> assertFalse(VasyanGoal.y(64).describe().isBlank()),
                () -> assertFalse(VasyanGoal.any(VasyanGoal.y(64)).describe().isBlank()),
                () -> assertFalse(VasyanGoal.horizontalNear(ORIGIN, 3).describe().isBlank())
        );
    }

    @Test
    void horizontalNearIgnoresHeight() {
        var goal = VasyanGoal.horizontalNear(ORIGIN, 3);
        assertTrue(goal.hasReached(new BlockPos(10, 54, 10)));
        assertTrue(goal.hasReached(new BlockPos(10, 74, 10)));
    }

    @Test
    void horizontalNearAtExactRangeBoundaryIsReached() {
        var goal = VasyanGoal.horizontalNear(ORIGIN, 3);
        assertTrue(goal.hasReached(new BlockPos(13, 64, 13)));
    }

    @Test
    void horizontalNearOutsideRangeIsNotReached() {
        var goal = VasyanGoal.horizontalNear(ORIGIN, 3);
        assertFalse(goal.hasReached(new BlockPos(14, 64, 10)));
    }

    @Test
    void horizontalNearRejectsNegativeRange() {
        assertThrows(IllegalArgumentException.class, () -> VasyanGoal.horizontalNear(ORIGIN, -1));
    }
}
