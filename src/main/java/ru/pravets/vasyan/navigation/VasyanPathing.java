package ru.pravets.vasyan.navigation;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.pathfinder.Path;

import org.jetbrains.annotations.Nullable;

import ru.pravets.vasyan.VasyanMod;
import ru.pravets.vasyan.config.VasyanConfig;
import ru.pravets.vasyan.entity.VasyanEntity;
import ru.pravets.vasyan.entity.VasyanTeleportUtil;

import java.util.Objects;
import java.util.Optional;

/**
 * Server-side glue translating {@link PathMonitor} decisions into real navigation/world
 * actions: rebuild the path, dig through an obstructing block, place a scaffold block from
 * the inventory, or make a one-shot hop teleport past the obstacle.
 *
 * <p>The class is completely stateless. Budget ownership lives with the CALLER: an action
 * creates its budgets once via {@link PathBudgets#start}, rolls {@link PathBudgets#nextTick}
 * and checks {@code thinkExpired}/{@code tickExpired} itself every server tick, and obtains
 * a fresh monitor per attempt from {@link #moveTo}. Nothing is cached here between ticks.</p>
 *
 * <p>Binding contracts honored (Task 3 review rulings):
 * <ul>
 *   <li>{@code hasPath} fed to the monitor means "a path is currently assigned", implemented
 *       as {@code !navigation.isDone()}; the monitor detects "navigation finished outside the
 *       goal" as {@code navDone &amp;&amp; !hasPath};</li>
 *   <li>after a visibly successful DIG_THROUGH / PLACE_SCAFFOLD / HOP_TELEPORT the glue calls
 *       {@link PathMonitor#onRecoverySuccess()} so the step gets a fresh grace window WITHOUT
 *       pretending the bot moved; a failed step logs and skips the callback, letting the grace
 *       window expire and the ladder move on.</li>
 * </ul></p>
 *
 * <p>Thin glue by design: every decision comes from {@link PathMonitor}, each executed branch
 * is small and logged, and no unit tests are required per plan - the correctness gate is
 * {@code compileJava}, the CI Build and later behavior tests.</p>
 *
 * @author Iosif Pravets &lt;i@pravets.ru&gt;
 */
public final class VasyanPathing {

    /** Ground walking speed modifier passed to {@link PathNavigation#moveTo(double, double, double, double)}. */
    private static final double GROUND_SPEED = 1.0;

    /** Offset from a block corner to its center, used for navigation/teleport targets. */
    private static final double CENTER_OFFSET = 0.5;

    /** Maximum preparation iterations for one vertical step before giving up. */
    private static final int MAX_PREPARE_CHAIN = 4;
    /** How many cells ahead (including the bot's own cell) findDiggableAhead scans. */
    private static final int AHEAD_SCAN_STEPS = 3;

    private VasyanPathing() {
    }

    /**
     * Starts a monitored move towards the goal: switches off flight, steers ground navigation
     * to a walkable position representing the goal and returns a fresh monitor for the action
     * to tick.
     *
     * <p>Target resolution maps each goal type to concrete coordinates using the public record
     * accessors of the goal hierarchy (describe-string parsing is deliberately avoided):
     * GoalNear/GoalAdjacent contribute their block target; GoalXZ keeps the bot's current Y;
     * GoalY keeps the bot's current X/Z (straight vertical move); GoalCompositeAny picks the
     * sub-goal anchor nearest to the bot.</p>
     *
     * @param vasyan  bot to move
     * @param goal    goal to reach; must not be null
     * @param budgets time/scope budgets owned by the CALLER (see class docs); accepted for
     *                interface symmetry and future search-radius plumbing, never stored -
     *                this class holds no state
     * @return a fresh {@link PathMonitor} with default stall/replan budgets (40/3)
     */
    public static PathMonitor moveTo(VasyanEntity vasyan, VasyanGoal goal, PathBudgets budgets) {
        return moveTo(vasyan, goal, budgets, VasyanConfig.NAV_DIG_THROUGH_MAX.get());
    }

    /**
     * Starts a monitored move towards the goal with an explicit per-route tunnel
     * depth. Tree routes use a larger leaf-dig budget; all other routes keep
     * the generic tunnel cap.
     *
     * @param vasyan       bot to move
     * @param goal         goal to reach; must not be null
     * @param budgets      time/scope budgets owned by the CALLER; never stored
     * @param maxDigThrough maximum tunnel depth this route may dig through
     * @return a fresh {@link PathMonitor} with default stall/replan budgets (40/3)
     */
    public static PathMonitor moveTo(VasyanEntity vasyan, VasyanGoal goal, PathBudgets budgets,
                                     int maxDigThrough) {
        return moveTo(vasyan, goal, budgets, GROUND_SPEED, maxDigThrough);
    }

