package ru.pravets.vasyan.navigation;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.ai.attributes.AttributeMap;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.Path;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import ru.pravets.vasyan.entity.VasyanEntity;
import ru.pravets.vasyan.entity.VasyanInventory;
import ru.pravets.vasyan.test.McTestBootstrap;
import ru.pravets.vasyan.testutil.AbstractMinecraftTest;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Answers.CALLS_REAL_METHODS;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

class VasyanPathNavigationTest extends AbstractMinecraftTest {

    private static final BlockPos TARGET = new BlockPos(6, 64, 6);

    private static Level level;
    private static Map<BlockPos, BlockState> world;
    private static VasyanEntity bot;
    private static VasyanInventory inventory;
    private static long[] gameTime;

    @BeforeAll
    static void stubLevelAndBot() {
        McTestBootstrap.bootstrap();
        initBlockStateCaches();
        world = new HashMap<>();
        gameTime = new long[1];
        level = mock(Level.class);
        when(level.getBlockState(any(BlockPos.class)))
            .thenAnswer(invocation ->
                world.getOrDefault(invocation.getArgument(0), Blocks.AIR.defaultBlockState()));
        when(level.isOutsideBuildHeight(any(BlockPos.class)))
            .thenAnswer(invocation -> {
                BlockPos pos = invocation.getArgument(0);
                return pos.getY() > 320 || pos.getY() < -64;
            });
        when(level.setBlockAndUpdate(any(BlockPos.class), any(BlockState.class))).thenReturn(true);
        when(level.getGameTime()).thenAnswer(invocation -> gameTime[0]);
        bot = mock(VasyanEntity.class);
        when(bot.level()).thenReturn(level);
        inventory = new VasyanInventory();
        when(bot.getInventory()).thenReturn(inventory);
    }

    /** Mirrors the server startup: leaves every BlockState cache unpopulated after plain bootstrap. */
    private static void initBlockStateCaches() {
        for (Block block : BuiltInRegistries.BLOCK) {
            for (BlockState state : block.getStateDefinition().getPossibleStates()) {
                state.initCache();
            }
        }
    }

    private static void resetWorld(Block target, Block neighbor) {
        world.clear();
        if (target != null) {
            world.put(TARGET, target.defaultBlockState());
        }
        if (neighbor != null) {
            world.put(TARGET.west(), neighbor.defaultBlockState());
        }
        clearInvocations(level);
    }

    private static void botCarrying(Block block) {
        inventory.clearContent();
        if (block != null) {
            inventory.addItem(new ItemStack(block.asItem()));
        }
    }

    /** Navigation whose replan decision is observable without a live mob. */
    private static final class ReplanSpy extends VasyanPathNavigation {
        private int replans;

        ReplanSpy() {
            super(bot, VasyanPathNavigationTest.level);
        }

        @Override
        public void recomputePath() {
            replans++;
        }
    }

    /** A one-edge path whose single DIG transition digs {@code foot} at head level open. */
    private static VasyanPath digPath(BlockPos foot) {
        Node from = new Node(foot.getX() - 1, foot.getY(), foot.getZ());
        Node to = new Node(foot.getX() + 1, foot.getY(), foot.getZ());
        VasyanEdge dig = new VasyanEdge(from, to, MoveType.DIG, 4f, foot, null, null);
        return new VasyanPath(List.of(from, to), List.of(dig), to.asBlockPos(), true);
    }

    @Test
    void replacementWalkPathClearsStalePendingDigState() {
        BlockPos foot = new BlockPos(1, 64, 0);
        Node from = new Node(0, 64, 0);
        Node to = new Node(1, 64, 0);
        VasyanEdge oldDig = new VasyanEdge(from, to, MoveType.DIG, 4f, foot, null, null);
        VasyanPath replacement = new VasyanPath(List.of(from, to), List.of(
            new VasyanEdge(from, to, MoveType.WALK, 1f, null, null, null)), to.asBlockPos(), true);
        TestNavigation navigation = new TestNavigation();
        navigation.setPendingDig(oldDig);
        navigation.setPath(replacement);

        navigation.invokeFollowThePath();

        assertFalse(navigation.isDigBlocked(), "a replacement WALK edge must not be blocked by old DIG progress");
        assertTrue(navigation.isDigStateCleared(), "stale DIG metadata must be cleared with the pending edge");
    }

    private static final class TestNavigation extends VasyanPathNavigation {
        TestNavigation() {
            super(bot, VasyanPathNavigationTest.level);
        }

        void setPath(Path path) {
            this.path = path;
        }

        void setPendingDig(VasyanEdge edge) {
            setField("pendingDig", edge);
        }

        void invokeFollowThePath() {
            followThePath();
        }

        boolean isDigBlocked() {
            return getField("pendingDig") != null;
        }

        boolean isDigStateCleared() {
            return getField("pendingDig") == null
                && !((Boolean) getField("dugFoot")) && !((Boolean) getField("dugHead"));
        }

        private void setField(String name, Object value) {
            try {
                var field = VasyanPathNavigation.class.getDeclaredField(name);
                field.setAccessible(true);
                field.set(this, value);
            } catch (ReflectiveOperationException exception) {
                throw new AssertionError(exception);
            }
        }

