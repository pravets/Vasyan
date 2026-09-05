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
 * <p>Success means the selected target goal is reached; failure is either an
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
    /** Set once the bot visibly starts moving: planning has succeeded. */
    private BlockPos firstPosition;

    public PathfindAction(VasyanEntity vasyan, Task task) {
        super(vasyan, task);
    }

    @Override
    protected void onStart() {
        int x = task.getIntParameter("x", 0);
        int y = task.getIntParameter("y", 0);
        int z = task.getIntParameter("z", 0);

        targetPos = new BlockPos(x, y, z);
        goal = levelTargetIsSolid() ? VasyanGoal.adjacent(targetPos)
            : VasyanGoal.near(targetPos, GOAL_RANGE_BLOCKS);
        budgets = createBudgets();
        monitor = VasyanPathing.moveTo(vasyan, goal, budgets);
        ru.pravets.vasyan.VasyanMod.LOGGER.info(
            "Vasyan '{}': pathfind start target={}", vasyan.getVasyanName(), targetPos);
    }

    private boolean levelTargetIsSolid() {
        var state = vasyan.level().getBlockState(targetPos);
        return !state.isAir() && !state.canBeReplaced();
    }

    /** Test seam exposing the resolved goal without exposing action state publicly. */
    VasyanGoal goalForTargetForTest() {
        return goal;
    }

    /**
     * Builds the time budgets for this pathfinding attempt from the navigation config.
     * Package-private so tests can inject deterministic (e.g. already expired) budgets.
     *
     * @return fresh budgets with a tick-based think deadline started at the current game tick
     */
    PathBudgets createBudgets() {
        return PathBudgets.startInTicks(vasyan.level().getGameTime(),
            VasyanConfig.NAV_THINK_TIMEOUT_MS.get(),
            VasyanConfig.NAV_TICK_TIMEOUT_MS.get(),
            VasyanConfig.NAV_SEARCH_RADIUS.get());
    }

    @Override
    protected void onTick() {
        long nowNano = System.nanoTime();
        long gameTime = vasyan.level().getGameTime();
        // The think budget bounds PLANNING: the time between the action start and
        // the bot visibly starting to move. Once the bot moves, the monitor's own
        // budgets (stall windows, paced replans, ladder) govern the rest - a long
        // walk or recovery is not a planning failure.
        BlockPos current = vasyan.blockPosition();
        if (firstPosition == null) {
            firstPosition = current;
        }
        boolean everMoved = !current.equals(firstPosition);
        if (budgets.thinkExpiredTicks(gameTime) && !everMoved && !monitor.inLadderRecovery()) {
            ru.pravets.vasyan.VasyanMod.LOGGER.debug(
                "Vasyan '{}': pathfind budget exhausted before any movement",
                vasyan.getVasyanName());
            result = ActionResult.failure("Pathfinding budget exhausted (think timeout)");
            return;
        }
        if (monitor.finished()) {
            ru.pravets.vasyan.VasyanMod.LOGGER.info(
                "Vasyan '{}': pathfind gave up towards {}", vasyan.getVasyanName(), targetPos);
            result = ActionResult.failure("Gave up: " + monitor.goal().describe());
            return;
        }
        if (goal.hasReached(vasyan.blockPosition())) {
            // A position inside lava is not a legitimate arrival: the intended goal cell
            // may lie under/inside a lava pocket, and reaching it by standing in the flow
            // would be an unsafe descent. Refuse instead of reporting success, mirroring
            // VasyanPathing.giveUp so the behavior test sees "giving up on near(...)".
            if (vasyan.isInLava()) {
                ru.pravets.vasyan.VasyanMod.LOGGER.warn(
                    "Vasyan '{}': giving up on {}", vasyan.getVasyanName(), monitor.goal().describe());
                vasyan.getNavigation().stop();
                result = ActionResult.failure("Gave up: " + monitor.goal().describe());
                return;
            }
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