    /**
     * Moves toward {@code goal} at a caller-chosen speed. Combat and follow pass a faster
     * speed (they previously trotted at 2.5); gather/pathfind use the default ground speed.
     *
     * @param vasyan  bot to move
     * @param goal    goal to reach; must not be null
     * @param budgets time/scope budgets owned by the CALLER; never stored
     * @param speed   navigation speed to steer at
     * @return a fresh {@link PathMonitor} with default stall/replan budgets (40/3)
     */
    public static PathMonitor moveTo(VasyanEntity vasyan, VasyanGoal goal, PathBudgets budgets,
                                      double speed) {
        return moveTo(vasyan, goal, budgets, speed, VasyanConfig.NAV_DIG_THROUGH_MAX.get());
    }

    /**
     * Moves toward {@code goal} at a caller-chosen speed with an explicit tunnel depth.
     *
     * @param vasyan       bot to move
     * @param goal         goal to reach; must not be null
     * @param budgets      time/scope budgets owned by the CALLER; never stored
     * @param speed        navigation speed to steer at
     * @param maxDigThrough maximum tunnel depth this route may dig through
     * @return a fresh {@link PathMonitor} with default stall/replan budgets (40/3)
     */
    public static PathMonitor moveTo(VasyanEntity vasyan, VasyanGoal goal, PathBudgets budgets,
                                     double speed, int maxDigThrough) {
        Objects.requireNonNull(vasyan, "vasyan");
        Objects.requireNonNull(goal, "goal");
        Objects.requireNonNull(budgets, "budgets");
        if (speed <= 0) {
            throw new IllegalArgumentException("speed must be > 0, got " + speed);
        }

        BlockPos target = VasyanGoal.anchor(goal, vasyan.blockPosition());
        if (goal instanceof GoalAdjacent adjacent) {
            target = adjacentApproach(vasyan, adjacent.block());
        }
        steerTo(vasyan, target, speed);
        VasyanMod.LOGGER.info("Vasyan '{}': moveTo {} @{} steering to {}",
            vasyan.getVasyanName(), goal.describe(), speed, target.toShortString());
        VerticalRecoverySettings verticalRecovery = new VerticalRecoverySettings(
            VasyanConfig.NAV_VERTICAL_RECOVERY_ENABLED.get(),
            VasyanConfig.NAV_VERTICAL_RECOVERY_MAX_DISTANCE.get(),
            VasyanConfig.NAV_VERTICAL_RECOVERY_HORIZONTAL_RANGE.get(),
            VasyanConfig.NAV_VERTICAL_RECOVERY_MAX_SCAFFOLD_BLOCKS.get());
        return new PathMonitor(goal, PathMonitor.DEFAULT_STALL_TICKS, PathMonitor.DEFAULT_MAX_REPLANS,
            PathMonitor.DEFAULT_NAV_DONE_REPLANS, speed, verticalRecovery,
            VasyanConfig.NAV_HOP_TELEPORT_ENABLED.get(),
            maxDigThrough);
    }

    /**
     * Executes one monitoring step: feeds the current world observation into the monitor and
     * performs whatever the returned decision demands. Call once per server tick from the
     * owning action; budget expiry checks stay with the caller (see class docs).
     *
     * @param vasyan  bot under navigation
     * @param monitor monitor previously created by {@link #moveTo} for this attempt
     */
    public static void enforce(VasyanEntity vasyan, PathMonitor monitor) {
        enforce(vasyan, monitor, true);
    }

    /** Recovery capabilities granted to one route attempt. */
    public enum RecoveryPolicy {
        /** Honest give-up on any stall (no recovery at all). */
        NONE,
        /** Vertical staircase only (up AND down) to a visible exposed target:
         *  no dig-through, no forward scaffold, no hop-teleport. An exposed coal
         *  face on a pit wall is reached by climbing, never by tunneling, and
         *  never at the cost of the vein itself (Alex' pit). */
        VERTICAL_ONLY,
        /** Full ladder: replan, vertical, dig (budget-capped), scaffold, hop. */
        FULL
    }

    /**
     * Enforces the monitor decision. When {@code allowRecovery} is false, the bot will
     * not dig, place scaffold, step vertically or teleport for this route: if the goal
     * is not reachable by plain walking, it gives up. This is used for resource gathering
     * routes, where chasing an ore must never turn into tunneling.
     */
    public static void enforce(VasyanEntity vasyan, PathMonitor monitor, boolean allowRecovery) {
        enforce(vasyan, monitor, allowRecovery ? RecoveryPolicy.FULL : RecoveryPolicy.NONE);
    }

