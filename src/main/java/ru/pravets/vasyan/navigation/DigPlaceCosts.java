package ru.pravets.vasyan.navigation;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import ru.pravets.vasyan.config.VasyanConfig;

/**
 * A* edge costs for the {@link MoveType} kinds the path-node evaluator
 * considers. Centralizing the math here keeps the cost model in one place and
 * unit-testable; every value reads the corresponding {@code navigation.*}
 * config key at call time.
 *
 * @author Iosif Pravets &lt;i@pravets.ru&gt;
 */
public final class DigPlaceCosts {

    private DigPlaceCosts() {
    }

    /** Base cost of a plain {@code WALK} step between adjacent cells. */
    public static int walkCost() {
        return 1;
    }

    /**
     * Total A* cost of a {@code DIG} edge through the block at {@code pos}:
     * {@code NAV_DIG_COST + round(destroySpeed * NAV_DIG_HARDNESS_FACTOR)}.
     * Negative destroy speeds (unbreakable blocks) are clamped to zero.
     */
    public static int digCost(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        float hardness = state.getDestroySpeed(level, pos);
        if (hardness < 0) hardness = 0;
        double factor = VasyanConfig.NAV_DIG_HARDNESS_FACTOR.get();
        return VasyanConfig.NAV_DIG_COST.get() + (int) Math.round(hardness * factor);
    }

    /** Base A* cost of a {@code PLACE} edge (one scaffold block consumed). */
    public static int placeCost() {
        return VasyanConfig.NAV_PLACE_COST.get();
    }

    /** A* cost of a {@code PILLAR_UP} edge: the placed block plus one walk step up. */
    public static int pillarUpCost() {
        return VasyanConfig.NAV_PLACE_COST.get() + walkCost();
    }
}
