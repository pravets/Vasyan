package ru.pravets.vasyan.entity;

import ru.pravets.vasyan.action.ActionExecutor;
import ru.pravets.vasyan.memory.VasyanMemory;
import ru.pravets.vasyan.menu.VasyanMenu;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import org.jetbrains.annotations.Nullable;

import java.util.List;

public class VasyanEntity extends PathfinderMob {
    private static final EntityDataAccessor<String> VASYAN_NAME = 
        SynchedEntityData.defineId(VasyanEntity.class, EntityDataSerializers.STRING);

    private String vasyanName;
    private VasyanMemory memory;
    private ActionExecutor actionExecutor;
    private VasyanInventory inventory;
    private int tickCounter = 0;
    private int pickupCooldown = 0;
    private boolean isFlying = false;
    private boolean isInvulnerable = false;
    /**
     * When true, remove() must not drop the inventory into the world. Used when
     * discarding a duplicate bot so its NBT-inventory is not duped as items.
     * Intentionally NOT reset after remove(): the entity is being discarded and
     * will never tick or be reused again, so there is no stale state to clear.
     */
    private boolean suppressInventoryDrop = false;

    /**
     * True when this instance was deserialized from NBT (chunk load / dimension
     * load) and therefore carries persisted state (inventory, memory). Freshly
     * spawned instances keep this false. Used by the manager dedup to prefer a
     * chunk-loaded bot over an empty fresh spawn with the same name.
     */
    private boolean loadedFromNbt = false;

    /** Pickup radius for items lying on the ground, in blocks. */
    private static final double PICKUP_RADIUS = 5.0;
    /** Pickup scan every N ticks (20 ticks = 1 second). */
    private static final int PICKUP_INTERVAL = 10;

    public VasyanEntity(EntityType<? extends PathfinderMob> entityType, Level level) {
        super(entityType, level);
        this.vasyanName = "Vasyan";
        this.memory = new VasyanMemory(this);
        this.actionExecutor = new ActionExecutor(this);
        this.inventory = new VasyanInventory(this, VasyanInventory.DEFAULT_SIZE);
        this.setCustomNameVisible(true);
        this.setPersistenceRequired();

        this.isInvulnerable = true;
        this.setInvulnerable(true);
    }

    public static AttributeSupplier.Builder createAttributes() {
        return Mob.createMobAttributes()
            .add(Attributes.MAX_HEALTH, 20.0D)
            .add(Attributes.MOVEMENT_SPEED, 0.25D)
            .add(Attributes.ATTACK_DAMAGE, 8.0D)
            .add(Attributes.FOLLOW_RANGE, 48.0D);
    }

    /**
     * Amphibious navigation: walks on land AND swims across water. The
     * default GroundPathNavigation treats water as a hard obstacle (no path
     * into/through it), which forced bridge-building hacks and swamp
     * workarounds; swimming removes that whole problem class.
     */
    @Override
    protected net.minecraft.world.entity.ai.navigation.PathNavigation createNavigation(Level level) {
        return new net.minecraft.world.entity.ai.navigation.AmphibiousPathNavigation(this, level);
    }

    @Override
    protected void registerGoals() {
        this.goalSelector.addGoal(0, new FloatGoal(this));
        this.goalSelector.addGoal(1, new LookAtPlayerGoal(this, Player.class, 8.0F));
        this.goalSelector.addGoal(2, new RandomLookAroundGoal(this));
    }

    @Override
    protected void defineSynchedData() {
        super.defineSynchedData();
        this.entityData.define(VASYAN_NAME, "Vasyan");
    }

    @Override
    public void remove(RemovalReason reason) {
        // Drop the inventory into the world when Vasyan is killed or discarded
        // (/kill, /vasyan remove) instead of silently losing the contents.
        // Unloading/changing dimension must keep the inventory (it is in NBT).
        // Dedup discards of duplicate bots must NOT drop: the duplicate carries
        // the same NBT inventory, so dropping it would be an item-dupe exploit.
        if (!this.level().isClientSide && !suppressInventoryDrop && !inventory.isEmpty()
                && (reason == RemovalReason.KILLED || reason == RemovalReason.DISCARDED)) {
            for (ItemStack stack : inventory.takeAll()) {
                this.spawnAtLocation(stack);
            }
        }
        releaseForcedChunk(reason);
        super.remove(reason);
    }