    /**
     * Enforces the monitor decision under an explicit recovery policy:
     * {@link RecoveryPolicy#NONE} gives up on any stall, {@link RecoveryPolicy#VERTICAL_ONLY}
     * permits only climbing up/down to a goal above/below the bot, {@link RecoveryPolicy#FULL} runs
     * the whole ladder.
     */
    public static void enforce(VasyanEntity vasyan, PathMonitor monitor, RecoveryPolicy policy) {
        Objects.requireNonNull(vasyan, "vasyan");
        Objects.requireNonNull(monitor, "monitor");
        if (vasyan.level().isClientSide()) {
            return; // world-mutating glue runs server-side only
        }

        PathNavigation nav = vasyan.getNavigation();
        boolean navDone = nav.isDone();
        boolean full = policy == RecoveryPolicy.FULL;
        BlockPos diggable = full ? findDiggableAhead(vasyan, monitor.goal()) : null;
        boolean canDig = full && diggable != null;
        boolean canPlace = full && ScaffoldBlocks.findBestStack(
            vasyan.getInventory(), vasyan.level(), vasyan.blockPosition(),
            VasyanConfig.NAV_SCAFFOLD_WHITELIST.get()) != null;

        // Contract: hasPath == "path currently assigned" == !navDone.
        PathMonitor.Decision decision =
            monitor.onTick(vasyan.blockPosition(), navDone, !navDone, canDig, canPlace);
        switch (decision) {
            case CONTINUE -> {
                // steady progress or goal reached: nothing to do
            }
            case REPLAN -> replan(vasyan, monitor);
            case DESCEND_STEP, ASCEND_STEP, DIG_THROUGH, PLACE_SCAFFOLD, HOP_TELEPORT -> {
                switch (decision) {
                    case DESCEND_STEP -> {
                        if (policy != RecoveryPolicy.NONE) {
                            verticalStep(vasyan, monitor, VerticalTraversalPlanner.Mode.DESCEND);
                        } else {
                            giveUp(vasyan, monitor);
                        }
                    }
                    case ASCEND_STEP -> {
                        if (policy != RecoveryPolicy.NONE) {
                            verticalStep(vasyan, monitor, VerticalTraversalPlanner.Mode.ASCEND);
                        } else {
                            giveUp(vasyan, monitor);
                        }
                    }
                    case DIG_THROUGH -> {
                        if (policy == RecoveryPolicy.FULL) {
                            // Level goal + boxed-in bot = a pit, not a wall: the
                            // vertical ladder keys off the goal's Y and never fires
                            // here, so climb out to the local surface instead of
                            // digging a tunnel (Bob's coal-pit bug).
                            BlockPos escape = pitEscapeAnchor(vasyan, monitor);
                            if (escape != null) {
                                verticalStep(vasyan, monitor, VerticalTraversalPlanner.Mode.ASCEND, escape);
                            } else {
                                digThrough(vasyan, monitor, diggable);
                            }
                        } else {
                            giveUp(vasyan, monitor);
                        }
                    }
                    case PLACE_SCAFFOLD -> {
                        if (policy == RecoveryPolicy.FULL) {
                            placeScaffold(vasyan, monitor);
                        } else {
                            giveUp(vasyan, monitor);
                        }
                    }
                    case HOP_TELEPORT -> {
                        if (policy == RecoveryPolicy.FULL) {
                            hopTeleport(vasyan, monitor);
                        } else {
                            giveUp(vasyan, monitor);
                        }
                    }
                    default -> {}
                }
            }
            case GIVE_UP -> giveUp(vasyan, monitor);
        }
    }

    /**
     * Recomputes and starts a fresh ground path towards the monitor's current goal anchor.
     *
     * <p>Steers the bot to the anchor resolved from {@link PathMonitor#goal()} at
     * {@link PathMonitor#navSpeed()}, disables flight, and preserves an active
     * building-invulnerability flag. Callers such as {@code CombatAction} use this to
     * re-route without replacing the monitor, keeping lifetime recovery budgets intact.</p>
     *
     * @param vasyan  bot to steer
     * @param monitor monitor whose goal and speed drive the new path
     */
    public static void replan(VasyanEntity vasyan, PathMonitor monitor) {
        BlockPos target = VasyanGoal.anchor(monitor.goal(), vasyan.blockPosition());
        steerTo(vasyan, target, monitor.navSpeed());
        VasyanMod.LOGGER.debug("Vasyan '{}': REPLAN towards {} ({})",
            vasyan.getVasyanName(), monitor.goal().describe(), target.toShortString());
    }

    /**
     * DESCEND_STEP / ASCEND_STEP: prepare and take one staircase step. The pure planner picks
     * the cell; this glue performs the one world mutation or movement it asks for.
     */
    private static void verticalStep(VasyanEntity vasyan, PathMonitor monitor,
                                     VerticalTraversalPlanner.Mode mode) {
        verticalStep(vasyan, monitor, mode, VasyanGoal.anchor(monitor.goal(), vasyan.blockPosition()));
    }

