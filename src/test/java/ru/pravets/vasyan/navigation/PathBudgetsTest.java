package ru.pravets.vasyan.navigation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure unit tests for {@link PathBudgets}. No Minecraft classes involved, so no bootstrap needed.
 */
class PathBudgetsTest {

    private static final long MS = 1_000_000L; // nanoseconds per millisecond

    @Test
    void startSetsDeadlinesFromTimeoutsAndPassesRadiusThrough() {
        long now = 1_000L * MS;
        var budgets = PathBudgets.start(now, 2_000L, 10L, 64);

        assertEquals(now + 2_000L * MS, budgets.thinkDeadlineNano());
        assertEquals(now + 10L * MS, budgets.tickDeadlineNano());
        assertEquals(64, budgets.searchRadius(), "radius must pass through unchanged");
    }

    @Test
    void thinkNotExpiredInsideBudgetButExpiredOneMillisecondPastIt() {
        // Brief-mandated case: start with a 1000 ms think budget.
        long now = 0L;
        var budgets = PathBudgets.start(now, 1_000L, 10L, 64);

        assertFalse(budgets.thinkExpired(now));
        assertFalse(budgets.thinkExpired(now + 999L * MS), "boundary is exclusive: deadline itself not expired");
        assertTrue(budgets.thinkExpired(now + 1000L * MS), "deadline instant counts as expired");
        assertTrue(budgets.thinkExpired(now + 1001L * MS), "now+1001ms must be expired");
    }

    @Test
    void tickNotExpiredInsideBudgetButExpiredAtDeadline() {
        long now = 500L * MS;
        var budgets = PathBudgets.start(now, 60_000L, 10L, 64);

        assertFalse(budgets.tickExpired(now));
        assertFalse(budgets.tickExpired(now + 9L * MS));
        assertTrue(budgets.tickExpired(now + 10L * MS));
    }

    @Test
    void negativeNanoTimeStartDoesNotThrowAndExpiryIsCorrect() {
        long now = -9_000_000_000L;
        var budgets = PathBudgets.start(now, 2000L, 10L, 64);

        long thinkDeadline = now + 2000L * MS;
        assertFalse(budgets.thinkExpired(now));
        assertFalse(budgets.thinkExpired(thinkDeadline - 1L), "one nanosecond before deadline is not expired");
        assertTrue(budgets.thinkExpired(thinkDeadline), "deadline instant counts as expired");
        assertTrue(budgets.thinkExpired(thinkDeadline + 1L), "one nanosecond after deadline is expired");
    }

    @Test
    void nextTickRollsOnlyTheTickDeadline() {
        long now = 7_000L * MS;
        var budgets = PathBudgets.start(now, 2_000L, 10L, 64);

        long later = now + 4L * MS;
        var rolled = budgets.nextTick(later);

        assertEquals(now + 2_000L * MS, rolled.thinkDeadlineNano(), "think budget must stay untouched");
        assertEquals(later + 10L * MS, rolled.tickDeadlineNano(), "tick deadline rolls from the new 'now'");
        assertEquals(64, rolled.searchRadius(), "radius passes through nextTick");
    }

    @Test
    void nextTickCanBeChainedRepeatedly() {
        long now = 0L;
        var budgets = PathBudgets.start(now, 1_000L, 5L, 16);

        var b1 = budgets.nextTick(now + 3L * MS);
        var b2 = b1.nextTick(now + 6L * MS);
        var b3 = b2.nextTick(now + 20L * MS);

        assertEquals(now + 1_000L * MS, b3.thinkDeadlineNano());
        assertEquals(now + 25L * MS, b3.tickDeadlineNano());
    }

    @Test
    void negativeThinkTimeoutIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> PathBudgets.start(0L, -1L, 10L, 64));
    }

    @Test
    void nonPositiveTickTimeoutOrRadiusIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> PathBudgets.start(0L, 2000L, 0L, 64));
        assertThrows(IllegalArgumentException.class, () -> PathBudgets.start(0L, 2000L, 10L, 0));
        assertThrows(IllegalArgumentException.class, () -> PathBudgets.start(0L, 2000L, 10L, -5));
    }
}
