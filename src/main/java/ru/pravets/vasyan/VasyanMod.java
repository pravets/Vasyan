package ru.pravets.vasyan;

import ru.pravets.vasyan.command.VasyanCommands;
import ru.pravets.vasyan.command.VasyanNameArgumentInfo;
import ru.pravets.vasyan.command.VasyanNameArgumentType;
import ru.pravets.vasyan.config.VasyanConfig;
import ru.pravets.vasyan.entity.VasyanEntity;
import ru.pravets.vasyan.entity.VasyanManager;
import ru.pravets.vasyan.menu.VasyanMenus;
import ru.pravets.vasyan.network.VasyanNetworking;
import net.minecraft.commands.synchronization.ArgumentTypeInfo;
import net.minecraft.commands.synchronization.ArgumentTypeInfos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityAttributeCreationEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;
import org.slf4j.Logger;

@Mod(VasyanMod.MODID)
public class VasyanMod {
    public static final String MODID = "vasyan";
    public static final Logger LOGGER = org.slf4j.LoggerFactory.getLogger("VasyanMod");

    public static final DeferredRegister<EntityType<?>> ENTITIES = 
        DeferredRegister.create(ForgeRegistries.ENTITY_TYPES, MODID);

    public static final RegistryObject<EntityType<VasyanEntity>> VASYAN_ENTITY = ENTITIES.register("vasyan",
        () -> EntityType.Builder.of(VasyanEntity::new, MobCategory.CREATURE)
            .sized(0.6F, 1.8F)
            .clientTrackingRange(10)
            .build("vasyan"));

    public static final DeferredRegister<ArgumentTypeInfo<?, ?>> COMMAND_ARGUMENT_TYPES =
        DeferredRegister.create(ForgeRegistries.COMMAND_ARGUMENT_TYPES, MODID);

    static {
        COMMAND_ARGUMENT_TYPES.register("vasyan_name", () -> VasyanNameArgumentInfo.INSTANCE);
    }

    private static VasyanManager vasyanManager;
    /** Current dedicated/integrated server, kept for cross-cutting notifications. */
    private static volatile net.minecraft.server.MinecraftServer currentServer;

    public VasyanMod() {
        IEventBus modEventBus = FMLJavaModLoadingContext.get().getModEventBus();

        VasyanNetworking.register();

        ENTITIES.register(modEventBus);
        COMMAND_ARGUMENT_TYPES.register(modEventBus);
        VasyanMenus.MENUS.register(modEventBus);

        // Quarantine a syntactically broken config BEFORE Forge tries to load
        // it (a broken file would otherwise crash the game during mod loading).
        VasyanConfig.quarantineUnparseableFile();

        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, VasyanConfig.SPEC);

        modEventBus.addListener(this::commonSetup);
        modEventBus.addListener(this::entityAttributes);

        MinecraftForge.EVENT_BUS.register(this);
        
        if (net.minecraftforge.fml.loading.FMLEnvironment.dist.isClient()) {
            MinecraftForge.EVENT_BUS.register(ru.pravets.vasyan.client.VasyanGUI.class);        }
        
        vasyanManager = new VasyanManager();
    }

    private void commonSetup(final FMLCommonSetupEvent event) {
        // Register the ArgumentTypeInfo<->argument class mapping so Minecraft
        // can serialize the command tree to clients (ArgumentTypeInfos.byClass).
        // This must happen on the main thread after registries are frozen.
        event.enqueueWork(() ->
            ArgumentTypeInfos.registerByClass(VasyanNameArgumentType.class, VasyanNameArgumentInfo.INSTANCE));
    }

    private void entityAttributes(EntityAttributeCreationEvent event) {
        event.put(VASYAN_ENTITY.get(), VasyanEntity.createAttributes().build());
    }

    @SubscribeEvent
    public void onCommandRegister(RegisterCommandsEvent event) {        VasyanCommands.register(event.getDispatcher());    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase == TickEvent.Phase.END && vasyanManager != null && event.getServer() != null) {
            currentServer = event.getServer();
            for (ServerLevel level : event.getServer().getAllLevels()) {
                vasyanManager.tick(level);
            }
        }
    }

    public static VasyanManager getVasyanManager() {
        return vasyanManager;
    }

    /**
     * The running server instance, or {@code null} when no server is up.
     * Used for cross-cutting notifications (e.g. LLM provider failover chat
     * messages) that originate outside any specific entity/level context.
     */
    public static net.minecraft.server.MinecraftServer getCurrentServer() {
        return currentServer;
    }
}

