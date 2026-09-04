package ru.pravets.vasyan.navigation;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import ru.pravets.vasyan.entity.VasyanInventory;
import ru.pravets.vasyan.test.McTestBootstrap;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;

/**
 * ScaffoldBlocks selection over a real {@link VasyanInventory} and a stubbed {@link Level}.
 *
 * <p>Tag note: {@code Bootstrap.bootStrap()} does not bind datapack tags, so
 * {@code BlockTags.PLANKS}/{@code LOGS} holders are bound manually in
 * {@link #bindPlankAndLogTags()} - exactly what TagManager would do after a datapack
 * load - so the planks/logs score branch can be exercised headlessly.</p>
 */
class ScaffoldBlocksTest {

    private static final BlockPos REF = new BlockPos(0, 64, 0);

    @BeforeAll
    static void bootstrap() {
        McTestBootstrap.bootstrap();
        bindPlankAndLogTags();
    }

    @SuppressWarnings("unchecked")
    private static void bindPlankAndLogTags() {
        Map<TagKey<Block>, List<Holder<Block>>> tags = Map.of(
            BlockTags.PLANKS, List.of(Blocks.OAK_PLANKS.builtInRegistryHolder()),
            BlockTags.LOGS, List.of(Blocks.OAK_LOG.builtInRegistryHolder()));
        ((MappedRegistry<Block>) BuiltInRegistries.BLOCK).bindTags(tags);
    }

    private static Level level() {
        return mock(Level.class);
    }

    private static VasyanInventory inventoryWith(Block... blocks) {
        VasyanInventory inventory = new VasyanInventory();
        for (Block block : blocks) {
            inventory.addItem(new ItemStack(block.asItem()));
        }
        return inventory;
    }

    @Test
    void scorePrefersDisposableGroundMaterial() {
        assertEquals(0, ScaffoldBlocks.score(Blocks.DIRT.defaultBlockState(), level(), REF));
        assertEquals(0, ScaffoldBlocks.score(Blocks.GRAVEL.defaultBlockState(), level(), REF));
    }

    @Test
    void scoreRanksCobblestoneAboveGroundMaterial() {
        assertEquals(1, ScaffoldBlocks.score(Blocks.COBBLESTONE.defaultBlockState(), level(), REF));
        assertEquals(1, ScaffoldBlocks.score(Blocks.STONE.defaultBlockState(), level(), REF));
    }

    @Test
    void scoreRanksPlanksAndLogsAsBuildingMaterial() {
        assertEquals(2, ScaffoldBlocks.score(Blocks.OAK_PLANKS.defaultBlockState(), level(), REF));
        assertEquals(2, ScaffoldBlocks.score(Blocks.OAK_LOG.defaultBlockState(), level(), REF));
    }

    @Test
    void scoreFallsBackToGenericForEverythingElse() {
        assertEquals(3, ScaffoldBlocks.score(Blocks.GLASS.defaultBlockState(), level(), REF));
    }

    @Test
    void scoringOrderIsDirtThenCobbleThenPlanks() {
        int dirt = ScaffoldBlocks.score(Blocks.DIRT.defaultBlockState(), level(), REF);
        int cobble = ScaffoldBlocks.score(Blocks.COBBLESTONE.defaultBlockState(), level(), REF);
        int planks = ScaffoldBlocks.score(Blocks.OAK_PLANKS.defaultBlockState(), level(), REF);
        assertEquals(0, dirt);
        assertEquals(1, cobble);
        assertEquals(2, planks);
    }

    @Test
    void emptyInventoryHasNoScaffoldBlock() {
        assertNull(ScaffoldBlocks.findBestStack(new VasyanInventory(), level(), REF));
    }

    @Test
    void inventoryWithoutBlockItemsHasNoScaffoldBlock() {
        VasyanInventory inventory = new VasyanInventory();
        inventory.addItem(new ItemStack(Items.STICK));
        assertNull(ScaffoldBlocks.findBestStack(inventory, level(), REF));
    }

    @Test
    void partialShapesAreNotStandableSupport() {
        VasyanInventory inventory = inventoryWith(Blocks.STONE_SLAB);
        assertNull(ScaffoldBlocks.findBestStack(inventory, level(), REF),
            "a slab is not a full collision cube and must never be picked as scaffold");
    }

    @Test
    void picksDirtOverCobblestoneAndPlanks() {
        VasyanInventory inventory = inventoryWith(Blocks.OAK_PLANKS, Blocks.COBBLESTONE, Blocks.DIRT);
        ItemStack best = ScaffoldBlocks.findBestStack(inventory, level(), REF);
        assertEquals(Blocks.DIRT.asItem(), best.getItem());
    }

    @Test
    void picksPlanksOverGenericBlocks() {
        VasyanInventory inventory = inventoryWith(Blocks.GLASS, Blocks.OAK_PLANKS);
        ItemStack best = ScaffoldBlocks.findBestStack(inventory, level(), REF);
        assertEquals(Blocks.OAK_PLANKS.asItem(), best.getItem());
    }

    @Test
    void returnsTheActualInventoryStackSoPlacementCanConsumeIt() {
        VasyanInventory inventory = inventoryWith(Blocks.DIRT);
        ItemStack best = ScaffoldBlocks.findBestStack(inventory, level(), REF);
        assertSame(inventory.getStacks().get(0), best,
            "the caller shrinks the returned stack by one on placement");
    }
}