    /**
     * Releases the force-loaded chunk, but only for permanent removals.
     * Chunk unloads and dimension changes must keep the force so the bot's
     * chunk reloads without a player nearby.
     */
    void releaseForcedChunk(RemovalReason reason) {
        if (!this.level().isClientSide && forcedChunk != null
                && ru.pravets.vasyan.VasyanMod.getVasyanManager() != null
                && (reason == RemovalReason.KILLED || reason == RemovalReason.DISCARDED)) {
            // Permanent removal: release our chunk force-load. Transient removals
            // (chunk unload, dimension change) must keep the force so the bot's
            // chunk reloads without a player nearby.
            ru.pravets.vasyan.VasyanMod.getVasyanManager().releaseChunk(this, (net.minecraft.server.level.ServerLevel) this.level());
            forcedChunk = null;
        }
    }

    /** Chunk currently force-loaded for this Vasyan (tracked by VasyanManager). */
    private ChunkForceTracker.ChunkKey forcedChunk;

    public ChunkForceTracker.ChunkKey getForcedChunk() {
        return forcedChunk;
    }

    public void setForcedChunk(ChunkForceTracker.ChunkKey forcedChunk) {
        this.forcedChunk = forcedChunk;
    }

    public void setSuppressInventoryDrop(boolean suppress) {
        this.suppressInventoryDrop = suppress;
    }

    /** True when this instance was loaded from NBT (persisted state). */
    public boolean isLoadedFromNbt() {
        return this.loadedFromNbt;
    }

    @Override
    public void tick() {
        super.tick();
        
        if (!this.level().isClientSide) {
            actionExecutor.tick();
            tickPickup();
        }
    }

    /**
     * Periodically picks up nearby item entities into Vasyan's inventory.
     */
    private void tickPickup() {
        if (--pickupCooldown > 0) {
            return;
        }
        pickupCooldown = PICKUP_INTERVAL;

        if (!inventory.hasFreeSpace()) {
            return; // Inventory full - leave items on the ground
        }

        AABB searchBox = this.getBoundingBox().inflate(PICKUP_RADIUS);
        List<ItemEntity> items = this.level().getEntitiesOfClass(ItemEntity.class, searchBox);
        for (ItemEntity item : items) {
            if (item.isRemoved() || !item.isAlive()) {
                continue;
            }
            // Respect vanilla pickup delay: items thrown by a player (Q), tossed
            // to teammates or dropped on death must not be vacuumed instantly
            if (item.hasPickUpDelay()) {
                continue;
            }
            ItemStack stack = item.getItem();
            if (stack.isEmpty()) {
                continue;
            }
            ItemStack remainder = inventory.addItem(stack);
            if (remainder.getCount() < stack.getCount()) {
                // We picked up at least part of the stack
                if (remainder.isEmpty()) {
                    item.discard();
                } else {
                    item.setItem(remainder);
                }
            }
            if (!inventory.hasFreeSpace()) {
                break; // Inventory full - stop picking up
            }
        }
    }

    public void setVasyanName(String name) {
        this.vasyanName = name;
        this.entityData.set(VASYAN_NAME, name);
        this.setCustomName(Component.literal(name));
    }

    public String getVasyanName() {
        return this.vasyanName;
    }

    public VasyanMemory getMemory() {
        return this.memory;
    }

    public VasyanInventory getInventory() {
        return this.inventory;
    }

    /**
     * Teleports this Vasyan to a safe spot near the given player.
     * Fails (returns false) if the Vasyan is in another dimension or no
     * safe spot was found. Reused by the auto-return logic (Stage 3:
     * full inventory -> return to player -> hand over -> go back).
     */
    public boolean teleportToPlayer(ServerPlayer player) {
        if (this.level().dimension() != player.level().dimension()) {
            return false;
        }
        return teleportToPos(player.blockPosition());
    }

    /**
     * Teleports this Vasyan to a safe spot near the given position (same
     * dimension only). This is the reusable primitive for future teleport
     * targets (mines, home base, ...).
     */
    public boolean teleportToPos(BlockPos pos) {
        if (pos == null) {
            return false;
        }
        BlockPos target = VasyanTeleportUtil.findSafePos(pos, this::isSafeTeleportSpot);
        if (target == null) {
            return false;
        }
        this.teleportTo(target.getX() + 0.5, target.getY(), target.getZ() + 0.5);
        return true;
    }

    private boolean isSafeTeleportSpot(int x, int y, int z) {
        Level level = this.level();
        BlockPos groundPos = new BlockPos(x, y - 1, z);
        BlockState ground = level.getBlockState(groundPos);
        BlockState at = level.getBlockState(new BlockPos(x, y, z));
        BlockState above = level.getBlockState(new BlockPos(x, y + 1, z));
        // isValidSpawn rejects cacti, magma, powder snow etc. that isSolid() accepts
        return ground.isValidSpawn(level, groundPos, this.getType())
            && at.isAir()
            && above.isAir();
    }

