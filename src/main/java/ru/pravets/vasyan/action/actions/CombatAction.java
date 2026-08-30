package ru.pravets.vasyan.action.actions;

import ru.pravets.vasyan.action.ActionResult;
import ru.pravets.vasyan.action.Task;
import ru.pravets.vasyan.entity.VasyanEntity;
import ru.pravets.vasyan.navigation.PathBudgets;
import ru.pravets.vasyan.navigation.PathMonitor;
import ru.pravets.vasyan.navigation.VasyanGoal;
import ru.pravets.vasyan.navigation.VasyanPathing;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.phys.AABB;

import java.util.List;

/**
 * Combat action driven by {@link PathMonitor} for approach movement.
 *
 * <p>The monitor's fallback ladder (replan / dig / scaffold / hop-teleport)
 * replaces the old manual stuck-teleport crutch; combat targeting, attacking
 * and cooldowns are unchanged.</p>
 */
public class CombatAction extends BaseAction {
    private String targetType;
    private LivingEntity target;
    private int ticksRunning;
    private static final int MAX_TICKS = 600;
    private static final double ATTACK_RANGE = 3.5;
    /** Navigation speed while approaching (matches the old pre-monitor trot). */
    private static final double COMBAT_NAV_SPEED = 2.5;
    /** Re-route only when the target has moved more than 4 blocks from the last routed position. */
    private static final double RETARGET_DISTANCE_SQ = 16.0;

    private PathMonitor routeMonitor;
    private PathBudgets routeBudgets;
    private BlockPos routeTargetPos;
    /** Bot cell when the current route began (planning-only bail). */
    private BlockPos routeStartPos;

    public CombatAction(VasyanEntity vasyan, Task task) {
        super(vasyan, task);
    }

    @Override
    protected void onStart() {
        targetType = task.getStringParameter("target");
        ticksRunning = 0;
        routeMonitor = null;
        routeBudgets = null;
        routeTargetPos = null;

        // Make sure we're not flying (in case we were building)
        vasyan.setFlying(false);

        vasyan.setInvulnerableBuilding(true);

        findTarget();

        if (target == null) {
            ru.pravets.vasyan.VasyanMod.LOGGER.warn("Vasyan '{}' no targets nearby", vasyan.getVasyanName());
        }
    }

    @Override
    protected void onTick() {
        ticksRunning++;

        if (ticksRunning > MAX_TICKS) {
            finishCombat("Combat complete");
            result = ActionResult.success("Combat complete");
            return;
        }

        // Re-search for targets periodically or if current target is invalid
        if (target == null || !target.isAlive() || target.isRemoved()) {
            if (ticksRunning % 20 == 0) {
                findTarget();
            }
            if (target == null) {
                return; // Keep searching
            }
        }

        double distance = vasyan.distanceTo(target);

        vasyan.setSprinting(true);

        if (distance <= ATTACK_RANGE) {
            // Park at the target instead of drifting along a stale route path.
            vasyan.getNavigation().stop();
            vasyan.doHurtTarget(target);
            vasyan.swing(net.minecraft.world.InteractionHand.MAIN_HAND, true);

            // Attack 3 times per second (every 6-7 ticks)
            if (ticksRunning % 7 == 0) {
                vasyan.doHurtTarget(target);
            }
            return;
        }

        // Approach phase: monitor-driven movement toward the target's block.
        BlockPos targetBlock = target.blockPosition();
        if (routeMonitor == null || routeTargetPos == null) {
            startRoute(targetBlock);
        } else if (targetBlock.distSqr(routeTargetPos) > RETARGET_DISTANCE_SQ) {
            routeMonitor.retarget(targetBlock);
            routeTargetPos = targetBlock;
            routeStartPos = vasyan.blockPosition();
            routeBudgets = PathBudgets.startInTicks(vasyan.level().getGameTime(),
                ru.pravets.vasyan.config.VasyanConfig.NAV_THINK_TIMEOUT_MS.get(),
                ru.pravets.vasyan.config.VasyanConfig.NAV_TICK_TIMEOUT_MS.get(),
                ru.pravets.vasyan.config.VasyanConfig.NAV_SEARCH_RADIUS.get());
            VasyanPathing.replan(vasyan, routeMonitor);
            // steerTo() preserves an active building-invulnerability flag across moveTo/replan.
        }

        if (routeMonitor != null && routeBudgets != null) {
            long nowNano = System.nanoTime();
            long gameTime = vasyan.level().getGameTime();
            routeBudgets = routeBudgets.nextTick(nowNano);
            // think-budget bounds PLANNING only: fail only if the route never got
            // moving inside the budget - once the bot advances, the monitor's own
            // budgets (stall windows + paced replans + recovery ladder) govern.
            boolean moved = routeStartPos != null
                && !vasyan.blockPosition().equals(routeStartPos);
            if (routeBudgets.thinkExpiredTicks(gameTime) && !moved && !routeMonitor.inLadderRecovery()) {
                finishCombat("Cannot reach combat target");
                result = ActionResult.failure("Cannot reach combat target");
                return;
            }
            VasyanPathing.enforce(vasyan, routeMonitor);
            if (routeMonitor.finished()) {
                finishCombat("Cannot reach combat target");
                result = ActionResult.failure("Cannot reach combat target");
            }
        }
    }

