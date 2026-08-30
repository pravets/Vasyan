package ru.pravets.vasyan.navigation;

import net.minecraft.core.BlockPos;

/**
 * Pure goal predicate for bot navigation: answers "is the given position good enough
 * to stop at".
 * <p>
 * Implementations are small immutable types with no world access — only arithmetic on
 * {@link BlockPos} coordinates — so they can be unit-tested without the Minecraft
 * bootstrap.
 */
public interface VasyanGoal {

    /**
     * Checks whether {@code botPos} satisfies this goal.
     *
     * @param botPos current position of the bot
     * @return true when the goal is reached at {@code botPos}
     */
    boolean hasReached(BlockPos botPos);

    /**
     * Human-readable description of this goal for logs and debug output.
     *
     * @return short non-empty description
     */
    String describe();

    /**
     * Creates a goal reached when the bot is within {@code rangeBlocks} of the target,
     * where distance is the max absolute coordinate delta ({@code max(|dx|, |dy|, |dz|)}).
     *
     * @param target      target block position
     * @param rangeBlocks inclusive range in blocks on each axis
     * @return goal checking proximity to {@code target}
     */
    static VasyanGoal near(BlockPos target, int rangeBlocks) {
        return new GoalNear(target, rangeBlocks);
    }

    /**
     * Creates a goal reached when the bot is horizontally within {@code rangeBlocks} of the
     * target, ignoring Y ({@code max(|dx|, |dz|)} <= range). For look-out stations whose
     * Y is a phantom height and should not affect arrival on sloped terrain.
     *
     * @param target      target block position
     * @param rangeBlocks inclusive horizontal range in blocks on X and Z
     * @return horizontal proximity goal for {@code target}
     */
    static VasyanGoal horizontalNear(BlockPos target, int rangeBlocks) {
        return new GoalHorizontalNear(target, rangeBlocks);
    }

    /**
     * Creates a goal reached only when the bot stands beside the block: manhattan XZ
     * distance == 1 with equal Y. Standing ON TOP of the block is NOT adjacency.
     *
     * @param block the block to stand next to
     * @return side-adjacency goal for {@code block}
     */
    static VasyanGoal adjacent(BlockPos block) {
        return new GoalAdjacent(block);
    }

    /**
     * Creates a goal reached when the bot is within ±1 on both X and Z;
     * height is ignored entirely.
     *
     * @param x target X coordinate
     * @param z target Z coordinate
     * @return planar proximity goal around (x, z)
     */
    static VasyanGoal xz(int x, int z) {
        return new GoalXZ(x, z);
    }

    /**
     * Creates a goal reached when the bot is within ±1 of the target Y level;
     * X and Z are ignored.
     *
     * @param y target Y level
     * @return vertical proximity goal around Y = {@code y}
     */
    static VasyanGoal y(int y) {
        return new GoalY(y);
    }

    /**
     * Creates a composite goal reached when ANY of the sub-goals is reached.
     *
     * @param goals sub-goals; must be non-empty
     * @return composite disjunction goal
     */
    static VasyanGoal any(VasyanGoal... goals) {
        return new GoalCompositeAny(goals);
    }

    /**
     * Resolves the concrete anchor navigation should steer towards. Composite goals use the
     * sub-goal nearest to the bot; unknown/custom goals conservatively use the bot position.
     */
    static BlockPos anchor(VasyanGoal goal, BlockPos botPos) {
        if (goal instanceof GoalNear near) {
            return near.target();
        }
        if (goal instanceof GoalHorizontalNear near) {
            return new BlockPos(near.target().getX(), botPos.getY(), near.target().getZ());
        }
        if (goal instanceof GoalAdjacent adjacent) {
            return adjacent.block();
        }
        if (goal instanceof GoalXZ xz) {
            return new BlockPos(xz.x(), botPos.getY(), xz.z());
        }
        if (goal instanceof GoalY y) {
            return new BlockPos(botPos.getX(), y.y(), botPos.getZ());
        }
        if (goal instanceof GoalCompositeAny any) {
            BlockPos best = botPos;
            int bestDist = Integer.MAX_VALUE;
            for (VasyanGoal subGoal : any.goals()) {
                BlockPos anchor = anchor(subGoal, botPos);
                int dist = anchor.distManhattan(botPos);
                if (dist < bestDist) {
                    bestDist = dist;
                    best = anchor;
                }
            }
            return best;
        }
        return botPos;
    }
}
