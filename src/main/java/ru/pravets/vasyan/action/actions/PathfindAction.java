package ru.pravets.vasyan.action.actions;

import ru.pravets.vasyan.action.ActionResult;
import ru.pravets.vasyan.action.Task;
import ru.pravets.vasyan.config.VasyanConfig;
import ru.pravets.vasyan.entity.VasyanEntity;
import ru.pravets.vasyan.navigation.PathBudgets;
import ru.pravets.vasyan.navigation.PathMonitor;
import ru.pravets.vasyan.navigation.VasyanGoal;
import ru.pravets.vasyan.navigation.VasyanPathing;
import net.minecraft.core.BlockPos;

/**
 * Action moving the bot to a target block position under {@link PathMonitor} control:
 * start issues a single monitored {@code moveTo}, every tick rolls time budgets and runs
 * {@code enforce()} so monitor decisions (replan / dig / scaffold / hop teleport) are
 * executed by {@link VasyanPathing}.
 *
 * <p>Success means the goal ({@code near(target, 2)}) is reached; failure is either an
 * exhausted think budget or the monitor giving up after all recovery steps failed. There
 * is no hard tick cap and no blind re-issue loop - the monitor owns stall and replan
 * accounting.</p>
 *
 * @author Iosif Pravets &lt;i@pravets.ru&gt;
 */
public class PathfindAction extends BaseAction {

    /** Range in blocks around the target accepted as "arrived" on each axis. */
    private static final int GOAL_RANGE_BLOCKS = 2;

    private BlockPos targetPos;
    private VasyanGoal goal;
    private PathMonitor monitor;
    private PathBudgets budgets;

    public PathfindAction(VasyanEntity vasyan, Task task) {
        super(vasyan, task);
    }

    @Override
    protected void onStart() {
        int x = task.getIntParameter("x", 0);
        int y = task.getIntParameter("y", 0);
        int z = task.getIntParameter("z", 0);

        targetPos = new BlockPos(x, y, z);
        goal = VasyanGoal.near(targetPos, GOAL_RANGE_BLOCKS);
        budgets = createBudgets();
        monitor = VasyanPathing.moveTo(vasyan, goal, budgets);
        ru.pravets.vasyan.VasyanMod.LOGGER.info(
            "Vasyan '{}': pathfind start target={}", vasyan.getVasyanName(), targetPos);
    }

    /**
     * Builds the time budgets for this pathfinding attempt from the navigation config.
     * Package-private so tests can inject deterministic (e.g. already expired) budgets.
     *
     * @return fresh budgets started at the current nano time
     */
    PathBudgets createBudgets() {
        return PathBudgets.start(System.nanoTime(),
            VasyanConfig.NAV_THINK_TIMEOUT_MS.get(),
            VasyanConfig.NAV_TICK_TIMEOUT_MS.get(),
            VasyanConfig.NAV_SEARCH_RADIUS.get());
    }

    @Override
    protected void onTick() {
        long nowNano = System.nanoTime();
        // The think budget bounds a single PATH PLANNING attempt (a path that is
        // being followed too long), not recovery: the monitor's fallback ladder
        // (paced replans + dig/scaffold/teleport) legitimately takes minutes and
        // terminates itself with finished()==true when exhausted.
        boolean hasPath = !vasyan.getNavigation().isDone();
        if (budgets.thinkExpired(nowNano) && hasPath) {
            result = ActionResult.failure("Pathfinding budget exhausted (think timeout)");
            return;
        }
        if (monitor.finished()) {
            ru.pravets.vasyan.VasyanMod.LOGGER.info(
                "Vasyan '{}': pathfind GAVE UP towards {}", vasyan.getVasyanName(), targetPos);
            result = ActionResult.failure("Gave up: " + monitor.goal().describe());
            return;
        }
        if (goal.hasReached(vasyan.blockPosition())) {
            result = ActionResult.success("Reached target position");
            return;
        }
        budgets = budgets.nextTick(nowNano);
        VasyanPathing.enforce(vasyan, monitor);
    }

    @Override
    protected void onCancel() {
        vasyan.getNavigation().stop();
    }

    @Override
    public String getDescription() {
        return "Pathfind to " + targetPos;
    }
}
