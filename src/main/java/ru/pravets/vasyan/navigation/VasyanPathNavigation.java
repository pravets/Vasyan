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
            executeNextEdge(vasyan, path);
            maybeReplan(vasyan, path);
        }
        super.followThePath();
    }

    static boolean executeNextEdge(VasyanEntity bot, Path currentPath) {
        if (bot == null || !(currentPath instanceof VasyanPath vasyanPath)) return false;
        VasyanEdge edge = vasyanPath.getNextTransition();
        if (edge == null) return false;
        return switch (edge.moveType()) {
            case WALK -> false;
            case DIG -> dig(bot.level(), edge);
            case PLACE -> place(bot, edge.placePosition());
            case PILLAR_UP -> place(bot, edge.placePosition()) && jump(bot);
        };
    }

    private static boolean dig(Level world, VasyanEdge edge) {
        boolean changed = false;
        for (BlockPos pos : new BlockPos[] {edge.digFoot(), edge.digHead()}) {
            if (pos != null && !world.getBlockState(pos).isAir()) {
                changed |= world.destroyBlock(pos, true);
            }
        }
        return changed;
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
            if (edge.moveType() == MoveType.DIG && edge.digFoot() != null && level.getBlockState(edge.digFoot()).isAir()
                    && (edge.digHead() == null || level.getBlockState(edge.digHead()).isAir())) {
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
