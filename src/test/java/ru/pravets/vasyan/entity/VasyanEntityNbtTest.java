package ru.pravets.vasyan.entity;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;
import ru.pravets.vasyan.action.ActionExecutor;
import ru.pravets.vasyan.memory.VasyanMemory;
import ru.pravets.vasyan.testutil.AbstractMinecraftTest;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Regression tests for VasyanEntity NBT save/load.
 */
class VasyanEntityNbtTest extends AbstractMinecraftTest {

    @Test
    void vasyanDataRoundTripPreservesNameInventoryMemoryAndStaying() throws Exception {
        VasyanEntity entity = mock(VasyanEntity.class, withSettings().defaultAnswer(CALLS_REAL_METHODS));

        // Set up real subcomponents without invoking the full entity constructor.
        VasyanMemory memory = new VasyanMemory(entity);
        memory.setCurrentGoal("gather wood");
        memory.addAction("chopped oak");

        VasyanInventory inventory = new VasyanInventory(entity, VasyanInventory.DEFAULT_SIZE);
        inventory.setItem(0, new ItemStack(Items.OAK_LOG, 7));
        inventory.setItem(4, new ItemStack(Items.STONE_AXE));

        ActionExecutor actionExecutor = mock(ActionExecutor.class);
        when(actionExecutor.isStaying()).thenReturn(true);

        setField(entity, "vasyanName", "Bob");
        setField(entity, "memory", memory);
        setField(entity, "inventory", inventory);
        setField(entity, "actionExecutor", actionExecutor);

        // Stub setVasyanName so it does not touch uninitialized entity data / custom name logic.
        doAnswer(invocation -> {
            setField(entity, "vasyanName", invocation.getArgument(0));
            return null;
        }).when(entity).setVasyanName(anyString());

        CompoundTag tag = new CompoundTag();
        entity.writeVasyanSaveData(tag);

        assertEquals("Bob", tag.getString("VasyanName"));
        assertTrue(tag.contains("Memory"));
        assertTrue(tag.contains("Inventory"));
        assertTrue(tag.getBoolean("Staying"));

        // Reset entity state and reload from NBT.
        VasyanMemory freshMemory = new VasyanMemory(entity);
        VasyanInventory freshInventory = new VasyanInventory(entity, VasyanInventory.DEFAULT_SIZE);
        setField(entity, "vasyanName", "Vasyan");
        setField(entity, "memory", freshMemory);
        setField(entity, "inventory", freshInventory);

        entity.readVasyanSaveData(tag);

        assertEquals("Bob", getField(entity, "vasyanName"));
        assertEquals("gather wood", freshMemory.getCurrentGoal());
        assertTrue(freshMemory.getRecentActions(10).contains("chopped oak"));
        assertEquals(7, freshInventory.getItem(0).getCount());
        assertEquals(Items.OAK_LOG, freshInventory.getItem(0).getItem());
        assertFalse(freshInventory.getItem(4).isEmpty());
        assertEquals(Items.STONE_AXE, freshInventory.getItem(4).getItem());
        verify(actionExecutor).setStaying(true);
    }

    @Test
    void vasyanDataRoundTripPreservesEmptyState() throws Exception {
        VasyanEntity entity = mock(VasyanEntity.class, withSettings().defaultAnswer(CALLS_REAL_METHODS));

        VasyanMemory memory = new VasyanMemory(entity);
        VasyanInventory inventory = new VasyanInventory(entity, VasyanInventory.DEFAULT_SIZE);
        ActionExecutor actionExecutor = mock(ActionExecutor.class);
        when(actionExecutor.isStaying()).thenReturn(false);

        setField(entity, "vasyanName", "Vasyan");
        setField(entity, "memory", memory);
        setField(entity, "inventory", inventory);
        setField(entity, "actionExecutor", actionExecutor);

        doAnswer(invocation -> {
            setField(entity, "vasyanName", invocation.getArgument(0));
            return null;
        }).when(entity).setVasyanName(anyString());

        CompoundTag tag = new CompoundTag();
        entity.writeVasyanSaveData(tag);

        VasyanMemory freshMemory = new VasyanMemory(entity);
        VasyanInventory freshInventory = new VasyanInventory(entity, VasyanInventory.DEFAULT_SIZE);
        setField(entity, "vasyanName", "Other");
        setField(entity, "memory", freshMemory);
        setField(entity, "inventory", freshInventory);

        entity.readVasyanSaveData(tag);

        assertEquals("Vasyan", getField(entity, "vasyanName"));
        assertTrue(freshMemory.getRecentActions(10).isEmpty());
        assertTrue(freshInventory.isEmpty());
        verify(actionExecutor, never()).setStaying(true);
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = VasyanEntity.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static Object getField(Object target, String name) throws Exception {
        Field field = VasyanEntity.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }
}