    /**
     * DESCEND_STEP / ASCEND_STEP with an explicit anchor (pit escape aims at the
     * local surface rather than the level route goal).
     */
    private static void verticalStep(VasyanEntity vasyan, PathMonitor monitor,
                                     VerticalTraversalPlanner.Mode mode, BlockPos anchor) {
        String name = vasyan.getVasyanName();
        Level level = vasyan.level();
        BlockPos botPos = vasyan.blockPosition();
        VerticalTraversalPlanner.WorldView world = verticalWorld(level);
        boolean maskOwnColumn = false;
        // Preparation and movement must happen in one monitor decision. If we
        // only clear/place and wait for the next stall, PathMonitor would treat
        // the successful preparation as a completed vertical step and advance
        // the ladder to DIG_THROUGH without ever steering onto the new step.
        for (int i = 0; i < MAX_PREPARE_CHAIN; i++) {
            var step = VerticalTraversalPlanner.nextStep(botPos, anchor, mode,
                maskOwnColumn ? maskingOwnColumn(world, botPos) : world);
            if (step.isEmpty()) {
                if (!maskOwnColumn && mode == VerticalTraversalPlanner.Mode.ASCEND) {
                    // Own column is unusable (e.g. no scaffold for the pillar):
                    // mask it once so the planner carves a staircase into the
                    // pit wall (CLEAR/MOVE side steps) instead of giving up.
                    maskOwnColumn = true;
                    continue;
                }
                VasyanMod.LOGGER.warn("Vasyan '{}': {} failed, no safe staircase step near {} towards {}",
                    name, mode, botPos.toShortString(), anchor.toShortString());
                return;
            }

            VerticalTraversalPlanner.Step planned = step.get();
            if (planned.action() == VerticalTraversalPlanner.Action.CLEAR) {
                if (!clearVerticalStep(vasyan, planned)) {
                    return;
                }
                continue;
            }
            if (planned.action() == VerticalTraversalPlanner.Action.PLACE_SUPPORT) {
                if (!placeVerticalSupport(vasyan, monitor, planned)) {
                    // The side step has no adjacent solid (e.g. a pit edge). Fall back to
                    // a legal pillar-up directly under the bot, supported by the floor it
                    // is already standing on.
                    if (mode == VerticalTraversalPlanner.Mode.ASCEND
                            && placeSupportAt(vasyan, monitor, botPos)) {
                        monitor.onRecoverySuccess();
                    } else if (mode == VerticalTraversalPlanner.Mode.ASCEND && !maskOwnColumn) {
                        // No scaffold blocks at all: retry once with the own
                        // column masked so the planner offers a CLEAR/MOVE
                        // wall-step the bot can take with bare hands.
                        maskOwnColumn = true;
                        continue;
                    }
                    return;
                }
                continue;
            }

            if (isOpen(level, planned.standPos()) && isOpen(level, planned.standPos().above())) {
                steerTo(vasyan, planned.standPos(), monitor.navSpeed());
                VasyanMod.LOGGER.info("Vasyan '{}': {} step to {}",
                    name, mode, planned.standPos().toShortString());
                monitor.onRecoverySuccess();
            } else {
                VasyanMod.LOGGER.warn("Vasyan '{}': {} could not enter {}",
                    name, mode, planned.standPos().toShortString());
            }
            return;
        }
        VasyanMod.LOGGER.warn("Vasyan '{}': {} exceeded preparation chain near {}",
            name, mode, botPos.toShortString());
    }

    /** Read-only world adapter for the pure vertical planner. */
    private static VerticalTraversalPlanner.WorldView verticalWorld(Level level) {
        return new VerticalTraversalPlanner.WorldView() {
            @Override
            public boolean isOpen(BlockPos pos) {
                return VasyanPathing.isOpen(level, pos);
            }

            @Override
            public boolean isSolidSupport(BlockPos pos) {
                return level.getBlockState(pos).isCollisionShapeFullBlock(level, pos);
            }

            @Override
            public boolean isBreakable(BlockPos pos) {
                return DigRules.isBreakable(level, pos, false);
            }

            @Override
            public boolean isUnsafeLiquid(BlockPos pos) {
                FluidState fluid = level.getBlockState(pos).getFluidState();
                return fluid.is(Fluids.LAVA) || fluid.is(Fluids.FLOWING_LAVA);
            }

            @Override
            public boolean isFlowingWater(BlockPos pos) {
                FluidState fluid = level.getBlockState(pos).getFluidState();
                return fluid.is(Fluids.FLOWING_WATER)
                    || (fluid.is(Fluids.WATER) && !fluid.isSource());
            }
        };
    }

    /**
     * World view with the bot's own column reported as not open: the planner then
     * skips the pillar-up own-column option and offers side CLEAR/MOVE steps
     * instead (used when the bot has no scaffold blocks to place).
     */
    private static VerticalTraversalPlanner.WorldView maskingOwnColumn(
            VerticalTraversalPlanner.WorldView delegate, BlockPos own) {
        return new VerticalTraversalPlanner.WorldView() {
            @Override
            public boolean isOpen(BlockPos pos) {
                return !pos.equals(own) && delegate.isOpen(pos);
            }

            @Override
            public boolean isSolidSupport(BlockPos pos) {
                return delegate.isSolidSupport(pos);
            }

            @Override
            public boolean isBreakable(BlockPos pos) {
                return delegate.isBreakable(pos);
            }

            @Override
            public boolean isUnsafeLiquid(BlockPos pos) {
                return delegate.isUnsafeLiquid(pos);
            }

            @Override
            public boolean isFlowingWater(BlockPos pos) {
                return delegate.isFlowingWater(pos);
            }
        };
    }

