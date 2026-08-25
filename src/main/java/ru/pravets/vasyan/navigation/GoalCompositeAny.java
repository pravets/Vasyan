package ru.pravets.vasyan.navigation;

import net.minecraft.core.BlockPos;

import java.util.Arrays;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Composite goal: reached when ANY of the wrapped sub-goals is reached (logical OR).
 * Short-circuits on the first satisfied sub-goal.
 *
 * @param goals wrapped sub-goals (defensively copied; the accessor returns a copy too)
 */
public record GoalCompositeAny(VasyanGoal[] goals) implements VasyanGoal {

    /** Defensively copies on construction and again on access so callers can never mutate. */
    public GoalCompositeAny {
        goals = goals.clone();
        if (goals.length == 0) {
            throw new IllegalArgumentException("any(...) requires at least one goal");
        }
    }

    @Override
    public VasyanGoal[] goals() {
        return goals.clone();
    }

    @Override
    public boolean hasReached(BlockPos botPos) {
        for (VasyanGoal goal : goals) {
            if (goal.hasReached(botPos)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public String describe() {
        return Stream.of(goals)
                .map(VasyanGoal::describe)
                .collect(Collectors.joining(", ", "any[", "]"));
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof GoalCompositeAny other && Arrays.equals(goals, other.goals);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(goals);
    }
}
