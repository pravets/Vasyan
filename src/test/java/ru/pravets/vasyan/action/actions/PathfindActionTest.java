package ru.pravets.vasyan.action.actions;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ru.pravets.vasyan.action.ActionResult;
import ru.pravets.vasyan.action.Task;
import ru.pravets.vasyan.entity.VasyanEntity;
import ru.pravets.vasyan.entity.VasyanInventory;
import ru.pravets.vasyan.navigation.PathBudgets;
import ru.pravets.vasyan.testutil.AbstractMinecraftTest;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests the routing of {@link ru.pravets.vasyan.navigation.PathMonitor} decisions into
 * {@link ActionResult} inside {@link PathfindAction}: give-up ends in a descriptive failure,
 * reaching the goal ends in success, an exhausted think budget ends in a budget failure.
 * Navigation is a stub ({@code isDone()} controlled per case); the real {@code moveTo} call
 * itself is out of scope per plan.
 *
 * @author Iosif Pravets &lt;i@pravets.ru&gt;
 */
class PathfindActionTest extends AbstractMinecraftTest {

    /** Safety bound for the tick loop: the rewritten action must terminate well below it. */
    private static final int MAX_TEST_TICKS = 500;

    private VasyanEntity vasyan;
    private Level level;
    private PathNavigation navigation;

    @BeforeEach
    void setUpMocks() {
        vasyan = mock(VasyanEntity.class);
        level = mock(Level.class);
        navigation = mock(PathNavigation.class);
        VasyanInventory inventory = mock(VasyanInventory.class);

        // World observations: server-side, everything around the bot is air, nothing to place.
        when(level.isClientSide()).thenReturn(false);
        when(level.getBlockState(any(BlockPos.class))).thenReturn(Blocks.AIR.defaultBlockState());
        when(vasyan.level()).thenReturn(level);
        when(vasyan.getNavigation()).thenReturn(navigation);
        when(vasyan.getVasyanName()).thenReturn("TestVasyan");
        when(vasyan.getDirection()).thenReturn(Direction.NORTH);
        when(vasyan.getInventory()).thenReturn(inventory);
        when(inventory.getStacks()).thenReturn(List.of());
    }

    /**
     * Builds a PathfindAction towards (x, y, z) with the bot standing at (bx, by, bz) and
     * fixed injected budgets (the package-private seam), so cases stay deterministic and do
     * not depend on wall-clock time.
     */
    private PathfindAction actionAt(int x, int y, int z, int bx, int by, int bz, PathBudgets budgets) {
        when(vasyan.blockPosition()).thenReturn(new BlockPos(bx, by, bz));
        Task task = new Task("pathfind", Map.of("x", x, "y", y, "z", z));
        return new PathfindAction(vasyan, task) {
            @Override
            PathBudgets createBudgets() {
                return budgets;
            }
        };
    }

    @Test
    void monitorGiveUpRoutesToFailureWithGoalDescribe() {
        // Far-away target, navigation permanently "done": the monitor burns its off-goal
        // replans and must end in GIVE_UP; the action must translate that into a failure
        // instead of blindly re-issuing moveTo forever like the old implementation.
        PathfindAction action = actionAt(100_000, 64, 100_000, 0, 64, 0,
            PathBudgets.start(System.nanoTime(), 60_000L, 50L, 16));

        action.start();
        int ticks = 0;
        while (!action.isComplete() && ++ticks < MAX_TEST_TICKS) {
            action.tick();
        }

        ActionResult result = action.getResult();
        assertNotNull(result, "action must terminate through monitor give-up, not spin forever");
        assertFalse(result.isSuccess(), "give-up must route to a failure result");
        assertTrue(result.getMessage().startsWith("Gave up:"),
            "failure message must come from the give-up routing, got: " + result.getMessage());
        assertTrue(ticks < MAX_TEST_TICKS,
            "blind re-moveTo loop detected: still running after " + ticks + " ticks");
        verify(navigation, atLeastOnce()).moveTo(anyDouble(), anyDouble(), anyDouble(), anyDouble());
        verify(navigation, atLeastOnce()).stop();
    }

    @Test
    void goalReachedRoutesToSuccessMessage() {
        // Bot stands within the ±2 range of GoalNear(target, 2): first tick must succeed.
        PathfindAction action = actionAt(15, 64, 20, 17, 63, 19,
            PathBudgets.start(System.nanoTime(), 60_000L, 50L, 16));

        action.start();
        action.tick();

        ActionResult result = action.getResult();
        assertNotNull(result, "first tick with the goal reached must finish the action");
        assertTrue(result.isSuccess(), "reached goal must route to a success result");
        assertEquals("Reached target position", result.getMessage());
        verify(navigation, never()).stop();
    }

    @Test
    void expiredThinkBudgetFailsImmediatelyWithBudgetMessage() {
        // Budgets injected through the seam are already expired: the very first tick must
        // fail with the budget message before any monitor enforcement happens.
        PathfindAction action = actionAt(15, 64, 20, 0, 64, 0,
            PathBudgets.start(System.nanoTime() - 5_000_000L, 1L, 1L, 16));

        action.start();
        action.tick();

        ActionResult result = action.getResult();
        assertNotNull(result, "expired think budget must finish the action on the first tick");
        assertFalse(result.isSuccess(), "exhausted think budget must route to a failure result");
        assertEquals("Pathfinding budget exhausted (think timeout)", result.getMessage());
    }
}
