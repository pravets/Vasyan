package ru.pravets.vasyan.navigation;

import net.minecraft.core.BlockPos;

/**
 * Planar goal: reached when the bot is within ±1 on both X and Z of the target column;
 * the Y coordinate is ignored entirely.
 *
 * @param x target X coordinate
 * @param z target Z coordinate
 */
public record GoalXZ(int x, int z) implements VasyanGoal {

    @Override
    public boolean hasReached(BlockPos botPos) {
        return Math.abs(botPos.getX() - x) <= 1 && Math.abs(botPos.getZ() - z) <= 1;
    }

    @Override
    public String describe() {
        return "xz(" + x + ", " + z + ")";
    }
}
