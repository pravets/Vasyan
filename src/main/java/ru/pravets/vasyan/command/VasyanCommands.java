package ru.pravets.vasyan.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import ru.pravets.vasyan.VasyanMod;
import ru.pravets.vasyan.action.ActionExecutor;
import ru.pravets.vasyan.chat.ChatCommandParser;
import ru.pravets.vasyan.config.VasyanConfig;
import ru.pravets.vasyan.debug.AgentDebugBuffer;
import ru.pravets.vasyan.debug.VasyanDumpWriter;
import ru.pravets.vasyan.debug.VasyanEnvironmentScanner;
import ru.pravets.vasyan.entity.VasyanEntity;
import ru.pravets.vasyan.entity.VasyanInventory;
import ru.pravets.vasyan.entity.VasyanManager;
import ru.pravets.vasyan.llm.LLMProviders;
import ru.pravets.vasyan.llm.TaskPlanner;
import ru.pravets.vasyan.llm.async.OpenAICompatibleClient;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

public class VasyanCommands {

    /**
     * Shared single-thread executor for async health checks: bounds concurrent
     * check tasks no matter how often the command is spammed, unlike a raw
     * {@code new Thread} per invocation.
     */
    private static final java.util.concurrent.ExecutorService HEALTH_CHECK_EXECUTOR =
        java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "vasyan-health-check");
            t.setDaemon(true);
            return t;
        });

    
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("vasyan")
            .then(Commands.literal("spawn")
                .then(Commands.argument("name", VasyanNameArgumentType.vasyanName())
                    .executes(VasyanCommands::spawnVasyan)))
            .then(Commands.literal("remove")
                .then(Commands.argument("name", VasyanNameArgumentType.vasyanName())
                    .executes(VasyanCommands::removeVasyan)))
            .then(Commands.literal("list")
                .executes(VasyanCommands::listVasyans))
            .then(Commands.literal("stop")
                .then(Commands.argument("name", VasyanNameArgumentType.vasyanName())
                    .executes(VasyanCommands::stopVasyan)))
            .then(Commands.literal("tell")
                .then(Commands.argument("name", VasyanNameArgumentType.vasyanName())
                    .then(Commands.argument("command", StringArgumentType.greedyString())
                        .executes(VasyanCommands::tellVasyan))))
            .then(Commands.literal("providers")
                .requires(source -> source.hasPermission(2))
                .executes(VasyanCommands::listProviders))
            .then(Commands.literal("debug")
                .executes(VasyanCommands::debugVasyan))
            .then(Commands.literal("inventory")
                .then(Commands.argument("name", VasyanNameArgumentType.vasyanName())
                    .executes(VasyanCommands::showInventory)))
            .then(Commands.literal("dump")
                .requires(source -> source.hasPermission(2))
                .then(Commands.argument("name", VasyanNameArgumentType.vasyanName())
                    .executes(ctx -> dumpVasyan(ctx, false))
                    .then(Commands.literal("with-prompt")
                        .executes(ctx -> dumpVasyan(ctx, true)))))
            .then(Commands.literal("look")
                .then(Commands.argument("name", VasyanNameArgumentType.vasyanName())
                    .executes(VasyanCommands::lookVasyan)))
            .then(Commands.literal("tp")
                .then(Commands.argument("name", VasyanNameArgumentType.vasyanName())
                    .executes(VasyanCommands::tpVasyan)))
        );
    }

    /**
     * /vasyan tp <name|all> - instantly teleports the named Vasyan (or all
     * Vasyans) to a safe spot near the commanding player.
     */
    private static int tpVasyan(CommandContext<CommandSourceStack> context) {
        String name = VasyanNameArgumentType.getName(context, "name");
        CommandSourceStack source = context.getSource();
        if (!(source.getEntity() instanceof ServerPlayer player)) {
            source.sendFailure(Component.literal("§cThis command must be run by a player"));
            return 0;
        }

        VasyanManager manager = VasyanMod.getVasyanManager();
        AgentDebugBuffer.log(name, "COMMAND", "tp to " + player.getName().getString());

        if ("all".equalsIgnoreCase(name)) {
            List<String> names = manager.getVasyanNames();
            if (names.isEmpty()) {
                source.sendFailure(Component.literal("§cNo Vasyans spawned. Use /vasyan spawn <name>"));
                return 0;
            }
            int teleported = 0;
            int wrongDimension = 0;
            int noSpot = 0;
            for (String vasyanName : names) {
                VasyanEntity vasyan = manager.getVasyan(vasyanName);
                if (vasyan == null) {
                    continue;
                }
                if (vasyan.level().dimension() != player.level().dimension()) {
                    wrongDimension++;
                } else if (vasyan.teleportToPlayer(player)) {
                    teleported++;
                } else {
                    noSpot++;
                }
            }
            if (teleported == 0) {
                String failure = "§cNo Vasyan teleported"
                    + (wrongDimension > 0 ? " (" + wrongDimension + " in another dimension" : "")
                    + (wrongDimension > 0 && noSpot > 0 ? ", " : "")
                    + (noSpot > 0 ? noSpot + " no safe spot" : "")
                    + (wrongDimension > 0 || noSpot > 0 ? ")" : "");
                source.sendFailure(Component.literal(failure));
                return 0;
            }
            String result = "§aTeleported " + teleported + "/" + names.size() + " Vasyan(s) to you";
            source.sendSuccess(() -> Component.literal(result), false);
            return 1;
        }

        VasyanEntity vasyan = manager.getVasyan(name);
        if (vasyan == null) {
            source.sendFailure(Component.literal("§cVasyan not found: " + name));
            return 0;
        }
        if (vasyan.level().dimension() != player.level().dimension()) {
            source.sendFailure(Component.literal("§c" + name + " is in another dimension"));
            return 0;
        }
        if (!vasyan.teleportToPlayer(player)) {
            source.sendFailure(Component.literal("§cNo safe spot near you for " + name));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("§a" + name + " teleported to you"), false);
        return 1;
    }

    private static int showInventory(CommandContext<CommandSourceStack> context) {
        String name = VasyanNameArgumentType.getName(context, "name");
        CommandSourceStack source = context.getSource();

        VasyanEntity vasyan = VasyanMod.getVasyanManager().getVasyan(name);
        if (vasyan == null) {
            source.sendFailure(Component.literal("Vasyan not found: " + name));
            return 0;
        }

        VasyanInventory inventory = vasyan.getInventory();
        source.sendSuccess(() -> Component.literal(
            "§e" + name + "'s inventory§7 (" + inventory.getStacksCount() + "/" + inventory.getMaxSize()
                + " stacks, " + inventory.getTotalCount() + " items): " + inventory.toDisplayString()),
            false);
        return 1;
    }

    private static int debugVasyan(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        VasyanManager manager = VasyanMod.getVasyanManager();

        String provider = VasyanConfig.AI_PROVIDER.get().toLowerCase();
        String base = LLMProviders.resolveBaseUrl(provider, VasyanConfig.LLM_BASE_URL.get());
        String model = VasyanConfig.LLM_MODEL.get();
        if (model == null || model.isEmpty()) {
            model = LLMProviders.resolveModel(provider, "");
        }
        String key = VasyanConfig.LLM_API_KEY.get();
        boolean keyPresent = key != null && !key.isEmpty();
        boolean jsonMode = VasyanConfig.LLM_JSON_MODE.get();
        String llmLine = "§eLLM: §f" + provider + "§7 (" + base + ") model=" + model
            + " key=" + (keyPresent ? "§aset" : "§cmissing")
            + " jsonMode=" + jsonMode;

        source.sendSuccess(() -> Component.literal(llmLine), false);

        // Provider health (async, 3s timeout)
        String providerId = provider;
        String baseUrl = base;
        String apiKey = VasyanConfig.LLM_API_KEY.get();
        String modelOverride = VasyanConfig.LLM_MODEL.get();
        HEALTH_CHECK_EXECUTOR.execute(() -> {
            try {
                OpenAICompatibleClient client = OpenAICompatibleClient.forProvider(
                    providerId, baseUrl, apiKey, modelOverride,
                    VasyanConfig.MAX_TOKENS.get(), VasyanConfig.TEMPERATURE.get(),
                    VasyanConfig.LLM_JSON_MODE.get(), VasyanConfig.LLM_TIMEOUT_SECONDS.get());
                source.sendSuccess(() -> Component.literal(
                    "§eHealth: §f" + (client.checkHealth() ? "§aONLINE" : "§cUNREACHABLE")
                        + "§7 (GET " + baseUrl + "/models)"), false);
            } catch (Exception e) {
                source.sendSuccess(() -> Component.literal("§cHealth check error: " + e.getMessage()), false);
            }
        });

        // Per-Vasyan state
        var vasyans = manager.getAllVasyans();
        if (vasyans.isEmpty()) {
            source.sendSuccess(() -> Component.literal("§7No Vasyans spawned. Use /vasyan spawn <name>"), false);
        } else {
            for (VasyanEntity vasyan : vasyans) {
                source.sendSuccess(() -> Component.literal(
                    "§e" + vasyan.getVasyanName() + "§7: " + vasyan.getActionExecutor().getStateSummary()), false);
            }
        }

        // Recent debug events
        List<String> events = AgentDebugBuffer.getEvents(20);
        source.sendSuccess(() -> Component.literal("§eRecent events (" + events.size() + "):"), false);
        for (String event : events) {
            source.sendSuccess(() -> Component.literal("§7 " + event), false);
        }

        return 1;
    }

    private static int listProviders(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();

        String configuredProvider = VasyanConfig.AI_PROVIDER.get().toLowerCase();
        ru.pravets.vasyan.llm.resilience.ProviderChainClient chain =
            TaskPlanner.getInstance().getProviderChain();
        String activeProvider = chain != null
            ? chain.getActiveProviderId()
            : configuredProvider;
        boolean failedOver = chain != null && !activeProvider.equals(
            chain.getMembers().get(0).getProviderId());

        // Effective settings of the ACTIVE member. In chain mode the same
        // resolution applies as in TaskPlanner (member section -> shared llm.*
        // -> preset). In SINGLE-PROVIDER mode TaskPlanner uses ONLY shared
        // llm.* values, so member sections must NOT be consulted here -
        // otherwise the status would show a different endpoint than requests
        // actually use.
        var memberSection = chain != null ? TaskPlanner.memberSection(activeProvider) : null;
        String sharedBase = LLMProviders.resolveBaseUrl(activeProvider, VasyanConfig.LLM_BASE_URL.get());
        String effectiveBase = firstNonBlank(
            memberSection != null ? memberSection.baseUrl().get() : null, sharedBase);
        String sharedModelRaw = VasyanConfig.LLM_MODEL.get();
        String sharedModel = sharedModelRaw != null && !sharedModelRaw.isEmpty()
            ? sharedModelRaw : LLMProviders.resolveModel(activeProvider, "");
        String effectiveModel = firstNonBlank(
            memberSection != null ? memberSection.model().get() : null, sharedModel);
        String key = VasyanConfig.LLM_API_KEY.get();
        String effectiveKey = firstNonBlank(
            memberSection != null ? memberSection.apiKey().get() : null, key);
        boolean keyPresent = effectiveKey != null && !effectiveKey.isEmpty();

        source.sendSuccess(() -> Component.literal(
            "§eActive provider: §f" + activeProvider
                + (failedOver ? "§7 (failed over from §f" + chain.getMembers().get(0).getProviderId() + "§7)" : "")
                + " §7(" + effectiveBase + ")"), false);

        // Configured chain with per-member circuit breaker state.
        if (chain != null) {
            StringBuilder chainLine = new StringBuilder("§eChain: ");
            for (int i = 0; i < chain.size(); i++) {
                var member = chain.getMembers().get(i);
                if (i > 0) {
                    chainLine.append("§7 → ");
                }
                String cb = chain.cbStateOf(member).name();
                boolean isActive = i == chain.getActiveIndex();
                long cooldownMs = chain.getMemberCooldownRemainingMillis(i);
                chainLine.append(isActive ? "§a✔ " : "§f")
                    .append(member.getProviderId())
                    .append("§7[CB:").append(cb);
                if (cooldownMs > 0) {
                    chainLine.append(", cd ").append(cooldownMs / 1000).append('s');
                }
                chainLine.append(']');
            }
            source.sendSuccess(() -> Component.literal(chainLine.toString()), false);
            source.sendSuccess(() -> Component.literal(
                "§7Failback to '" + chain.getMembers().get(0).getProviderId()
                    + "' is retried every "
                    + VasyanConfig.FAILOVER_RETRY_SECONDS.get() + "s"), false);
        } else {
            source.sendSuccess(() -> Component.literal(
                "§7No providerChain configured - single-provider mode"), false);
        }

        // Model/key line for the ACTIVE provider.
        String modelLine;
        if (LLMProviders.CUSTOM.equals(activeProvider)) {
            // Custom endpoint may or may not need a key - depends on the server.
            modelLine = "§eModel: §f" + effectiveModel + "§7 | key: §7optional";
        } else if (!LLMProviders.requiresKey(activeProvider)) {
            modelLine = "§eModel: §f" + effectiveModel + "§7 | key: §7not required";
        } else {
            modelLine = "§eModel: §f" + effectiveModel + "§7 | key: "
                + (keyPresent ? "§aset" : "§cmissing");
        }
        source.sendSuccess(() -> Component.literal(modelLine), false);

        // Live health check of EVERY member of the chain, not just the head
        // (GET /models, 3s timeout each; an unreachable /models such as some
        // ollama setups must not fail the whole chain's health view).
        List<String> memberIds = chain != null
            ? chain.getMembers().stream().map(m -> m.getProviderId()).toList()
            : List.of(configuredProvider);
        HEALTH_CHECK_EXECUTOR.execute(() -> {
            for (String memberId : memberIds) {
                try {
                    // Per-member overrides first ([llm.members.<id>]), then the
                    // shared llm.* values - same resolution as TaskPlanner uses.
                    // In single-provider mode there is no chain client built from
                    // member sections, so skip them to match real request routing.
                    var section = chain != null ? TaskPlanner.memberSection(memberId) : null;
                    String memberBase = LLMProviders.resolveBaseUrl(memberId,
                        firstNonBlank(section != null ? section.baseUrl().get() : null,
                            VasyanConfig.LLM_BASE_URL.get()));
                    String memberKey = firstNonBlank(section != null ? section.apiKey().get() : null, key);
                    String memberModel = firstNonBlank(section != null ? section.model().get() : null,
                        VasyanConfig.LLM_MODEL.get());
                    OpenAICompatibleClient client = OpenAICompatibleClient.forProvider(
                        memberId, memberBase, memberKey, memberModel,
                        VasyanConfig.MAX_TOKENS.get(), VasyanConfig.TEMPERATURE.get(),
                        VasyanConfig.LLM_JSON_MODE.get(), VasyanConfig.LLM_TIMEOUT_SECONDS.get());
                    boolean healthy = client.checkHealth();
                    source.sendSuccess(() -> Component.literal(
                        "§eHealth [" + memberId + "]: "
                            + (healthy ? "§aONLINE" : "§cUNREACHABLE")
                            + " §7(GET " + memberBase + "/models)"), false);
                } catch (Exception e) {
                    String message = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                    source.sendSuccess(() -> Component.literal(
                        "§eHealth [" + memberId + "]: §cERROR §7(" + message + ")"), false);
                }
            }
        });

        // List all known providers
        source.sendSuccess(() -> Component.literal("§eAvailable providers:"), false);
        source.sendSuccess(() -> Component.literal(
            "§7 openai, groq, gemini, ollama, lmstudio, opencode-go, custom"), false);
        source.sendSuccess(() -> Component.literal(
            "§7 Set llm.provider and llm.providerChain in config/vasyan-common.toml to switch"), false);

        return 1;
    }

    /** Returns {@code a} when non-blank, otherwise {@code b} (which may be null). */
    private static String firstNonBlank(String a, String b) {
        return a != null && !a.isBlank() ? a : b;
    }

    private static int spawnVasyan(CommandContext<CommandSourceStack> context) {
        String name = VasyanNameArgumentType.getName(context, "name");
        CommandSourceStack source = context.getSource();
        
        ServerLevel serverLevel = source.getLevel();
        if (serverLevel == null) {
            source.sendFailure(Component.literal("Command must be run on server"));
            return 0;
        }

        VasyanManager manager = VasyanMod.getVasyanManager();
        
        Vec3 sourcePos = source.getPosition();
        if (source.getEntity() != null) {
            Vec3 lookVec = source.getEntity().getLookAngle();
            sourcePos = sourcePos.add(lookVec.x * 3, 0, lookVec.z * 3);
        } else {
            sourcePos = sourcePos.add(3, 0, 0);
        }
        Vec3 spawnPos = sourcePos;
        
        VasyanEntity vasyan = manager.spawnVasyan(serverLevel, spawnPos, name);
        if (vasyan != null) {
            source.sendSuccess(() -> Component.literal("Spawned Vasyan: " + name), true);
            return 1;
        } else {
            source.sendFailure(Component.literal("Failed to spawn Vasyan. Name may already exist or max limit reached."));
            return 0;
        }
    }

    private static int removeVasyan(CommandContext<CommandSourceStack> context) {
        String name = VasyanNameArgumentType.getName(context, "name");
        CommandSourceStack source = context.getSource();
        
        VasyanManager manager = VasyanMod.getVasyanManager();
        if (manager.removeVasyan(name, source.getServer())) {
            source.sendSuccess(() -> Component.literal("Removed Vasyan: " + name), true);
            return 1;
        } else {
            source.sendFailure(Component.literal("Vasyan not found: " + name));
            return 0;
        }
    }

    private static int listVasyans(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        VasyanManager manager = VasyanMod.getVasyanManager();
        
        var names = manager.getVasyanNames();
        if (names.isEmpty()) {
            source.sendSuccess(() -> Component.literal("No active Vasyans"), false);
        } else {
            source.sendSuccess(() -> Component.literal("Active Vasyans (" + names.size() + "): " + String.join(", ", names)), false);
        }
        return 1;
    }

    private static int stopVasyan(CommandContext<CommandSourceStack> context) {
        String name = VasyanNameArgumentType.getName(context, "name");
        CommandSourceStack source = context.getSource();
        
        VasyanManager manager = VasyanMod.getVasyanManager();
        VasyanEntity vasyan = manager.getVasyan(name);
        
        if (vasyan != null) {
            vasyan.getActionExecutor().stopCurrentAction();
            vasyan.getActionExecutor().setStaying(true);
            vasyan.getMemory().clearTaskQueue();
            source.sendSuccess(() -> Component.literal("Stopped Vasyan: " + name), true);
            return 1;
        } else {
            source.sendFailure(Component.literal("Vasyan not found: " + name));
            return 0;
        }
    }

    private static int tellVasyan(CommandContext<CommandSourceStack> context) {
        String name = VasyanNameArgumentType.getName(context, "name");
        String command = StringArgumentType.getString(context, "command");
        CommandSourceStack source = context.getSource();

        // Single dispatch path (name matching, "all", nearest, stay-trigger)
        // shared with voice commands - see VasyanCommandDispatcher.
        return VasyanCommandDispatcher.dispatch(source, name + " " + command);
    }

    private static int dumpVasyan(CommandContext<CommandSourceStack> context, boolean includePrompt) {
        String name = VasyanNameArgumentType.getName(context, "name");
        CommandSourceStack source = context.getSource();

        VasyanEntity vasyan = VasyanMod.getVasyanManager().getVasyan(name);
        if (vasyan == null) {
            source.sendFailure(Component.literal("§cVasyan not found: " + name));
            return 0;
        }

        try {
            Path file = VasyanDumpWriter.write(vasyan, includePrompt);
            source.sendSuccess(() -> Component.literal(
                "§aDumped " + name + " to §f" + file), false);
            return 1;
        } catch (IOException e) {
            VasyanMod.LOGGER.error("Failed to write dump for {}", name, e);
            source.sendFailure(Component.literal("§cFailed to write dump: " + e.getMessage()));
            return 0;
        }
    }

    private static int lookVasyan(CommandContext<CommandSourceStack> context) {
        String name = VasyanNameArgumentType.getName(context, "name");
        CommandSourceStack source = context.getSource();

        VasyanEntity vasyan = VasyanMod.getVasyanManager().getVasyan(name);
        if (vasyan == null) {
            source.sendFailure(Component.literal("§cVasyan not found: " + name));
            return 0;
        }

        VasyanEnvironmentScanner.SurfaceScan scan = VasyanEnvironmentScanner.scan(vasyan);
        VasyanCommandDispatcher.triggerLookDebug(vasyan, scan);
        String description = VasyanEnvironmentScanner.describe(scan);
        vasyan.sendChatMessage(description);
        source.sendSuccess(() -> Component.literal("§7" + name + " looks around"), false);
        return 1;
    }
}

