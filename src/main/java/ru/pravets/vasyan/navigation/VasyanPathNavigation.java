package ru.pravets.vasyan.navigation;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
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
        if (!dugFoot && edge.digFoot() != null && canDig(world, edge.digFoot())) {
            dugFoot = destroy(world, edge.digFoot());
            return dugFoot;
        }
        if (!dugHead && edge.digHead() != null && canDig(world, edge.digHead())) {
            dugHead = destroy(world, edge.digHead());
            return dugHead;
        }
        dugFoot = edge.digFoot() == null || world.getBlockState(edge.digFoot()).isAir();
        dugHead = edge.digHead() == null || world.getBlockState(edge.digHead()).isAir();
        return false;
    }

    private static boolean digFirst(Level world, VasyanEdge edge) {
        BlockPos pos = edge.digFoot() != null ? edge.digFoot() : edge.digHead();
        return pos != null && canDig(world, pos) && destroy(world, pos);
    }

    private static boolean canDig(Level world, BlockPos pos) {
        return DigRules.isBreakable(world, pos, false)
            && !DigRules.wouldCreateFlow(world, pos)
            && !DigRules.isFallingBlock(world, pos);
    }

    private static boolean destroy(Level world, BlockPos pos) {
        return world.destroyBlock(pos, true);
    }

    private boolean digComplete(VasyanEdge edge) {
        return (edge.digFoot() == null || level.getBlockState(edge.digFoot()).isAir())
            && (edge.digHead() == null || level.getBlockState(edge.digHead()).isAir());
    }

    private static boolean place(VasyanEntity bot, BlockPos pos) {
        if (pos == null) return false;
        Level world = bot.level();
        if (!world.getBlockState(pos).isAir() || !hasAdjacentSolid(world, pos)) return false;
        ItemStack stack = findAllowedStack(bot, pos);
        if (stack == null || !(stack.getItem() instanceof BlockItem item) || !allowed(item)) return false;
        if (!world.setBlockAndUpdate(pos, item.getBlock().defaultBlockState())) return false;
        stack.shrink(1);
        return true;
    }

    private static boolean jump(VasyanEntity bot) {
        bot.getJumpControl().jump();
        return true;
    }

    private static boolean allowed(BlockItem item) {
        String id = item.getBlock().builtInRegistryHolder().key().location().toString();
        return VasyanConfig.NAV_SCAFFOLD_WHITELIST.get().stream().anyMatch(id::equals);
    }

    private static ItemStack findAllowedStack(VasyanEntity bot, BlockPos pos) {
        ItemStack best = ItemStack.EMPTY;
        int bestScore = Integer.MAX_VALUE;
        for (ItemStack stack : bot.getInventory().getStacks()) {
            if (!(stack.getItem() instanceof BlockItem item) || !allowed(item)) continue;
            BlockState state = item.getBlock().defaultBlockState();
            if (state.isAir() || state.getFluidState().is(Fluids.WATER) || state.getFluidState().is(Fluids.LAVA)
                    || !state.isCollisionShapeFullBlock(bot.level(), pos)) continue;
            int score = ScaffoldBlocks.score(state, bot.level(), pos);
            if (score < bestScore) {
                best = stack;
                bestScore = score;
            }
        }
        return best;
    }

    private static boolean hasAdjacentSolid(Level world, BlockPos pos) {
        for (Direction direction : Direction.values()) {
            BlockState state = world.getBlockState(pos.relative(direction));
            if (!state.isAir() && state.isFaceSturdy(world, pos.relative(direction), direction.getOpposite())) return true;
        }
        return false;
    }

    private void maybeReplan(VasyanEntity bot, Path currentPath) {
        long now = bot.level().getGameTime();
        int interval = VasyanConfig.NAV_REPLAN_CHECK_INTERVAL_TICKS.get();
        if (now - lastReplanCheck < interval || !(currentPath instanceof VasyanPath vasyanPath)) return;
        lastReplanCheck = now;
        int start = vasyanPath.getNextNodeIndex();
        int end = Math.min(vasyanPath.transitions().size(), start + LOOKAHEAD_TRANSITIONS);
        for (int i = start; i < end; i++) {
            VasyanEdge edge = vasyanPath.transitions().get(i);
            if (edge.moveType() == MoveType.DIG && ((edge.digFoot() != null && !level.getBlockState(edge.digFoot()).isAir())
                    || (edge.digHead() != null && !level.getBlockState(edge.digHead()).isAir()))) {
                recomputePath();
                return;
            }
            if ((edge.moveType() == MoveType.PLACE || edge.moveType() == MoveType.PILLAR_UP)
                    && edge.placePosition() != null && !level.getBlockState(edge.placePosition()).isAir()) {
                recomputePath();
                return;
            }
        }
    }
}