    /** Clears one breakable block selected by vertical recovery. Drops are left for
     *  the bot's vacuum pickup: pocketed dirt/logs become scaffold material, so
     *  carving steps self-supplies the pillar blocks for the climb out. */
    private static boolean clearVerticalStep(VasyanEntity vasyan, VerticalTraversalPlanner.Step planned) {
        String name = vasyan.getVasyanName();
        Level level = vasyan.level();
        BlockState state = level.getBlockState(planned.target());
        if (!DigRules.isBreakable(level, planned.target(), false)) {
            VasyanMod.LOGGER.warn("Vasyan '{}': {} CLEAR skipped, {} no longer breakable",
                name, planned.mode(), planned.target().toShortString());
            return false;
        }
        vasyan.swing(InteractionHand.MAIN_HAND, true);
        if (!level.destroyBlock(planned.target(), true)) {
            VasyanMod.LOGGER.warn("Vasyan '{}': {} failed to clear {} at {}",
                name, planned.mode(), state.getBlock().getName().getString(),
                planned.target().toShortString());
            return false;
        }
        VasyanMod.LOGGER.info("Vasyan '{}': {} cleared {} at {}",
            name, planned.mode(), state.getBlock().getName().getString(),
            planned.target().toShortString());
        return true;
    }

    /** A placed block must touch at least one solid neighbor (no floating scaffolds). */
    private static boolean hasAdjacentSolid(Level level, BlockPos pos) {
        for (Direction dir : Direction.values()) {
            if (level.getBlockState(pos.relative(dir)).isSolid()) {
                return true;
            }
        }
        return false;
    }

    /** Places one support block selected by vertical recovery, capped by the monitor budget. */
    private static boolean placeVerticalSupport(VasyanEntity vasyan, PathMonitor monitor,
                                             VerticalTraversalPlanner.Step planned) {
        return placeSupportAt(vasyan, monitor, planned.target());
    }

    /** Places a support block at the given position if it is open and has a solid neighbor. */
    private static boolean placeSupportAt(VasyanEntity vasyan, PathMonitor monitor, BlockPos target) {
        String name = vasyan.getVasyanName();
        if (!monitor.canPlaceVerticalScaffold()) {
            VasyanMod.LOGGER.warn("Vasyan '{}': scaffold budget exhausted for {}",
                name, monitor.goal().describe());
            return false;
        }
        ItemStack stack = ScaffoldBlocks.findBestStack(
            vasyan.getInventory(), vasyan.level(), vasyan.blockPosition(),
            VasyanConfig.NAV_SCAFFOLD_WHITELIST.get());
        if (stack == null) {
            VasyanMod.LOGGER.warn("Vasyan '{}': support placement failed, no scaffold block", name);
            return false;
        }
        Level level = vasyan.level();
        if (!isOpen(level, target)) {
            VasyanMod.LOGGER.warn("Vasyan '{}': support spot occupied at {}",
                name, target.toShortString());
            return false;
        }
        if (!hasAdjacentSolid(level, target)) {
            VasyanMod.LOGGER.warn("Vasyan '{}': support has no adjacent solid block at {}",
                name, target.toShortString());
            return false;
        }
        BlockItem blockItem = (BlockItem) stack.getItem();
        if (!level.setBlockAndUpdate(target, blockItem.getBlock().defaultBlockState())) {
            VasyanMod.LOGGER.warn("Vasyan '{}': failed to place support at {}",
                name, target.toShortString());
            return false;
        }
        stack.shrink(1);
        VasyanMod.LOGGER.info("Vasyan '{}': placed support {} at {}",
            name, blockItem.getBlock().getName().getString(), target.toShortString());
        monitor.recordVerticalScaffoldPlacement();
        monitor.recordPlacedSupport(target, blockItem.getBlock());
        return true;
    }

    /**
     * Steers ground navigation to {@code target}, resetting airborne state while PRESERVING an
     * already-active building-invulnerability (combat relies on it; {@code setFlying(false)}
     * would otherwise clear it as a side effect). Then re-applies the flag so combat keeps
     * protection across moveTo AND every replan.
     */
    private static void steerTo(VasyanEntity vasyan, BlockPos target, double speed) {
        boolean wasInvulnerable = vasyan.isInvulnerable();
        vasyan.setFlying(false);
        if (wasInvulnerable) {
            vasyan.setInvulnerableBuilding(true);
        }
        vasyan.getNavigation().moveTo(target.getX() + CENTER_OFFSET, target.getY(),
            target.getZ() + CENTER_OFFSET, speed);
    }

