package ru.pravets.vasyan.navigation;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import ru.pravets.vasyan.test.McTestBootstrap;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * DigRules predicates over a stubbed {@link Level}: a {@link BlockPos}-keyed map of
 * real block states (everything unlisted reads as air). The full-collision / destroy
 * speed paths of real {@link BlockState}s never touch the level beyond
 * {@code getBlockState}, so a Mockito stub is sufficient and honest here - no real
 * world can be spun up in a plain unit test.
 */
class DigRulesTest {

    private static final BlockPos POS = new BlockPos(0, 64, 0);

    /**
     * One shared mock for the whole class: Mockito recompiles heavy method graphs for
     * {@link Level} default methods (see isFallingBlock's isOutsideBuildHeight loop), so
     * mocking per test exhausts the test JVM heap. Tests are single-threaded; each test
     * installs its own block map via {@link #levelWith(Map)}.
     */
    private static Level level;
    private static Map<BlockPos, BlockState> world;

    @BeforeAll
    static void bootstrap() {
        McTestBootstrap.bootstrap();
        initBlockStateCaches();
        world = new HashMap<>();
        level = mock(Level.class);
        when(level.getBlockState(any(BlockPos.class)))
            .thenAnswer(invocation ->
                world.getOrDefault(invocation.getArgument(0), Blocks.AIR.defaultBlockState()));
        when(level.isOutsideBuildHeight(anyInt()))
            .thenAnswer(invocation -> (int) invocation.getArgument(0) > 320);
    }

    /**
     * Bootstrap alone leaves every {@link BlockState} cache unpopulated (the server
     * initializes them during startup), so {@code getFluidState()} would misleadingly
     * report EMPTY for water/lava. Initializing all states mirrors the server and
     * makes real fluid states observable headlessly.
     */
    private static void initBlockStateCaches() {
        for (Block block : BuiltInRegistries.BLOCK) {
            for (BlockState state : block.getStateDefinition().getPossibleStates()) {
                state.initCache();
            }
        }
    }

    /**
     * Installs the given {@code BlockPos -> BlockState} map as the world seen by the
     * shared level stub; unlisted cells read as air.
     *
     * @return the shared stubbed {@link Level}
     */
    private static Level levelWith(Map<BlockPos, BlockState> states) {
        world.clear();
        world.putAll(states);
        return level;
    }

    @Test
    void oreIsNotBreakableByDefault() {
        Level level = levelWith(Map.of(POS, Blocks.DIAMOND_ORE.defaultBlockState()));
        assertFalse(DigRules.isBreakable(level, POS, false),
            "navigation digs destroy blocks without drops, so ores must stay unbreakable");
    }

    @Test
    void oreIsBreakableWhenExplicitlyIncluded() {
        Level level = levelWith(Map.of(POS, Blocks.DIAMOND_ORE.defaultBlockState()));
        assertTrue(DigRules.isBreakable(level, POS, true),
            "includeOres=true lifts only the NEVER_BREAK guard, not the UNBREAKABLE/speed rules");
    }

    @Test
    void oreIncludeFlagStillRespectsUnbreakableGuard() {
        Level level = levelWith(Map.of(POS, Blocks.ANCIENT_DEBRIS.defaultBlockState()));
        assertTrue(DigRules.isBreakable(level, POS, true),
            "ancient debris is in NEVER_BREAK but not UNBREAKABLE and is minable by speed");
    }

    @Test
    void bedrockIsNeverBreakable() {
        Level level = levelWith(Map.of(POS, Blocks.BEDROCK.defaultBlockState()));
        assertFalse(DigRules.isBreakable(level, POS, false), "bedrock is in UNBREAKABLE");
        assertFalse(DigRules.isBreakable(level, POS, true), "UNBREAKABLE applies even with includeOres");
    }

    @Test
    void obsidianIsNeverBreakable() {
        Level level = levelWith(Map.of(POS, Blocks.OBSIDIAN.defaultBlockState()));
        assertFalse(DigRules.isBreakable(level, POS, false), "obsidian is in UNBREAKABLE");
    }

