package ru.pravets.vasyan.navigation;

import net.minecraft.core.BlockPos;

/**
 * Side-adjacency goal: reached only when the bot stands on one of the four horizontal
 * neighbors of the block — manhattan XZ distance == 1 with equal Y.
 * <p>
 * Standing ON TOP of the block (Y + 1) is deliberately NOT adjacency, matching the
 * behavior contract of {@code test_adjacent_stand}.
 *
 * @param block the block to stand beside
 */
public record GoalAdjacent(BlockPos block) implements VasyanGoal {

    @Override
    public boolean hasReached(BlockPos botPos) {
        int dx = Math.abs(botPos.getX() - block.getX());
        int dy = botPos.getY() - block.getY();
        int dz = Math.abs(botPos.getZ() - block.getZ());
        return dx + dz == 1 && dy == 0;
    }

    @Override
    public String describe() {
        return "adjacent(" + block.toShortString() + ")";
    }
}