    /** Cleans up combat state (invulnerability, sprint, nav) before any exit. */
    private void finishCombat(String reason) {
        vasyan.setInvulnerableBuilding(false);
        vasyan.setSprinting(false);
        vasyan.getNavigation().stop();
        ru.pravets.vasyan.VasyanMod.LOGGER.info("Vasyan '{}' combat finished: {}",
            vasyan.getVasyanName(), reason);
    }

    /** Starts a fresh monitored route toward the given target block. */
    private void startRoute(BlockPos targetBlock) {
        routeTargetPos = targetBlock;
        routeStartPos = vasyan.blockPosition();
        routeBudgets = PathBudgets.startInTicks(vasyan.level().getGameTime(),
            ru.pravets.vasyan.config.VasyanConfig.NAV_THINK_TIMEOUT_MS.get(),
            ru.pravets.vasyan.config.VasyanConfig.NAV_TICK_TIMEOUT_MS.get(),
            ru.pravets.vasyan.config.VasyanConfig.NAV_SEARCH_RADIUS.get());
        routeMonitor = VasyanPathing.moveTo(vasyan, VasyanGoal.near(targetBlock, 1),
            routeBudgets, COMBAT_NAV_SPEED);
        // steerTo() preserves an active building-invulnerability flag across moveTo/replan.
    }

    @Override
    protected void onCancel() {
        vasyan.setInvulnerableBuilding(false);
        vasyan.getNavigation().stop();
        vasyan.setSprinting(false);
        vasyan.setFlying(false);
        target = null;
        ru.pravets.vasyan.VasyanMod.LOGGER.info("Vasyan '{}' combat cancelled, invulnerability disabled",
            vasyan.getVasyanName());
    }

    @Override
    public String getDescription() {
        return "Attack " + targetType;
    }

    private void findTarget() {
        AABB searchBox = vasyan.getBoundingBox().inflate(32.0);
        List<Entity> entities = vasyan.level().getEntities(vasyan, searchBox);

        LivingEntity nearest = null;
        double nearestDistance = Double.MAX_VALUE;

        for (Entity entity : entities) {
            if (entity instanceof LivingEntity living && isValidTarget(living)) {
                double distance = vasyan.distanceTo(living);
                if (distance < nearestDistance) {
                    nearest = living;
                    nearestDistance = distance;
                }
            }
        }

        target = nearest;
        if (target != null) {
            ru.pravets.vasyan.VasyanMod.LOGGER.info("Vasyan '{}' locked onto: {} at {}m",
                vasyan.getVasyanName(), target.getType().toString(), (int)nearestDistance);
        }
    }

    private boolean isValidTarget(LivingEntity entity) {
        if (!entity.isAlive() || entity.isRemoved()) {
            return false;
        }

        // Don't attack other Vasyans or players
        if (entity instanceof VasyanEntity || entity instanceof net.minecraft.world.entity.player.Player) {
            return false;
        }

        String targetLower = targetType.toLowerCase();

        // Match ANY hostile mob
        if (targetLower.contains("mob") || targetLower.contains("hostile") ||
            targetLower.contains("monster") || targetLower.equals("any")) {
            return entity instanceof Monster;
        }

        // Match specific entity type
        String entityTypeName = entity.getType().toString().toLowerCase();
        return entityTypeName.contains(targetLower);
    }
}