    @Test
    void dirtIsBreakable() {
        Level level = levelWith(Map.of(POS, Blocks.DIRT.defaultBlockState()));
        assertTrue(DigRules.isBreakable(level, POS, false));
        assertTrue(DigRules.isBreakable(level, POS, true));
    }

    @Test
    void airIsNotBreakable() {
        Level level = levelWith(Map.of());
        assertFalse(DigRules.isBreakable(level, POS, false), "nothing to break in air");
    }

    @Test
    void waterIsNotBreakable() {
        Level level = levelWith(Map.of(POS, Blocks.WATER.defaultBlockState()));
        assertFalse(DigRules.isBreakable(level, POS, false), "liquid cells are passable, not obstacles");
    }

    @Test
    void lavaIsNotBreakable() {
        Level level = levelWith(Map.of(POS, Blocks.LAVA.defaultBlockState()));
        assertFalse(DigRules.isBreakable(level, POS, false));
    }

    @Test
    void diggingNextToWaterWouldCreateFlow() {
        Level level = levelWith(Map.of(
            POS, Blocks.DIRT.defaultBlockState(),
            POS.east(), Blocks.WATER.defaultBlockState()));
        assertTrue(DigRules.wouldCreateFlow(level, POS),
            "breaking a block adjacent to water opens a side flow into the tunnel");
    }

    @Test
    void diggingNextToLavaWouldCreateFlow() {
        Level level = levelWith(Map.of(
            POS, Blocks.DIRT.defaultBlockState(),
            POS.above(), Blocks.LAVA.defaultBlockState()));
        assertTrue(DigRules.wouldCreateFlow(level, POS));
    }

    @Test
    void dryTunnelWouldNotCreateFlow() {
        Level level = levelWith(Map.of(POS, Blocks.DIRT.defaultBlockState()));
        assertFalse(DigRules.wouldCreateFlow(level, POS));
    }

    @Test
    void sandAboveIsFallingHazard() {
        Level level = levelWith(Map.of(POS.above(), Blocks.SAND.defaultBlockState()));
        assertTrue(DigRules.isFallingBlock(level, POS));
    }

    @Test
    void gravelAboveIsFallingHazard() {
        Level level = levelWith(Map.of(POS.above(), Blocks.GRAVEL.defaultBlockState()));
        assertTrue(DigRules.isFallingBlock(level, POS));
    }

    @Test
    void anvilAboveIsFallingHazard() {
        Level level = levelWith(Map.of(POS.above(), Blocks.ANVIL.defaultBlockState()));
        assertTrue(DigRules.isFallingBlock(level, POS));
    }

    @Test
    void damagedAnvilAboveIsFallingHazard() {
        Level level = levelWith(Map.of(POS.above(), Blocks.DAMAGED_ANVIL.defaultBlockState()));
        assertTrue(DigRules.isFallingBlock(level, POS));
    }

    @Test
    void solidCeilingIsNotFallingHazard() {
        Level level = levelWith(Map.of(POS.above(), Blocks.STONE.defaultBlockState()));
        assertFalse(DigRules.isFallingBlock(level, POS), "a solid ceiling holds the column");
    }

    @Test
    void sandBehindSolidCeilingIsNotFallingHazard() {
        Level level = levelWith(Map.of(
            POS.above(), Blocks.STONE.defaultBlockState(),
            POS.above().above(), Blocks.SAND.defaultBlockState()));
        assertFalse(DigRules.isFallingBlock(level, POS),
            "sand resting on a solid ceiling cannot fall into this cell");
    }

    @Test
    void openColumnIsNotFallingHazard() {
        // A distant solid cap bounds the scan; only air lies between it and the cell.
        Level level = levelWith(Map.of(POS.above().above().above(), Blocks.STONE.defaultBlockState()));
        assertFalse(DigRules.isFallingBlock(level, POS), "clear sky above is safe");
    }
}
