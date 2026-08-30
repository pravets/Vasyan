package ru.pravets.vasyan.navigation;

import net.minecraft.core.BlockPos;

/**
 * Goal reached when the bot is horizontally within {@code rangeBlocks} of
 * {@link #target}, ignoring the Y coordinate entirely. Distance is the max
 * absolute X/Z delta: {@code max(|dx|, |dz|)}. The boundary is inclusive.
 * <p>
 * Used for look-out stations whose Y is a phantom height (origin + offset)
 * rather than real terrain, so strict 3D proximity would fail on sloped ground.
 *
 * @param target      target block position
 * @param rangeBlocks inclusive horizontal range in blocks on X and Z axes
 */
public record GoalHorizontalNear(BlockPos target, int rangeBlocks) implements VasyanGoal {

    public GoalHorizontalNear {
        if (rangeBlocks < 0) {
            throw new IllegalArgumentException("rangeBlocks must be >= 0, got " + rangeBlocks);
        }
    }

    @Override
    public boolean hasReached(BlockPos botPos) {
        int dx = Math.abs(botPos.getX() - target.getX());
        int dz = Math.abs(botPos.getZ() - target.getZ());
        return Math.max(dx, dz) <= rangeBlocks;
    }

    @Override
    public String describe() {
        return "horizontalNear(" + target.toShortString() + " ±" + rangeBlocks + ")";
    }
}