    private static BlockPos adjacentApproach(VasyanEntity vasyan, BlockPos block) {
        Level level = vasyan.level();
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            BlockPos candidate = block.relative(direction);
            if (passable(level, candidate) && passable(level, candidate.above())) {
                return candidate;
            }
        }
        return block;
    }

    private static boolean passable(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return state.isAir() || state.canBeReplaced();
    }

    /**
     * Pit escape for LEVEL goals: the vertical ladder keys off the goal's Y, so a
     * bot boxed into a depression with a same-height goal never gets ASCEND_STEP
     * and digs a tunnel instead. Returns the local-surface anchor to climb to, or
     * null when this is a plain wall (DIG_THROUGH's job) or escape is impossible.
     */
    private static @Nullable BlockPos pitEscapeAnchor(VasyanEntity vasyan, PathMonitor monitor) {
        Level level = vasyan.level();
        BlockPos botPos = vasyan.blockPosition();
        BlockPos anchor = VasyanGoal.anchor(monitor.goal(), botPos);
        if (anchor.getY() != botPos.getY() || !monitor.verticalRecovery().enabled()) {
            return null; // a real Y goal is the vertical ladder's job
        }
        if (!isBoxedIn(level, botPos)) {
            return null; // a single wall ahead is DIG_THROUGH's job (scenario C)
        }
        // The bot's own column is dug out, so its heightmap is the hole floor;
        // the rim to climb to is the HIGHEST neighbor column (a 2-deep trench
        // has walls on 2 sides but a rim right there).
        int rimY = botPos.getY();
        for (Direction dir : Direction.Plane.HORIZONTAL) {
            rimY = Math.max(rimY, level.getHeightmapPos(
                net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                botPos.relative(dir)).getY());
        }
        int climb = rimY - botPos.getY();
        if (climb <= 0 || climb > monitor.verticalRecovery().maxDistance()) {
            return null; // flat ground, or a deep cave: no honest escape
        }
        BlockPos escapeAnchor = new BlockPos(botPos.getX(), rimY, botPos.getZ());
        // A boxed bot can climb out by breaking a step into the pit wall (CLEAR/MOVE)
        // even with an empty inventory. Only refuse the escape when the ascent needs
        // placed support and no scaffold blocks are available.
        if (canAscendByClearing(botPos, escapeAnchor, verticalWorld(level))
                || ScaffoldBlocks.findBestStack(vasyan.getInventory(), vasyan.level(), vasyan.blockPosition(),
                    VasyanConfig.NAV_SCAFFOLD_WHITELIST.get()) != null) {
            return escapeAnchor;
        }
        return null;
    }

    /** True when the first ASCEND step can be taken by clearing or moving, without placing support. */
    static boolean canAscendByClearing(BlockPos botPos, BlockPos anchor,
                                       VerticalTraversalPlanner.WorldView world) {
        return VerticalTraversalPlanner.nextStep(botPos, anchor, VerticalTraversalPlanner.Mode.ASCEND, world)
            .map(step -> step.action() != VerticalTraversalPlanner.Action.PLACE_SUPPORT)
            .orElse(false);
    }

    /** Whether at least 2 of the 4 horizontal walking exits from {@code botPos} are blocked. */
    static boolean isBoxedIn(Level level, BlockPos botPos) {
        int blocked = 0;
        for (Direction dir : Direction.Plane.HORIZONTAL) {
            BlockPos feet = botPos.relative(dir);
            if (!isOpen(level, feet) || !isOpen(level, feet.above())) {
                blocked++;
            }
        }
        // 1 blocked side = a wall ahead (DIG_THROUGH's job, scenario C);
        // 2+ blocked sides = a trench/pit corner: climb the rim instead.
        return blocked >= 2;
    }

    /**
     * DIG_THROUGH: breaks the obstruction directly ahead (no drops - tunneling, not
     * gathering). Reports progress only when the block really broke, so a failed break lets
     * the grace window expire and the ladder advance normally.
     */
    private static void digThrough(VasyanEntity vasyan, PathMonitor monitor, @Nullable BlockPos pos) {
        String name = vasyan.getVasyanName();
        if (pos == null) {
            VasyanMod.LOGGER.warn("Vasyan '{}': DIG_THROUGH skipped, nothing breakable ahead", name);
            return;
        }
        Level level = vasyan.level();
        vasyan.swing(InteractionHand.MAIN_HAND, true);

        boolean broke = false;
        int botY = vasyan.blockPosition().getY();
        // findDiggableAhead only returns foot/head cells; clear the whole 1x2 corridor.
        BlockPos[] corridorCells = pos.getY() == botY
            ? new BlockPos[]{pos, pos.above()}
            : new BlockPos[]{pos, pos.below()};
        for (BlockPos cell : corridorCells) {
            if (!DigRules.isBreakable(level, cell, false)) {
                continue;
            }
            BlockState state = level.getBlockState(cell);
            // Drops stay for the vacuum pickup: tunnelled dirt/logs come back as
            // scaffold material for the climb out of whatever we dig into.
            if (!level.destroyBlock(cell, true)) {
                VasyanMod.LOGGER.warn("Vasyan '{}': failed to break {} at {}", name,
                    state.getBlock().getName().getString(), cell.toShortString());
                continue;
            }
            VasyanMod.LOGGER.info("Vasyan '{}': dug through {} at {}", name,
                state.getBlock().getName().getString(), cell.toShortString());
            broke = true;
        }
        if (broke) {
            monitor.onRecoverySuccess();
            // The old path is still blocked/finished; rebuild it through the corridor.
            replan(vasyan, monitor);
        }
    }

    /**
     * PLACE_SCAFFOLD: places the first solid full-cube block from the inventory into the gap
     * below the feet (bridging) or directly ahead (ramping). Consumes the item and reports
     * progress only on a successful placement.
     */
    private static void placeScaffold(VasyanEntity vasyan, PathMonitor monitor) {
        String name = vasyan.getVasyanName();
        ItemStack stack = ScaffoldBlocks.findBestStack(
            vasyan.getInventory(), vasyan.level(), vasyan.blockPosition(),
            VasyanConfig.NAV_SCAFFOLD_WHITELIST.get());
        if (stack == null) {
            VasyanMod.LOGGER.warn("Vasyan '{}': PLACE_SCAFFOLD skipped, no solid block item", name);
            return;
        }
        BlockPos placePos = scaffoldPosition(vasyan, monitor.goal());
        if (placePos == null) {
            VasyanMod.LOGGER.warn("Vasyan '{}': PLACE_SCAFFOLD skipped, no open spot under/ahead", name);
            return;
        }
        Level level = vasyan.level();
        if (!hasAdjacentSolid(level, placePos)) {
            VasyanMod.LOGGER.warn("Vasyan '{}': PLACE_SCAFFOLD skipped, no adjacent solid block at {}",
                name, placePos.toShortString());
            return;
        }
        BlockItem blockItem = (BlockItem) stack.getItem();
        if (!level.setBlockAndUpdate(placePos, blockItem.getBlock().defaultBlockState())) {
            VasyanMod.LOGGER.warn("Vasyan '{}': failed to place scaffold at {}",
                name, placePos.toShortString());
            return;
        }
        stack.shrink(1);
        VasyanMod.LOGGER.info("Vasyan '{}': placed scaffold {} at {}", name,
            blockItem.getBlock().getName().getString(), placePos.toShortString());
        monitor.recordPlacedSupport(placePos, blockItem.getBlock());
        monitor.onRecoverySuccess();
        replan(vasyan, monitor);
    }

    /**
     * HOP_TELEPORT (one-shot per monitor instance): finds a safe standing spot around the
     * goal anchor via the {@link VasyanTeleportUtil#findSafePos} ring scan and teleports the
     * bot there. Because the teleport leaves the old ground path behind, navigation is
     * stopped and a fresh path is rebuilt towards the goal. Reports progress only when the
     * teleport actually happened.
     */
    private static void hopTeleport(VasyanEntity vasyan, PathMonitor monitor) {
        String name = vasyan.getVasyanName();
        BlockPos anchor = VasyanGoal.anchor(monitor.goal(), vasyan.blockPosition());
        Level level = vasyan.level();
        BlockPos safe = VasyanTeleportUtil.findSafePos(anchor,
            (x, y, z) -> isSafeHopSpot(level, vasyan, x, y, z));
        if (safe == null) {
            VasyanMod.LOGGER.warn("Vasyan '{}': HOP_TELEPORT failed, no safe spot near {}",
                name, anchor.toShortString());
            return;
        }
        vasyan.teleportTo(safe.getX() + CENTER_OFFSET, safe.getY(), safe.getZ() + CENTER_OFFSET);
        VasyanMod.LOGGER.info("Vasyan '{}': hop-teleported past obstacle to {}",
            name, safe.toShortString());
        monitor.onRecoverySuccess();
        // One-shot fallback: the old path no longer matches the bot's new position,
        // so stop the leftover navigation and replan from the teleport destination.
        vasyan.getNavigation().stop();
        replan(vasyan, monitor);
    }

    /** GIVE_UP: halt navigation; the owning action observes {@code monitor.finished()}. */
    private static void giveUp(VasyanEntity vasyan, PathMonitor monitor) {
        VasyanMod.LOGGER.warn("Vasyan '{}': giving up on {}",
            vasyan.getVasyanName(), monitor.goal().describe());
        vasyan.getNavigation().stop();
        dismantlePlacedSupports(vasyan, monitor);
    }

    /**
     * Breaks every support/scaffold block this route placed (drops enabled, the
     * vacuum re-collects them). A bot that pillared into a dead pocket falls back
     * out instead of staying trapped on its own block; the next route attempt
     * starts from the freed corridor with a fresh ladder.
     */
    private static void dismantlePlacedSupports(VasyanEntity vasyan, PathMonitor monitor) {
        if (monitor.placedSupports().isEmpty()) {
            return;
        }
        String name = vasyan.getVasyanName();
        Level level = vasyan.level();
        for (PathMonitor.PlacedSupport support : monitor.placedSupports()) {
            BlockPos pos = support.pos();
            if (!level.getBlockState(pos).is(support.block())) {
                VasyanMod.LOGGER.warn("Vasyan '{}': support replaced, skipping {}",
                    name, pos.toShortString());
                continue;
            }
            if (!DigRules.isBreakable(level, pos, false)) {
                continue;
            }
            if (level.destroyBlock(pos, true)) {
                VasyanMod.LOGGER.info("Vasyan '{}': dismantled support at {}",
                    name, pos.toShortString());
            }
        }
        monitor.placedSupports().clear();
    }

    /**
     * Position directly ahead along the movement direction: the next path node while a path
     * is being followed. When navigation is DONE (e.g. the path dead-ends right before an
     * obstacle), the dead path's last node is the bot's own cell and would never be diggable,
     * so the direction towards the goal anchor is used instead.
     */
    private static BlockPos aheadPosition(VasyanEntity vasyan, VasyanGoal goal) {
        Path path = vasyan.getNavigation().getPath();
        if (path != null && !vasyan.getNavigation().isDone()
                && path.getNextNodeIndex() < path.getNodeCount()) {
            return path.getNextNodePos();
        }
        Direction dir = facingToward(vasyan, VasyanGoal.anchor(goal, vasyan.blockPosition()));
        return vasyan.blockPosition().relative(dir);
    }

    /**
     * First breakable obstruction between the bot and the goal anchor - foot level, then head
     * level - or {@code null} when the way is clear or nothing may be broken.
     *
     * <p>When navigation is done and the bot is pressed against an obstacle, its rounded
     * position can already be INSIDE the obstacle's column (x=148.6 rounds to 149, the wall
     * cell), so a single "one cell ahead" probe would look past the wall at clear air. Instead,
     * scan along the goal direction for up to {@value #AHEAD_SCAN_STEPS} cells ahead (including
     * the bot's own cell), foot level first.</p>
     */
    @Nullable
    private static BlockPos findDiggableAhead(VasyanEntity vasyan, VasyanGoal goal) {
        Level level = vasyan.level();
        Direction dir = facingToward(vasyan, VasyanGoal.anchor(goal, vasyan.blockPosition()));
        BlockPos cursor = vasyan.blockPosition();
        for (int step = 0; step < AHEAD_SCAN_STEPS; step++) {
            if (DigRules.isBreakable(level, cursor, false)) {
                return cursor;
            }
            if (DigRules.isBreakable(level, cursor.above(), false)) {
                return cursor.above();
            }
            cursor = cursor.relative(dir);
        }
        return null;
    }

    /**
     * Horizontal direction the bot should face to move toward {@code target}. Prefers the axis
     * with the larger absolute delta; when the target is on the same cell horizontally, falls
     * back to the bot's current facing.
     */
    private static Direction facingToward(VasyanEntity vasyan, BlockPos target) {
        BlockPos botPos = vasyan.blockPosition();
        int dx = Integer.compare(target.getX(), botPos.getX());
        int dz = Integer.compare(target.getZ(), botPos.getZ());
        int absDx = Math.abs(target.getX() - botPos.getX());
        int absDz = Math.abs(target.getZ() - botPos.getZ());
        if (dx != 0 && (absDx >= absDz || dz == 0)) {
            return dx > 0 ? Direction.EAST : Direction.WEST;
        }
        if (dz != 0) {
            return dz > 0 ? Direction.SOUTH : Direction.NORTH;
        }
        return vasyan.getDirection();
    }

    /**
     * Where to put a scaffold block: the gap right below the feet first (bridging /
     * pillaring), then the open foot-level cell directly ahead.
     *
     * @return an open (air / liquid / replaceable) position, or {@code null} if none
     */
    @Nullable
    private static BlockPos scaffoldPosition(VasyanEntity vasyan, VasyanGoal goal) {
        Level level = vasyan.level();
        BlockPos below = vasyan.blockPosition().below();
        if (isDeepGap(level, below)) {
            return below;
        }
        BlockPos ahead = aheadPosition(vasyan, goal);
        return isOpen(level, ahead) ? ahead : null;
    }

    /** Whether a scaffold block can be placed into {@code pos} (air, liquid or replaceable). */
    private static boolean isOpen(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return state.isAir() || isLiquid(state.getFluidState()) || state.canBeReplaced();
    }

    /** Whether an open placement cell is deeper than the normal walk-down limit. */
    static boolean isDeepGap(Level level, BlockPos pos) {
        if (!isOpen(level, pos)) {
            return false;
        }
        int maxDropDown = VasyanConfig.NAV_MAX_DROP_DOWN.get();
        int drop = 0;
        BlockPos below = pos.below();
        while (drop <= maxDropDown && isOpen(level, below)) {
            drop++;
            below = below.below();
        }
        return drop > maxDropDown;
    }

    /** Whether the given block position holds water or lava (non-deprecated fluid check). */
    private static boolean isLiquid(FluidState fluid) {
        return fluid.is(Fluids.WATER) || fluid.is(Fluids.LAVA);
    }

    /**
     * Safe-spot predicate mirroring {@code VasyanEntity#isSafeTeleportSpot}: valid spawn
     * ground plus two air blocks of headroom.
     */
    private static boolean isSafeHopSpot(Level level, VasyanEntity vasyan, int x, int y, int z) {
        BlockPos groundPos = new BlockPos(x, y - 1, z);
        BlockState ground = level.getBlockState(groundPos);
        return ground.isValidSpawn(level, groundPos, vasyan.getType())
            && level.getBlockState(new BlockPos(x, y, z)).isAir()
            && level.getBlockState(new BlockPos(x, y + 1, z)).isAir();
    }
}
