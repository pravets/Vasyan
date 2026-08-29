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

    @Test
    void pitEscapeCanUseClearStepWhenNoScaffold() {
        BlockPos bot = new BlockPos(0, 64, 0);
        BlockPos anchor = new BlockPos(0, 66, 0);
        // East step at y=65 is a breakable wall: bot can break it and step up.
        VerticalTraversalPlanner.WorldView world = new VerticalTraversalPlanner.WorldView() {
            @Override
            public boolean isOpen(BlockPos pos) {
                if (pos.equals(bot.east())) return false; // breakable wall to clear
                if (pos.equals(bot.east().above().above())) return false; // ceiling above head
                return true;
            }

            @Override
            public boolean isSolidSupport(BlockPos pos) {
                return pos.equals(bot.below()) || pos.equals(bot.east().below());
            }

            @Override
            public boolean isBreakable(BlockPos pos) {
                return pos.equals(bot.east());
            }

            @Override
            public boolean isUnsafeLiquid(BlockPos pos) {
                return false;
            }
        };

        assertTrue(VasyanPathing.canAscendByClearing(bot, anchor, world),
            "boxed bot must escape by breaking a wall step when inventory is empty");
    }

    @Test
    void pitEscapeRequiresScaffoldWhenOnlyPlaceSupportStepAvailable() {
        BlockPos bot = new BlockPos(0, 64, 0);
        BlockPos anchor = new BlockPos(0, 66, 0);
        // East step is open but has no solid support (air below) -> PLACE_SUPPORT required.
        VerticalTraversalPlanner.WorldView world = new VerticalTraversalPlanner.WorldView() {
            @Override
            public boolean isOpen(BlockPos pos) {
                if (pos.equals(bot.east().below())) return false; // missing support -> open
                if (pos.equals(bot.east().above().above())) return false; // ceiling above head
                return true;
            }

            @Override
            public boolean isSolidSupport(BlockPos pos) {
                return pos.equals(bot.below()); // only the floor under the bot
            }

            @Override
            public boolean isBreakable(BlockPos pos) {
                return false;
            }

            @Override
            public boolean isUnsafeLiquid(BlockPos pos) {
                return false;
            }
        };

        assertFalse(VasyanPathing.canAscendByClearing(bot, anchor, world),
            "when ascent needs placed support and no scaffold exists, escape must not be claimed");
    }
}
