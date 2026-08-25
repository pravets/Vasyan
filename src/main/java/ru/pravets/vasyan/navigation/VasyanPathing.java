package ru.pravets.vasyan.navigation;

import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.pathfinder.Path;

import org.jetbrains.annotations.Nullable;

import ru.pravets.vasyan.VasyanMod;
import ru.pravets.vasyan.entity.VasyanEntity;
import ru.pravets.vasyan.entity.VasyanTeleportUtil;

import java.util.Objects;
import java.util.Set;

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
 *       {@link PathMonitor#onProgress()} so the escalation ladder does not advance while the
 *       recovery step is actually working; a failed step logs and skips {@code onProgress()},
 *       letting the grace window expire and the ladder move on.</li>
 * </ul></p>
 *
 * <p>Thin glue by design: every decision comes from {@link PathMonitor}, each executed branch
 * is small and logged, and no unit tests are required per plan - the correctness gate is
 * {@code compileJava}, the CI Build and later behavior tests.</p>
 *
 * @author Iosif Pravets &lt;i@pravets.ru&gt;
 */
public final class VasyanPathing {

    /**
     * Blocks the DIG_THROUGH fallback must never break, on top of the generic
     * "negative destroy speed means unbreakable" rule (which also covers this set).
     */
    private static final Set<Block> UNBREAKABLE = Set.of(
        Blocks.BEDROCK, Blocks.OBSIDIAN, Blocks.CRYING_OBSIDIAN, Blocks.REINFORCED_DEEPSLATE);

    /** Ground walking speed modifier passed to {@link PathNavigation#moveTo(double, double, double, double)}. */
    private static final double GROUND_SPEED = 1.0;

    /** Offset from a block corner to its center, used for navigation/teleport targets. */
    private static final double CENTER_OFFSET = 0.5;

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
        Objects.requireNonNull(vasyan, "vasyan");
        Objects.requireNonNull(goal, "goal");
        Objects.requireNonNull(budgets, "budgets");

        BlockPos target = resolveTarget(goal, vasyan.blockPosition());
        vasyan.setFlying(false);
        vasyan.getNavigation().moveTo(target.getX() + CENTER_OFFSET, target.getY(),
            target.getZ() + CENTER_OFFSET, GROUND_SPEED);
        VasyanMod.LOGGER.info("Vasyan '{}': moveTo {}, steering navigation to {}",
            vasyan.getVasyanName(), goal.describe(), target.toShortString());
        return new PathMonitor(goal, PathMonitor.DEFAULT_STALL_TICKS, PathMonitor.DEFAULT_MAX_REPLANS);
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
        Objects.requireNonNull(vasyan, "vasyan");
        Objects.requireNonNull(monitor, "monitor");
        if (vasyan.level().isClientSide()) {
            return; // world-mutating glue runs server-side only
        }

        PathNavigation nav = vasyan.getNavigation();
        boolean navDone = nav.isDone();
        BlockPos diggable = findDiggableAhead(vasyan, monitor.goal());
        boolean canDig = diggable != null;
        boolean canPlace = findScaffoldStack(vasyan) != null;

        // Contract: hasPath == "path currently assigned" == !navDone.
        PathMonitor.Decision decision =
            monitor.onTick(vasyan.blockPosition(), navDone, !navDone, canDig, canPlace);
        switch (decision) {
            case CONTINUE -> {
                // steady progress or goal reached: nothing to do
            }
            case REPLAN -> replan(vasyan, monitor.goal());
            case DIG_THROUGH -> digThrough(vasyan, monitor, diggable);
            case PLACE_SCAFFOLD -> placeScaffold(vasyan, monitor);
            case HOP_TELEPORT -> hopTeleport(vasyan, monitor);
            case GIVE_UP -> giveUp(vasyan, monitor);
        }
    }

    /** REPLAN: back to ground movement and rebuild the path to the goal anchor. */
    private static void replan(VasyanEntity vasyan, VasyanGoal goal) {
        BlockPos target = resolveTarget(goal, vasyan.blockPosition());
        vasyan.setFlying(false);
        vasyan.getNavigation().moveTo(target.getX() + CENTER_OFFSET, target.getY(),
            target.getZ() + CENTER_OFFSET, GROUND_SPEED);
        VasyanMod.LOGGER.debug("Vasyan '{}': REPLAN towards {} ({})",
            vasyan.getVasyanName(), goal.describe(), target.toShortString());
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
        BlockState state = level.getBlockState(pos);
        vasyan.swing(InteractionHand.MAIN_HAND, true);
        if (!level.destroyBlock(pos, false)) {
            VasyanMod.LOGGER.warn("Vasyan '{}': failed to break {} at {}", name,
                state.getBlock().getName().getString(), pos.toShortString());
            return;
        }
        VasyanMod.LOGGER.warn("Vasyan '{}': dug through {} at {}", name,
            state.getBlock().getName().getString(), pos.toShortString());
        monitor.onProgress();
    }

    /**
     * PLACE_SCAFFOLD: places the first solid full-cube block from the inventory into the gap
     * below the feet (bridging) or directly ahead (ramping). Consumes the item and reports
     * progress only on a successful placement.
     */
    private static void placeScaffold(VasyanEntity vasyan, PathMonitor monitor) {
        String name = vasyan.getVasyanName();
        ItemStack stack = findScaffoldStack(vasyan);
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
        BlockItem blockItem = (BlockItem) stack.getItem();
        if (!level.setBlockAndUpdate(placePos, blockItem.getBlock().defaultBlockState())) {
            VasyanMod.LOGGER.warn("Vasyan '{}': failed to place scaffold at {}",
                name, placePos.toShortString());
            return;
        }
        stack.shrink(1);
        VasyanMod.LOGGER.warn("Vasyan '{}': placed scaffold {} at {}", name,
            blockItem.getBlock().getName().getString(), placePos.toShortString());
        monitor.onProgress();
    }

    /**
     * HOP_TELEPORT (one-shot per monitor instance): finds a safe standing spot around the
     * goal anchor via the {@link VasyanTeleportUtil#findSafePos} ring scan and teleports the
     * bot there. Reports progress only when the teleport actually happened.
     */
    private static void hopTeleport(VasyanEntity vasyan, PathMonitor monitor) {
        String name = vasyan.getVasyanName();
        BlockPos anchor = resolveTarget(monitor.goal(), vasyan.blockPosition());
        Level level = vasyan.level();
        BlockPos safe = VasyanTeleportUtil.findSafePos(anchor,
            (x, y, z) -> isSafeHopSpot(level, vasyan, x, y, z));
        if (safe == null) {
            VasyanMod.LOGGER.warn("Vasyan '{}': HOP_TELEPORT failed, no safe spot near {}",
                name, anchor.toShortString());
            return;
        }
        vasyan.teleportTo(safe.getX() + CENTER_OFFSET, safe.getY(), safe.getZ() + CENTER_OFFSET);
        VasyanMod.LOGGER.warn("Vasyan '{}': hop-teleported past obstacle to {}",
            name, safe.toShortString());
        monitor.onProgress();
    }

    /** GIVE_UP: halt navigation; the owning action observes {@code monitor.finished()}. */
    private static void giveUp(VasyanEntity vasyan, PathMonitor monitor) {
        VasyanMod.LOGGER.warn("Vasyan '{}': giving up on {}",
            vasyan.getVasyanName(), monitor.goal().describe());
        vasyan.getNavigation().stop();
    }

    /**
     * Maps a goal to the concrete block position navigation should steer towards (see
     * {@link #moveTo} for the per-type rules).
     */
    private static BlockPos resolveTarget(VasyanGoal goal, BlockPos botPos) {
        if (goal instanceof GoalNear near) {
            return near.target();
        }
        if (goal instanceof GoalAdjacent adjacent) {
            return adjacent.block();
        }
        if (goal instanceof GoalXZ xz) {
            return new BlockPos(xz.x(), botPos.getY(), xz.z());
        }
        if (goal instanceof GoalY y) {
            return new BlockPos(botPos.getX(), y.y(), botPos.getZ());
        }
        if (goal instanceof GoalCompositeAny any) {
            return nearestAnchor(any.goals(), botPos);
        }
        return botPos;
    }

    /** Anchor of a composite goal nearest to the bot (manhattan metric). */
    private static BlockPos nearestAnchor(VasyanGoal[] goals, BlockPos botPos) {
        BlockPos best = botPos;
        int bestDist = Integer.MAX_VALUE;
        for (VasyanGoal goal : goals) {
            BlockPos anchor = resolveTarget(goal, botPos);
            int dist = anchor.distManhattan(botPos);
            if (dist < bestDist) {
                bestDist = dist;
                best = anchor;
            }
        }
        return best;
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
        BlockPos target = resolveTarget(goal, vasyan.blockPosition());
        int dx = Integer.compare(target.getX(), vasyan.blockPosition().getX());
        int dz = Integer.compare(target.getZ(), vasyan.blockPosition().getZ());
        net.minecraft.core.Direction dir;
        int absDx = Math.abs(target.getX() - vasyan.blockPosition().getX());
        int absDz = Math.abs(target.getZ() - vasyan.blockPosition().getZ());
        if (dx != 0 && (absDx >= absDz || dz == 0)) {
            dir = dx > 0 ? net.minecraft.core.Direction.EAST : net.minecraft.core.Direction.WEST;
        } else if (dz != 0) {
            dir = dz > 0 ? net.minecraft.core.Direction.SOUTH : net.minecraft.core.Direction.NORTH;
        } else {
            dir = vasyan.getDirection();
        }
        return vasyan.blockPosition().relative(dir);
    }

    /**
     * First breakable obstruction directly ahead - foot level, then head level - or
     * {@code null} when the way is clear or nothing may be broken.
     */
    @Nullable
    private static BlockPos findDiggableAhead(VasyanEntity vasyan, VasyanGoal goal) {
        BlockPos ahead = aheadPosition(vasyan, goal);
        Level level = vasyan.level();
        if (isBreakable(level, ahead)) {
            return ahead;
        }
        BlockPos head = ahead.above();
        return isBreakable(level, head) ? head : null;
    }

    /** Whether the block at {@code pos} is a real obstacle the bot may break. */
    private static boolean isBreakable(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (state.isAir() || isLiquid(state.getFluidState()) || state.canBeReplaced()) {
            return false;
        }
        return !UNBREAKABLE.contains(state.getBlock())
            && state.getDestroySpeed(level, pos) >= 0.0F;
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
        if (isOpen(level, below)) {
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

    /** Whether the given block position holds water or lava (non-deprecated fluid check). */
    private static boolean isLiquid(FluidState fluid) {
        return fluid.is(Fluids.WATER) || fluid.is(Fluids.LAVA);
    }

    /**
     * First inventory stack usable as scaffold: a block item whose block forms a full
     * collision cube (dirt, cobblestone, planks, ...). Same criterion as the tree-pillar
     * support: partial shapes (slabs, torches) cannot be stood on.
     *
     * @return the first matching stack or {@code null} when the inventory holds none
     */
    @Nullable
    private static ItemStack findScaffoldStack(VasyanEntity vasyan) {
        Level level = vasyan.level();
        BlockPos refPos = vasyan.blockPosition();
        for (ItemStack stack : vasyan.getInventory().getStacks()) {
            if (stack.isEmpty() || !(stack.getItem() instanceof BlockItem blockItem)) {
                continue;
            }
            BlockState state = blockItem.getBlock().defaultBlockState();
            if (state.isAir() || isLiquid(state.getFluidState())
                    || !state.isCollisionShapeFullBlock(level, refPos)) {
                continue;
            }
            return stack;
        }
        return null;
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