    public ActionExecutor getActionExecutor() {
        return this.actionExecutor;
    }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        writeVasyanSaveData(tag);
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        this.loadedFromNbt = true;
        readVasyanSaveData(tag);
    }

    /**
     * Writes Vasyan-specific data to the given NBT tag. Extracted so unit
     * tests can verify the round-trip without needing a running game server.
     */
    void writeVasyanSaveData(CompoundTag tag) {
        tag.putString("VasyanName", this.vasyanName);

        CompoundTag memoryTag = new CompoundTag();
        this.memory.saveToNBT(memoryTag);
        tag.put("Memory", memoryTag);

        CompoundTag inventoryTag = new CompoundTag();
        this.inventory.saveToNBT(inventoryTag);
        tag.put("Inventory", inventoryTag);

        tag.putBoolean("Staying", this.actionExecutor.isStaying());
    }

    /**
     * Reads Vasyan-specific data from the given NBT tag. Extracted so unit
     * tests can verify the round-trip without needing a running game server.
     */
    void readVasyanSaveData(CompoundTag tag) {
        if (tag.contains("VasyanName")) {
            this.setVasyanName(tag.getString("VasyanName"));
        }

        if (tag.contains("Memory")) {
            this.memory.loadFromNBT(tag.getCompound("Memory"));
        }

        if (tag.contains("Inventory")) {
            this.inventory.loadFromNBT(tag.getCompound("Inventory"));
        }

        if (tag.contains("Staying") && tag.getBoolean("Staying")) {
            // Persist "stay in place" across restarts (guard post etc.)
            this.actionExecutor.setStaying(true);
        }
    }

    @Override
    @Nullable
    public SpawnGroupData finalizeSpawn(ServerLevelAccessor level, DifficultyInstance difficulty,
                                       MobSpawnType spawnType, @Nullable SpawnGroupData spawnData,
                                       @Nullable CompoundTag tag) {
        spawnData = super.finalizeSpawn(level, difficulty, spawnType, spawnData, tag);
        return spawnData;
    }

    public void sendChatMessage(String message) {
        if (this.level().isClientSide) return;
        
        Component chatComponent = Component.literal("<" + this.vasyanName + "> " + message);
        this.level().players().forEach(player -> player.sendSystemMessage(chatComponent));
    }

    /**
     * Right-click on a Vasyan opens its inventory as a take-only container menu:
     * the player can selectively take items, but cannot place items into Vasyan.
     */
    @Override
    public InteractionResult mobInteract(Player player, InteractionHand hand) {
        if (!this.level().isClientSide && player instanceof ServerPlayer serverPlayer) {
            serverPlayer.openMenu(new SimpleMenuProvider(
                (containerId, playerInventory, p) ->
                    new VasyanMenu(containerId, playerInventory, this.inventory),
                Component.literal(this.vasyanName + "'s Inventory")));
        }
        return InteractionResult.sidedSuccess(this.level().isClientSide);
    }

    @Override
    protected void dropCustomDeathLoot(net.minecraft.world.damagesource.DamageSource source, int looting, boolean recentlyHit) {
        super.dropCustomDeathLoot(source, looting, recentlyHit);
    }

    public void setFlying(boolean flying) {
        this.isFlying = flying;
        this.setNoGravity(flying);
        this.setInvulnerableBuilding(flying);
    }

    public boolean isFlying() {
        return this.isFlying;
    }

    /**
     * Set invulnerability for building (immune to ALL damage: fire, lava, suffocation, fall, etc.)
     */
    public void setInvulnerableBuilding(boolean invulnerable) {
        this.isInvulnerable = invulnerable;
        this.setInvulnerable(invulnerable); // Minecraft's built-in invulnerability
    }

    @Override
    public boolean hurt(net.minecraft.world.damagesource.DamageSource source, float amount) {
        return false;
    }

    @Override
    public boolean isInvulnerableTo(net.minecraft.world.damagesource.DamageSource source) {
        return true;
    }

    @Override
    public void travel(net.minecraft.world.phys.Vec3 travelVector) {
        if (this.isFlying && !this.level().isClientSide) {
            double motionY = this.getDeltaMovement().y;
            
            if (this.getNavigation().isInProgress()) {
                super.travel(travelVector);
                
                // But add ability to move vertically freely
                if (Math.abs(motionY) < 0.1) {
                    // Small upward force to prevent falling
                    this.setDeltaMovement(this.getDeltaMovement().add(0, 0.05, 0));
                }
            } else {
                super.travel(travelVector);
            }
        } else {
            super.travel(travelVector);
        }
    }

    @Override
    public boolean causeFallDamage(float distance, float damageMultiplier, net.minecraft.world.damagesource.DamageSource source) {
        // No fall damage when flying
        if (this.isFlying) {
            return false;
        }
        return super.causeFallDamage(distance, damageMultiplier, source);
    }
}

