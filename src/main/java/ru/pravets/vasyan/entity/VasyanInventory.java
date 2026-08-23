package ru.pravets.vasyan.entity;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Vasyan's inventory: a fixed array of slots (default 27, matching a vanilla
 * single chest / the player's main grid) with NBT persistence.
 *
 * <p>Slot indices are stable ({@link ItemStack#EMPTY} fills empty slots), which
 * is required for the vanilla container menu contract: the menu addresses each
 * of the 36 slots by index, and any placement into an empty slot must be
 * stored, never dropped.</p>
 *
 * <p>Implements {@link Container} so players can open Vasyan's inventory via a
 * container menu (right-click on Vasyan) and selectively take items.</p>
 */
public class VasyanInventory implements Container {

    /**
     * Default capacity: 27 slots (3 rows x 9), matching a vanilla single chest
     * / the player's main grid. Deliberately small - capacity upgrades
     * (backpacks etc.) can raise this later.
     */
    public static final int DEFAULT_SIZE = 27;
    private static final String NBT_KEY = "Inventory";

    private final ItemStack[] slots;
    private final int maxSize;
    /** Owner for stillValid() checks; null in unit tests. */
    private final VasyanEntity owner;

    public VasyanInventory() {
        this(null, DEFAULT_SIZE);
    }

    public VasyanInventory(int maxSize) {
        this(null, maxSize);
    }

    public VasyanInventory(VasyanEntity owner, int maxSize) {
        this.owner = owner;
        this.maxSize = maxSize;
        this.slots = new ItemStack[maxSize];
        Arrays.fill(slots, ItemStack.EMPTY);
    }

    /**
     * Attempts to add the given stack (or part of it) to the inventory.
     * Returns what could NOT be stored (empty stack if everything fit).
     */
    public ItemStack addItem(ItemStack incoming) {
        if (incoming.isEmpty()) {
            return ItemStack.EMPTY;
        }

        ItemStack remainder = incoming.copy();

        // Merge into existing non-full stacks of the same item
        for (int i = 0; i < maxSize && !remainder.isEmpty(); i++) {
            ItemStack slot = slots[i];
            if (!slot.isEmpty() && ItemStack.isSameItemSameTags(slot, remainder)) {
                int space = slot.getMaxStackSize() - slot.getCount();
                if (space > 0) {
                    int move = Math.min(space, remainder.getCount());
                    slot.grow(move);
                    remainder.shrink(move);
                }
            }
        }

        // Place into the first empty slot
        for (int i = 0; i < maxSize && !remainder.isEmpty(); i++) {
            if (slots[i].isEmpty()) {
                int perStack = Math.min(remainder.getCount(), remainder.getMaxStackSize());
                ItemStack newStack = remainder.copy();
                newStack.setCount(perStack);
                slots[i] = newStack;
                remainder.shrink(perStack);
            }
        }

        return remainder;
    }

    /**
     * Whether the inventory can hold at least one more item.
     */
    public boolean hasFreeSpace() {
        for (int i = 0; i < maxSize; i++) {
            if (slots[i].isEmpty()) {
                return true;
            }
            if (slots[i].getCount() < slots[i].getMaxStackSize()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether this specific item can still be stored: an empty slot or a
     * partially filled stack of the same item. Used by the "fill inventory"
     * gather mode - the bot keeps mining until there is no room left for the
     * requested resource.
     */
    public boolean hasSpaceFor(Item item) {
        for (int i = 0; i < maxSize; i++) {
            if (slots[i].isEmpty()) {
                return true;
            }
            if (slots[i].is(item) && slots[i].getCount() < slots[i].getMaxStackSize()) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean isEmpty() {
        for (ItemStack slot : slots) {
            if (!slot.isEmpty()) {
                return false;
            }
        }
        return true;
    }

    /**
     * Total count of items of the given type across all slots.
     */
    public int countItem(Item item) {
        int total = 0;
        for (ItemStack slot : slots) {
            if (!slot.isEmpty() && slot.is(item)) {
                total += slot.getCount();
            }
        }
        return total;
    }

    /**
     * Total number of items across all slots.
     */
    public int getTotalCount() {
        int total = 0;
        for (ItemStack slot : slots) {
            if (!slot.isEmpty()) {
                total += slot.getCount();
            }
        }
        return total;
    }

    /**
     * Number of non-empty slots.
     */
    public int getStacksCount() {
        int count = 0;
        for (ItemStack slot : slots) {
            if (!slot.isEmpty()) {
                count++;
            }
        }
        return count;
    }

    public int getMaxSize() {
        return maxSize;
    }

    /**
     * Read-only view of the non-empty stacks, in slot order.
     */
    public List<ItemStack> getStacks() {
        List<ItemStack> nonEmpty = new ArrayList<>();
        for (ItemStack slot : slots) {
            if (!slot.isEmpty()) {
                nonEmpty.add(slot);
            }
        }
        return Collections.unmodifiableList(nonEmpty);
    }

    /**
     * Removes and returns everything (used for handing items over to a player).
     */
    public List<ItemStack> takeAll() {
        List<ItemStack> taken = new ArrayList<>();
        for (int i = 0; i < maxSize; i++) {
            if (!slots[i].isEmpty()) {
                taken.add(slots[i]);
                slots[i] = ItemStack.EMPTY;
            }
        }
        return taken;
    }

    public void saveToNBT(CompoundTag tag) {
        ListTag list = new ListTag();
        for (int i = 0; i < slots.length; i++) {
            ItemStack slot = slots[i];
            if (!slot.isEmpty()) {
                CompoundTag slotTag = new CompoundTag();
                slotTag.putInt("Slot", i);
                slot.save(slotTag);
                list.add(slotTag);
            }
        }
        tag.put(NBT_KEY, list);
    }

    public void loadFromNBT(CompoundTag tag) {
        Arrays.fill(slots, ItemStack.EMPTY);
        ListTag list = tag.getList(NBT_KEY, Tag.TAG_COMPOUND);
        int fallbackIndex = 0;
        for (int i = 0; i < list.size() && fallbackIndex < maxSize; i++) {
            CompoundTag slotTag = list.getCompound(i);
            ItemStack stack = ItemStack.of(slotTag);
            if (stack.isEmpty()) {
                continue;
            }
            if (slotTag.contains("Slot")) {
                int slotIndex = slotTag.getInt("Slot");
                if (slotIndex >= 0 && slotIndex < maxSize) {
                    slots[slotIndex] = stack;
                }
            } else {
                // Legacy compact format: items are stored in order.
                slots[fallbackIndex++] = stack;
            }
        }
    }

    public String toDisplayString() {
        if (isEmpty()) {
            return "empty";
        }
        StringBuilder sb = new StringBuilder();
        for (ItemStack stack : getStacks()) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(stack.getHoverName().getString()).append(" x").append(stack.getCount());
        }
        return sb.toString();
    }

    // ==================== Container implementation ====================
    // Fixed slot model: indices are stable, empty slots are ItemStack.EMPTY.

    @Override
    public int getContainerSize() {
        return maxSize;
    }

    @Override
    public ItemStack getItem(int slot) {
        return slot >= 0 && slot < maxSize ? slots[slot] : ItemStack.EMPTY;
    }

    @Override
    public ItemStack removeItem(int slot, int amount) {
        if (slot < 0 || slot >= maxSize || slots[slot].isEmpty()) {
            return ItemStack.EMPTY;
        }
        ItemStack stack = slots[slot];
        int toRemove = Math.min(amount, stack.getCount());
        ItemStack removed = stack.copy();
        removed.setCount(toRemove);
        stack.shrink(toRemove);
        if (stack.isEmpty()) {
            slots[slot] = ItemStack.EMPTY; // clear without shifting later slots
        }
        return removed;
    }

    @Override
    public ItemStack removeItemNoUpdate(int slot) {
        if (slot < 0 || slot >= maxSize) {
            return ItemStack.EMPTY;
        }
        ItemStack stack = slots[slot];
        slots[slot] = ItemStack.EMPTY;
        return stack;
    }

    @Override
    public void setItem(int slot, ItemStack stack) {
        if (slot < 0 || slot >= maxSize) {
            return;
        }
        // Any valid slot is written (including empty slots beyond the last
        // non-empty one) - a placed item is never silently dropped.
        slots[slot] = stack.isEmpty() ? ItemStack.EMPTY : stack.copy();
    }

    @Override
    public void setChanged() {
        // No-op: VasyanInventory is not a block entity
    }

    @Override
    public boolean stillValid(Player player) {
        if (owner == null) {
            return true; // unit tests / detached inventory
        }
        return owner.isAlive()
            && !owner.isRemoved()
            && owner.level().dimension() == player.level().dimension()
            && player.distanceToSqr(owner) <= 8.0 * 8.0;
    }

    @Override
    public void clearContent() {
        Arrays.fill(slots, ItemStack.EMPTY);
    }
}
