package ru.pravets.vasyan.navigation;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.level.pathfinder.PathFinder;
import net.minecraft.world.entity.ai.navigation.AmphibiousPathNavigation;
import ru.pravets.vasyan.config.VasyanConfig;
import ru.pravets.vasyan.entity.VasyanEntity;

/** Amphibious navigation which executes the metadata on the current path edge. */
public class VasyanPathNavigation extends AmphibiousPathNavigation {
    private static final int LOOKAHEAD_TRANSITIONS = 5;
    private long lastReplanCheck = Long.MIN_VALUE;
    private VasyanEdge pendingDig;
    private boolean dugFoot;
    private boolean dugHead;

    public VasyanPathNavigation(Mob mob, Level level) {
        super(mob, level);
    }

    @Override
    protected PathFinder createPathFinder(int maxVisitedNodes) {
        VasyanNodeEvaluator evaluator = new VasyanNodeEvaluator(mob, level);
        return new VasyanPathFinder(evaluator, maxVisitedNodes);
    }

    @Override
    protected void followThePath() {
        if (!level.isClientSide && mob instanceof VasyanEntity vasyan) {
            executeNextEdge(vasyan, path, this);
            maybeReplan(vasyan, path);
            if (pendingDig != null && !digComplete(pendingDig)) return;
        }
        super.followThePath();
    }

    static boolean executeNextEdge(VasyanEntity bot, Path currentPath) {
        return executeNextEdge(bot, currentPath, null);
    }

    private static boolean executeNextEdge(VasyanEntity bot, Path currentPath, VasyanPathNavigation navigation) {
        if (bot == null || bot.level().isClientSide || !(currentPath instanceof VasyanPath vasyanPath)) return false;
        VasyanEdge edge = vasyanPath.getNextTransition();
        if (edge == null) return false;
        return switch (edge.moveType()) {
            case WALK -> false;
            case DIG -> navigation == null ? digFirst(bot.level(), edge) : navigation.dig(bot.level(), edge);
            case PLACE -> place(bot, edge.placePosition());
            case PILLAR_UP -> place(bot, edge.placePosition()) && jump(bot);
        };
    }

    private boolean dig(Level world, VasyanEdge edge) {
        if (pendingDig != edge) {
            pendingDig = edge;
            dugFoot = false;
            dugHead = false;
        }
        if (!dugFoot && edge.digFoot() != null && DigRules.isSafeToDig(world, edge.digFoot())) {
            dugFoot = destroy(world, edge.digFoot());
            return dugFoot;
        }
        if (!dugHead && edge.digHead() != null && DigRules.isSafeToDig(world, edge.digHead())) {
            dugHead = destroy(world, edge.digHead());
            return dugHead;
        }
        dugFoot = edge.digFoot() == null || world.getBlockState(edge.digFoot()).isAir();
        dugHead = edge.digHead() == null || world.getBlockState(edge.digHead()).isAir();
        return false;
    }

    private static boolean digFirst(Level world, VasyanEdge edge) {
        BlockPos pos = edge.digFoot() != null ? edge.digFoot() : edge.digHead();
        return pos != null && DigRules.isSafeToDig(world, pos) && destroy(world, pos);
    }

    private static boolean destroy(Level world, BlockPos pos) {
        return world.destroyBlock(pos, true);
    }

    private boolean digComplete(VasyanEdge edge) {
        return (edge.digFoot() == null || level.getBlockState(edge.digFoot()).isAir())
            && (edge.digHead() == null || level.getBlockState(edge.digHead()).isAir());
    }

    static boolean place(VasyanEntity bot, BlockPos pos) {
        if (pos == null) return false;
        Level world = bot.level();
        if (!placeableInto(world.getBlockState(pos)) || !hasAdjacentSolid(world, pos)) return false;
        ItemStack stack = ScaffoldBlocks.findBestStack(bot.getInventory(), world, pos,
            VasyanConfig.NAV_SCAFFOLD_WHITELIST.get());
        if (stack == null || !(stack.getItem() instanceof BlockItem item)) return false;
        if (!world.setBlockAndUpdate(pos, item.getBlock().defaultBlockState())) return false;
        stack.shrink(1);
        return true;
    }

    /**
     * Whether a scaffold block may go into this cell: air, replaceable (grass,
     * snow) or liquid — the same openness the planner assumes, so bots can
     * bridge over water like they swim through it.
     */
    private static boolean placeableInto(BlockState state) {
        return state.isAir() || state.canBeReplaced() || isLiquid(state);
    }

    private static boolean isLiquid(BlockState state) {
        FluidState fluid = state.getFluidState();
        return fluid.is(Fluids.WATER) || fluid.is(Fluids.LAVA);
    }

    private static boolean jump(VasyanEntity bot) {
        bot.getJumpControl().jump();
        return true;
    }

    private static boolean hasAdjacentSolid(Level world, BlockPos pos) {
        for (Direction direction : Direction.values()) {
            BlockState state = world.getBlockState(pos.relative(direction));
            if (!state.isAir() && state.isFaceSturdy(world, pos.relative(direction), direction.getOpposite())) return true;
        }
        return false;
    }

    void maybeReplan(VasyanEntity bot, Path currentPath) {
        long now = bot.level().getGameTime();
        int interval = VasyanConfig.NAV_REPLAN_CHECK_INTERVAL_TICKS.get();
        if (lastReplanCheck != Long.MIN_VALUE && now - lastReplanCheck < interval) return;
        if (!(currentPath instanceof VasyanPath vasyanPath)) return;
        lastReplanCheck = now;
        int start = vasyanPath.getNextNodeIndex();
        int end = Math.min(vasyanPath.transitions().size(), start + LOOKAHEAD_TRANSITIONS);
        for (int i = start; i < end; i++) {
            VasyanEdge edge = vasyanPath.transitions().get(i);
            if (edge.moveType() == MoveType.DIG && corridorUndiggable(edge)) {
                recomputePath();
                return;
            }
            if ((edge.moveType() == MoveType.PLACE || edge.moveType() == MoveType.PILLAR_UP)
                    && edge.placePosition() != null
                    && (!placeableInto(level.getBlockState(edge.placePosition()))
                        || !hasAdjacentSolid(level, edge.placePosition()))) {
                recomputePath();
                return;
            }
        }
    }

    /**
     * Whether a required DIG corridor cell is still solid but no longer safe to
     * break. A cell that is still diggable (mid-dig) or already passable
     * (cleared, replaced by liquid) must NOT replan; only a genuinely
     * undiggable obstacle must.
     */
    private boolean corridorUndiggable(VasyanEdge edge) {
        return solidButUndiggable(edge.digFoot()) || solidButUndiggable(edge.digHead());
    }

    private boolean solidButUndiggable(BlockPos pos) {
        if (pos == null) return false;
        BlockState state = level.getBlockState(pos);
        if (state.isAir() || state.canBeReplaced()) return false;
        return !DigRules.isSafeToDig(level, pos);
    }
}