        @SuppressWarnings("unchecked")
        private <T> T getField(String name) {
            try {
                var field = VasyanPathNavigation.class.getDeclaredField(name);
                field.setAccessible(true);
                return (T) field.get(this);
            } catch (ReflectiveOperationException exception) {
                throw new AssertionError(exception);
            }
        }
    }

    @Test
    void exhaustedPathDoesNotExecuteAnEdge() {
        McTestBootstrap.bootstrap();
        Node node = new Node(0, 64, 0);
        VasyanPath path = new VasyanPath(List.of(node), List.of(), node.asBlockPos(), true);
        assertFalse(VasyanPathNavigation.executeNextEdge(null, path));
    }

    @Test
    void entityCreatesVasyanPathNavigation() {
        McTestBootstrap.bootstrap();
        VasyanEntity entity = mock(VasyanEntity.class, withSettings().defaultAnswer(CALLS_REAL_METHODS));
        when(entity.getAttributes()).thenReturn(mock(AttributeMap.class));

        PathNavigation navigation;
        try {
            var method = VasyanEntity.class.getDeclaredMethod("createNavigation", Level.class);
            method.setAccessible(true);
            navigation = (PathNavigation) method.invoke(entity, mock(Level.class));
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }

        assertInstanceOf(VasyanPathNavigation.class, navigation);
    }

    @Test
    void replanFiresWhenDigCorridorBecomesUndiggable() {
        BlockPos foot = new BlockPos(1, 64, 0);
        world.clear();
        world.put(foot, Blocks.BEDROCK.defaultBlockState());
        gameTime[0] = 100;
        ReplanSpy navigation = new ReplanSpy();

        navigation.maybeReplan(bot, digPath(foot));

        assertEquals(1, navigation.replans,
            "a solid corridor cell that is no longer diggable must trigger exactly one replan");
    }

    @Test
    void replanGateSuppressesChecksInsideTheInterval() {
        BlockPos foot = new BlockPos(1, 64, 0);
        world.clear();
        world.put(foot, Blocks.BEDROCK.defaultBlockState());
        gameTime[0] = 100;
        ReplanSpy navigation = new ReplanSpy();
        navigation.maybeReplan(bot, digPath(foot));
        navigation.replans = 0;

        gameTime[0] = 105;
        navigation.maybeReplan(bot, digPath(foot));
        assertEquals(0, navigation.replans,
            "within NAV_REPLAN_CHECK_INTERVAL_TICKS of the last check the gate must suppress replanning");

        gameTime[0] = 111;
        navigation.maybeReplan(bot, digPath(foot));
        assertEquals(1, navigation.replans,
            "after the interval has elapsed the corridor check must run again");
    }

    @Test
    void replanDoesNotFireWhileCorridorIsStillDiggable() {
        BlockPos foot = new BlockPos(1, 64, 0);
        world.clear();
        world.put(foot, Blocks.DIRT.defaultBlockState());
        gameTime[0] = 100;
        ReplanSpy navigation = new ReplanSpy();

        navigation.maybeReplan(bot, digPath(foot));
        gameTime[0] = 200;
        navigation.maybeReplan(bot, digPath(foot));

        assertEquals(0, navigation.replans,
            "a solid but still diggable corridor cell is mid-dig, not a reason to replan");
    }

    @Test
    void replanDoesNotFireWhenCorridorIsAlreadyPassable() {
        BlockPos foot = new BlockPos(1, 64, 0);
        world.clear();
        gameTime[0] = 100;
        ReplanSpy navigation = new ReplanSpy();

        navigation.maybeReplan(bot, digPath(foot));

        assertEquals(0, navigation.replans,
            "an already cleared (air) corridor cell must not trigger a replan");
    }

    @Test
    void placeReplacesWaterAndConsumesWhitelistedStack() {
        resetWorld(Blocks.WATER, Blocks.COBBLESTONE);
        botCarrying(Blocks.DIRT);

        assertTrue(VasyanPathNavigation.place(bot, TARGET),
            "the planner treats liquid as open, so place() must accept a water cell");

        verify(level).setBlockAndUpdate(TARGET, Blocks.DIRT.defaultBlockState());
        assertEquals(0, inventory.countItem(Blocks.DIRT.asItem()),
            "the consumed scaffold stack must shrink by one");
    }

    @Test
    void placeRejectsASolidNonReplaceableCell() {
        resetWorld(Blocks.STONE, Blocks.COBBLESTONE);
        botCarrying(Blocks.DIRT);

        assertFalse(VasyanPathNavigation.place(bot, TARGET),
            "a solid non-replaceable cell is never a placement target");
        verify(level, org.mockito.Mockito.never()).setBlockAndUpdate(any(BlockPos.class), any(BlockState.class));
    }

    @Test
    void placeRejectsBlocksOutsideTheScaffoldWhitelist() {
        resetWorld(null, Blocks.COBBLESTONE);
        botCarrying(Blocks.GLASS);

        assertFalse(VasyanPathNavigation.place(bot, TARGET),
            "a full-cube block outside NAV_SCAFFOLD_WHITELIST must not be placed");
        verify(level, org.mockito.Mockito.never()).setBlockAndUpdate(any(BlockPos.class), any(BlockState.class));
    }
}
