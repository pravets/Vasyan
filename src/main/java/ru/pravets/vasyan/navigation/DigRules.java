package ru.pravets.vasyan.navigation;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.AnvilBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;

import java.util.Set;

/**
 * Reusable dig/breakability rules for navigation. Extracted from
 * {@link VasyanPathing} so the path-node evaluator and any other consumer can
 * share exactly the same predicates without duplicating the logic.
 *
 * <p>Two block sets guard against expensive mistakes:
 * <ul>
 *   <li>{@link #UNBREAKABLE} — blocks the dig fallback must never break, on top of the
 *       generic "negative destroy speed means unbreakable" rule (which also covers this set);</li>
 *   <li>{@link #NEVER_BREAK} — ores the navigation layer must never break: navigation digs
 *       destroy blocks WITHOUT drops, so carving through a vein would silently delete the
 *       resource the bot was sent to gather. Ore is mined as a target.</li>
 * </ul></p>
 *
 * @author Iosif Pravets &lt;i@pravets.ru&gt;
 */
public final class DigRules {

    /**
     * Blocks the DIG_THROUGH fallback must never break, on top of the generic
     * "negative destroy speed means unbreakable" rule (which also covers this set).
     */
    public static final Set<Block> UNBREAKABLE = Set.of(
        Blocks.BEDROCK, Blocks.OBSIDIAN, Blocks.CRYING_OBSIDIAN, Blocks.REINFORCED_DEEPSLATE);

    /**
     * Ores the navigation layer must never break: navigation digs destroy blocks
     * WITHOUT drops, so carving through a vein would silently delete the resource
     * the bot was sent to gather (Alex' station route ate a coal vein). Ore must be
     * mined as a target.
     */
    public static final Set<Block> NEVER_BREAK = Set.of(
        Blocks.COAL_ORE, Blocks.DEEPSLATE_COAL_ORE,
        Blocks.IRON_ORE, Blocks.DEEPSLATE_IRON_ORE,
        Blocks.COPPER_ORE, Blocks.DEEPSLATE_COPPER_ORE,
        Blocks.GOLD_ORE, Blocks.DEEPSLATE_GOLD_ORE,
        Blocks.REDSTONE_ORE, Blocks.DEEPSLATE_REDSTONE_ORE,
        Blocks.LAPIS_ORE, Blocks.DEEPSLATE_LAPIS_ORE,
        Blocks.DIAMOND_ORE, Blocks.DEEPSLATE_DIAMOND_ORE,
        Blocks.EMERALD_ORE, Blocks.DEEPSLATE_EMERALD_ORE,
        Blocks.NETHER_GOLD_ORE, Blocks.NETHER_QUARTZ_ORE, Blocks.ANCIENT_DEBRIS);

    private DigRules() {
    }

    /**
     * Whether the block at {@code pos} is a real obstacle the bot may break.
     *
     * @param includeOres when {@code false} (the navigation default) blocks in
     *                    {@link #NEVER_BREAK} are treated as unbreakable; when
     *                    {@code true} only {@link #UNBREAKABLE} and the generic
     *                    destroy-speed rule apply
     */
    public static boolean isBreakable(Level level, BlockPos pos, boolean includeOres) {
        BlockState state = level.getBlockState(pos);
        if (state.isAir() || isLiquid(state.getFluidState()) || state.canBeReplaced()) {
            return false;
        }
        if (!includeOres && NEVER_BREAK.contains(state.getBlock())) {
            return false;
        }
        return !UNBREAKABLE.contains(state.getBlock())
            && state.getDestroySpeed(level, pos) >= 0.0F;
    }

    /**
     * Whether the block at {@code pos} may be broken and doing so is safe:
     * breakable, would not open a flow into liquid, and no gravity block hangs
     * above. Single source of truth shared by the dig executor, the path-node
     * evaluator and the auto-replan lookahead.
     */
    public static boolean isSafeToDig(Level level, BlockPos pos) {
        return isBreakable(level, pos, false)
            && !wouldCreateFlow(level, pos)
            && !isFallingBlock(level, pos);
    }

    /**
     * Whether breaking the block at {@code pos} would open a cell next to a
     * flowing/source liquid (water or lava) and risk flooding the tunnel.
     */
    public static boolean wouldCreateFlow(Level level, BlockPos pos) {
        for (Direction dir : Direction.values()) {
            FluidState fluid = level.getBlockState(pos.relative(dir)).getFluidState();
            if (!fluid.isEmpty() && (fluid.isSource() || fluid.is(FluidTags.WATER) || fluid.is(FluidTags.LAVA))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether gravity-block hazard hangs above {@code pos}: any sand, gravel or anvil
     * (plain/damaged/chipped) in the column directly above, with only air in between.
     */
    public static boolean isFallingBlock(Level level, BlockPos pos) {
        BlockPos above = pos.above();
        while (!level.isOutsideBuildHeight(above)) {
            Block b = level.getBlockState(above).getBlock();
            if (b == Blocks.SAND || b == Blocks.GRAVEL || b instanceof AnvilBlock) {
                return true;
            }
            if (!level.getBlockState(above).isAir()) {
                break;
            }
            above = above.above();
        }
        return false;
    }

    /** Whether the given fluid state is water or lava (non-deprecated fluid check). */
    private static boolean isLiquid(FluidState fluid) {
        return fluid.is(Fluids.WATER) || fluid.is(Fluids.LAVA);
    }
}
