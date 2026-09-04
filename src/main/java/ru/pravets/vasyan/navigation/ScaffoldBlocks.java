package ru.pravets.vasyan.navigation;

import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;

import org.jetbrains.annotations.Nullable;

import ru.pravets.vasyan.entity.VasyanInventory;

/**
 * Reusable scaffold-block selection for navigation. Extracted from
 * {@link VasyanPathing} so the path-node evaluator and any other consumer can
 * share exactly the same selection logic without duplicating it.
 *
 * <p>Selection rule: a scaffold must be a block item whose block forms a full
 * collision cube (never a partial shape such as a slab or torch — the bot
 * needs standable support). Among full-cube candidates the cheapest/most
 * disposable material wins: dirt/sand/gravel (score 0), then cobble/stone-like
 * blocks (score 1), then planks/logs (score 2), everything else (score 3).
 * Placement-time checks (e.g. whether an adjacent solid face exists) stay with
 * the caller — this class is selection only, not placement.</p>
 *
 * @author Iosif Pravets &lt;i@pravets.ru&gt;
 */
public final class ScaffoldBlocks {

    private ScaffoldBlocks() {
    }

    /**
     * Best inventory stack usable as scaffold: a block item whose block forms a full
     * collision cube. Prefer disposable ground materials over logs/planks and never
     * consider partial shapes (slabs, torches) standable support.
     *
     * @param inventory bot inventory to scan
     * @param level     world, used only for the full-collision-cube shape check
     * @param refPos    reference position for that shape check
     * @return the cheapest matching stack or {@code null} when the inventory holds none
     */
    @Nullable
    public static ItemStack findBestStack(VasyanInventory inventory, Level level, BlockPos refPos) {
        ItemStack best = null;
        int bestScore = Integer.MAX_VALUE;
        for (ItemStack stack : inventory.getStacks()) {
            if (stack.isEmpty() || !(stack.getItem() instanceof BlockItem blockItem)) {
                continue;
            }
            BlockState state = blockItem.getBlock().defaultBlockState();
            if (state.isAir() || isLiquid(state.getFluidState())
                    || !state.isCollisionShapeFullBlock(level, refPos)) {
                continue;
            }
            int score = score(state, level, refPos);
            if (score < bestScore) {
                best = stack;
                bestScore = score;
            }
        }
        return best;
    }

    /**
     * Scaffold-material preference score; lower score = more disposable material.
     * {@code level}/{@code pos} are accepted for symmetry with
     * {@link #findBestStack} and future per-position scoring.
     */
    public static int score(BlockState state, Level level, BlockPos pos) {
        Block block = state.getBlock();
        if (block == Blocks.DIRT || block == Blocks.GRASS_BLOCK || block == Blocks.SAND
                || block == Blocks.GRAVEL || block == Blocks.COARSE_DIRT || block == Blocks.ROOTED_DIRT) {
            return 0;
        }
        if (block == Blocks.COBBLESTONE || block == Blocks.STONE || block == Blocks.DEEPSLATE
                || block == Blocks.NETHERRACK || block == Blocks.BLACKSTONE) {
            return 1;
        }
        if (state.is(BlockTags.PLANKS) || state.is(BlockTags.LOGS)) {
            return 2;
        }
        return 3;
    }

    /** Whether the given fluid state is water or lava (non-deprecated fluid check). */
    private static boolean isLiquid(FluidState fluid) {
        return fluid.is(Fluids.WATER) || fluid.is(Fluids.LAVA);
    }
}
