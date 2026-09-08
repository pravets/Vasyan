package ru.pravets.vasyan.navigation;

import com.electronwill.nightconfig.core.CommentedConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import ru.pravets.vasyan.config.VasyanConfig;
import ru.pravets.vasyan.testutil.AbstractMinecraftTest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link DigPlaceCosts} over a stubbed {@link Level}: one cell holds dirt, the
 * other stone, everything else is irrelevant because
 * {@code BlockState#getDestroySpeed} never reads the world beyond the passed
 * block state for plain blocks. Config values come from the accepted
 * {@link VasyanConfig} spec (defaults), same as the game would supply at runtime.
 */
class DigPlaceCostsTest extends AbstractMinecraftTest {

    private static final BlockPos DIRT_POS = new BlockPos(0, 64, 0);
    private static final BlockPos STONE_POS = new BlockPos(1, 64, 0);
    private static final BlockPos AIR_POS = new BlockPos(2, 64, 0);

    private static Level level;

    @BeforeAll
    static void loadVasyanConfig() {
        CommentedConfig config = CommentedConfig.inMemory();
        VasyanConfig.SPEC.correct(config);
        VasyanConfig.SPEC.acceptConfig(config);
        level = mock(Level.class);
        when(level.getBlockState(any(BlockPos.class)))
            .thenAnswer(invocation -> {
                BlockPos pos = invocation.getArgument(0);
                if (pos.equals(STONE_POS)) {
                    return Blocks.STONE.defaultBlockState();
                }
                if (pos.equals(AIR_POS)) {
                    return Blocks.AIR.defaultBlockState();
                }
                return Blocks.DIRT.defaultBlockState();
            });
    }

    @Test
    void dirtCostsLessThanStone() {
        assertTrue(DigPlaceCosts.digCost(level, DIRT_POS) < DigPlaceCosts.digCost(level, STONE_POS),
            "soft dirt must price below hard stone so A* prefers digging the cheap block");
    }

    @Test
    void digEdgeCostIncludesBreakableHeadOnly() {
        assertEquals(DigPlaceCosts.digCost(level, DIRT_POS) + DigPlaceCosts.digCost(level, STONE_POS),
            DigPlaceCosts.digCost(level, DIRT_POS, STONE_POS));
        assertEquals(DigPlaceCosts.digCost(level, DIRT_POS),
            DigPlaceCosts.digCost(level, DIRT_POS, AIR_POS));
    }

    @Test
    void placeCostEqualsNavPlaceCost() {
        assertEquals(VasyanConfig.NAV_PLACE_COST.get(), DigPlaceCosts.placeCost());
    }

    @Test
    void pillarUpCostEqualsPlacePlusWalk() {
        assertEquals(VasyanConfig.NAV_PLACE_COST.get() + DigPlaceCosts.walkCost(),
            DigPlaceCosts.pillarUpCost());
    }
}
