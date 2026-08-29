package ru.pravets.vasyan.navigation;

import ru.pravets.vasyan.testutil.AbstractMinecraftTest;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Pit detection for the level-goal pit escape (Bob's coal-pit regression). */
class VasyanPathingTest extends AbstractMinecraftTest {

    private static BlockState solid() {
        BlockState state = mock(BlockState.class);
        when(state.isAir()).thenReturn(false);
        when(state.canBeReplaced()).thenReturn(false);
        when(state.getFluidState()).thenReturn(Fluids.EMPTY.defaultFluidState());
        return state;
    }

    private static BlockState open() {
        BlockState state = mock(BlockState.class);
        when(state.isAir()).thenReturn(true);
        when(state.canBeReplaced()).thenReturn(true);
        when(state.getFluidState()).thenReturn(Fluids.EMPTY.defaultFluidState());
        return state;
    }

    @Test
    void botInPitWithAllSidesBlockedIsBoxedIn() {
        BlockPos bot = new BlockPos(0, 64, 0);
        BlockState stone = solid();
        Level level = mock(Level.class);
        when(level.getBlockState(any())).thenReturn(stone);

        assertTrue(VasyanPathing.isBoxedIn(level, bot),
            "all four horizontal exits blocked = pit, climb out instead of digging");
    }

    @Test
    void botFacingASingleWallIsNotBoxedIn() {
        BlockPos bot = new BlockPos(0, 64, 0);
        BlockState stone = solid();
        BlockState air = open();
        Level level = mock(Level.class);
        // East side is the wall (feet + head), every other side is walkable.
        when(level.getBlockState(any())).thenReturn(air);
        when(level.getBlockState(bot.east())).thenReturn(stone);
        when(level.getBlockState(bot.east().above())).thenReturn(stone);

        assertFalse(VasyanPathing.isBoxedIn(level, bot),
            "a single wall ahead must stay DIG_THROUGH's job (scenario C)");
    }
}
