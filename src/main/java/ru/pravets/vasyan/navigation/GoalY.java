package ru.pravets.vasyan.navigation;

import net.minecraft.core.BlockPos;

/**
 * Vertical goal: reached when the bot is within ±1 of the target Y level;
 * X and Z are ignored entirely.
 *
 * @param y target Y level
 */
public record GoalY(int y) implements VasyanGoal {

    @Override
    public boolean hasReached(BlockPos botPos) {
        return Math.abs(botPos.getY() - y) <= 1;
    }

    @Override
    public String describe() {
        return "y(" + y + ")";
    }
}
