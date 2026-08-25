package ru.pravets.vasyan.action.actions;

import ru.pravets.vasyan.action.ActionResult;
import ru.pravets.vasyan.action.Task;
import ru.pravets.vasyan.entity.VasyanEntity;
import ru.pravets.vasyan.navigation.PathBudgets;
import ru.pravets.vasyan.navigation.PathMonitor;
import ru.pravets.vasyan.navigation.VasyanGoal;
import ru.pravets.vasyan.navigation.VasyanPathing;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;

/**
 * Long-lived follow action driven by {@link PathMonitor}.
 *
 * <p>Replans naturally when the player moves beyond the current goal range;
 * obstacle handling (replan/dig/scaffold/hop ladder) is owned by the monitor
 * via {@link VasyanPathing#enforce}, not duplicated here.</p>
 */
public class FollowPlayerAction extends BaseAction {
    private String playerName;
    private Player targetPlayer;
    private int ticksRunning;
    private static final int MAX_TICKS = 6000; // 5 minutes

    private PathMonitor followMonitor;
    private PathBudgets followBudgets;
    private BlockPos followedGoalPos;

    public FollowPlayerAction(VasyanEntity vasyan, Task task) {
        super(vasyan, task);
    }

    @Override
    protected void onStart() {
        playerName = task.getStringParameter("player");
        ticksRunning = 0;
        followMonitor = null;
        followBudgets = null;
        followedGoalPos = null;

        findPlayer();

        if (targetPlayer == null) {
            result = ActionResult.failure("Player not found: " + playerName);
        }
    }

    @Override
    protected void onTick() {
        ticksRunning++;

        if (ticksRunning > MAX_TICKS) {
            result = ActionResult.success("Stopped following");
            return;
        }

        if (targetPlayer == null || !targetPlayer.isAlive() || targetPlayer.isRemoved()) {
            findPlayer();
            if (targetPlayer == null) {
                result = ActionResult.failure("Lost track of player");
                return;
            }
        }

        double distance = vasyan.distanceTo(targetPlayer);
        if (distance <= 2.0) {
            // Close enough: hold position.
            vasyan.getNavigation().stop();
            return;
        }

        BlockPos playerBlock = targetPlayer.blockPosition();
        boolean needNewRoute = followedGoalPos == null
            || !playerBlock.closerThan(followedGoalPos, FOLLOW_REPLAN_DISTANCE);

        if (needNewRoute) {
            startRoute(playerBlock);
        } else if (followMonitor != null && followBudgets != null) {
            long nowNano = System.nanoTime();
            followBudgets = followBudgets.nextTick(nowNano);
            if (followBudgets.thinkExpired(nowNano)) {
                // Following is long-lived: a slow route is replanned, never failed.
                startRoute(playerBlock);
                return;
            }
            VasyanPathing.enforce(vasyan, followMonitor);
            if (followMonitor.finished() && !followMonitor.goal().hasReached(vasyan.blockPosition())) {
                // One fresh monitored route; the monitor's own navDoneReplans
                // already bounded obstacle churn inside the previous attempt.
                startRoute(playerBlock);
            }
        }
    }

    /** Starts a fresh monitored route toward the player's current block. */
    private void startRoute(BlockPos playerBlock) {
        followedGoalPos = playerBlock;
        long nowNano = System.nanoTime();
        followBudgets = PathBudgets.start(nowNano,
            ru.pravets.vasyan.config.VasyanConfig.NAV_THINK_TIMEOUT_MS.get(),
            ru.pravets.vasyan.config.VasyanConfig.NAV_TICK_TIMEOUT_MS.get(),
            ru.pravets.vasyan.config.VasyanConfig.NAV_SEARCH_RADIUS.get());
        followMonitor = VasyanPathing.moveTo(vasyan, VasyanGoal.near(playerBlock, 2), followBudgets);
    }

    @Override
    protected void onCancel() {
        vasyan.getNavigation().stop();
    }

    @Override
    public String getDescription() {
        return "Follow player " + playerName;
    }

    /** Distance the player must move from the routed goal before a fresh route starts. */
    private static final double FOLLOW_REPLAN_DISTANCE = 4.0;

    private void findPlayer() {
        java.util.List<? extends Player> players = vasyan.level().players();

        // First try exact name match
        for (Player player : players) {
            if (player.getName().getString().equalsIgnoreCase(playerName)) {
                targetPlayer = player;
                return;
            }
        }

        if (playerName != null && (playerName.contains("PLAYER") || playerName.contains("NAME") ||
            playerName.equalsIgnoreCase("me") || playerName.equalsIgnoreCase("you") || playerName.isEmpty())) {
            Player nearest = null;
            double nearestDistance = Double.MAX_VALUE;

            for (Player player : players) {
                double distance = vasyan.distanceTo(player);
                if (distance < nearestDistance) {
                    nearest = player;
                    nearestDistance = distance;
                }
            }

            if (nearest != null) {
                targetPlayer = nearest;
                playerName = nearest.getName().getString(); // Update to actual name
                ru.pravets.vasyan.VasyanMod.LOGGER.info("Vasyan '{}' following nearest player: {}",
                    vasyan.getVasyanName(), playerName);
            }
        }
    }
}
