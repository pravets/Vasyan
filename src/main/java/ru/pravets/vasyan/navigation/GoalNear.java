package ru.pravets.vasyan.navigation;

import net.minecraft.core.BlockPos;

/**
 * Goal reached when the bot is within {@code rangeBlocks} of {@link #target}, using the
 * max absolute coordinate delta as the distance metric: {@code max(|dx|, |dy|, |dz|)}.
 * The boundary is inclusive.
 *
 * @param target      target block position
 * @param rangeBlocks inclusive range in blocks on each axis
 */
public record GoalNear(BlockPos target, int rangeBlocks) implements VasyanGoal {

    public GoalNear {
        if (rangeBlocks < 0) {
            throw new IllegalArgumentException("rangeBlocks must be >= 0, got " + rangeBlocks);
        }
    }

    @Override
    public boolean hasReached(BlockPos botPos) {
        int dx = Math.abs(botPos.getX() - target.getX());
        int dy = Math.abs(botPos.getY() - target.getY());
        int dz = Math.abs(botPos.getZ() - target.getZ());
        return Math.max(dx, Math.max(dy, dz)) <= rangeBlocks;
    }

    @Override
    public String describe() {
        return "near(" + target.toShortString() + " ±" + rangeBlocks + ")";
    }
}
