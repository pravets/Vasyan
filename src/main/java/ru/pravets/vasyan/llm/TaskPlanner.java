package ru.pravets.vasyan.llm;

import ru.pravets.vasyan.VasyanMod;
import ru.pravets.vasyan.action.Task;
import ru.pravets.vasyan.chat.ChatCommandParser;
import ru.pravets.vasyan.config.VasyanConfig;
import ru.pravets.vasyan.debug.AgentDebugBuffer;
import ru.pravets.vasyan.entity.VasyanEntity;
import ru.pravets.vasyan.llm.async.AsyncLLMClient;
import ru.pravets.vasyan.llm.async.LLMCache;
import ru.pravets.vasyan.llm.async.LLMResponse;
import ru.pravets.vasyan.llm.async.OpenAICompatibleClient;
import ru.pravets.vasyan.llm.resilience.LLMFallbackHandler;
import ru.pravets.vasyan.llm.resilience.ProviderChainClient;
import ru.pravets.vasyan.llm.resilience.ResilientLLMClient;
import ru.pravets.vasyan.memory.WorldKnowledge;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public class TaskPlanner {

    private final AsyncLLMClient llmClient;
    private final LLMCache llmCache;
    private final OpenAICompatibleClient baseClient;
    /** Failover chain; non-null only when llm.providerChain is configured. */
    private final ProviderChainClient providerChain;
    /**
     * Most recently constructed chain, for observability from commands that
     * have no planner instance (/vasyan providers). All planners share the same
     * config, so the latest one reflects the current routing setup.
     */
    private static volatile ProviderChainClient lastConstructedChain;
    private volatile PlanRecord lastPlanRecord;

    /**
     * The active failover chain of the most recently initialized planner, or
     * {@code null} when no planner has been created yet or no chain is
     * configured (single-provider mode).
     */
    public static ProviderChainClient getActiveChain() {
        return lastConstructedChain;
    }

    public PlanRecord getLastPlanRecord() {
        return lastPlanRecord;
    }

    public TaskPlanner() {
        String provider = VasyanConfig.AI_PROVIDER.get().toLowerCase();
        String baseUrl = VasyanConfig.LLM_BASE_URL.get();
        String apiKey = VasyanConfig.LLM_API_KEY.get();
        String model = VasyanConfig.LLM_MODEL.get();
        int maxTokens = VasyanConfig.MAX_TOKENS.get();
        double temperature = VasyanConfig.TEMPERATURE.get();
        boolean jsonMode = VasyanConfig.LLM_JSON_MODE.get();
        int timeoutSeconds = VasyanConfig.LLM_TIMEOUT_SECONDS.get();

        if (!LLMProviders.isValid(provider)) {
            VasyanMod.LOGGER.warn("Unknown LLM provider '{}', falling back to 'ollama'. Valid: {}",
                provider, String.join(", ", List.of(
                    LLMProviders.OPENAI, LLMProviders.GROQ, LLMProviders.GEMINI,
                    LLMProviders.OLLAMA, LLMProviders.LMSTUDIO, LLMProviders.OPENCODE_GO,
                    LLMProviders.DEEPSEEK, LLMProviders.OPENROUTER, LLMProviders.NEURALDEEP,
                    LLMProviders.ROUTERAI,
                    LLMProviders.CLOUD_RU_FM,
                    LLMProviders.SELECTEL_ROUTER, LLMProviders.TOKENRA,
                    LLMProviders.CUSTOM)));
            provider = LLMProviders.OLLAMA;
        }

        this.baseClient = OpenAICompatibleClient.forProvider(
            provider, baseUrl, apiKey, model, maxTokens, temperature, jsonMode, timeoutSeconds);

        if (LLMProviders.requiresKey(provider) && !baseClient.hasApiKey()) {
            VasyanMod.LOGGER.warn("Provider '{}' requires an API key but llm.apiKey is empty. " +
                "LLM calls will fail; set the key in config/vasyan-common.toml.", provider);
        }

        this.llmCache = new LLMCache();
        ResilientLLMClient primaryResilient =
            new ResilientLLMClient(baseClient, llmCache, new LLMFallbackHandler());

        // Optional failover chain: llm.providerChain = ["opencode-go", "ollama", ...].
        // Empty chain -> exactly the old single-provider behavior (backward compat).
        this.providerChain = buildProviderChain(primaryResilient);

        this.llmClient = (providerChain != null) ? providerChain : primaryResilient;
        lastConstructedChain = this.providerChain;

        VasyanMod.LOGGER.info("TaskPlanner initialized: provider={}, baseUrl={}, model={}, jsonMode={}, chain={}",
            provider, baseClient.getBaseUrl(), baseClient.getModel(), jsonMode,
            providerChain != null ? describeChain(providerChain) : "off");
    }

    /**
     * Builds the failover chain from {@code llm.providerChain}: one
     * resilience-wrapped client per entry, head first. The FIRST chain member
     * reuses the already-built primary client when it matches
     * {@code llm.provider}, so no duplicate client is constructed.
     *
     * <p>Validation per plan T1: unknown ids are warned about and skipped,
     * duplicates removed, and an effectively-empty chain yields {@code null}
     * (= single-provider mode via {@code llm.provider}).
     *
     * <p>Resolution order for each member's settings (most specific wins):
     * <ol>
     *   <li>{@code llm.members.<id>} section ({@code apiKey}/{@code model}/{@code baseUrl}),</li>
     *   <li>shared {@code llm.apiKey} / {@code llm.model} / {@code llm.baseUrl},</li>
     *   <li>the provider preset default from {@link LLMProviders}.</li>
     * </ol>
     *
     * <p>This makes chains of DIFFERENT providers with independent keys and
     * models possible, including several distinct custom endpoints.</p>
     */
    private ProviderChainClient buildProviderChain(ResilientLLMClient primaryResilient) {
        List<? extends String> rawChain = VasyanConfig.PROVIDER_CHAIN.get();
        if (rawChain == null || rawChain.isEmpty()) {
            return null;
        }

        String primaryProvider = VasyanConfig.AI_PROVIDER.get().toLowerCase();
        List<AsyncLLMClient> members = new ArrayList<>();
        boolean first = true;
        for (String rawId : rawChain) {
            String id = rawId == null ? "" : rawId.trim().toLowerCase();
            if (id.isEmpty()) {
                continue;
            }
            boolean presetId = LLMProviders.isValid(id);
            var fileSettings = LlmMembersFile.get(id);
            if (!presetId && (fileSettings == null || !fileSettings.hasAny())) {
                // Not a preset AND no settings in the mod-owned members file:
                // nothing to route to. Presets need no entry at all; arbitrary
                // ids are allowed ONLY through vasyan-llm-members.toml with at
                // least a baseUrl.
                VasyanMod.LOGGER.warn(
                    "providerChain: unknown provider '{}' - skipping. Either use a preset id " +
                    "(openai, groq, gemini, deepseek, openrouter, neuraldeep, ollama, lmstudio, " +
                    "opencode-go, routerai, cloud-ru-fm, selectel-router, tokenra, custom) or add " +
                    "a [{}] section with baseUrl to config/vasyan-llm-members.toml. NOTE: Forge " +
                    "REMOVES [llm.members.<name>] sections for non-preset names from " +
                    "vasyan-common.toml on startup - put custom endpoints in the members file.",
                    id, id);
                continue;
            }
            if (containsId(members, id)) {
                VasyanMod.LOGGER.warn("providerChain: duplicate provider '{}' - skipping", id);
                continue;
            }
            AsyncLLMClient member;
            // Per-member overrides of the head: reuse primaryResilient ONLY if
            // none are configured, otherwise build a dedicated client - the
            // primary was created from shared llm.* fields and would ignore
            // [llm.members.<id>] settings.
            boolean headWithNoOverrides = first && id.equals(primaryProvider)
                && !hasMemberOverrides(id);
            if (headWithNoOverrides) {
                member = primaryResilient;
            } else {
                // Resolution: mod-owned vasyan-llm-members.toml (never wiped by
                // Forge's config corrector, any member name allowed) ->
                // [llm.members.<id>] Forge section (preset ids only) -> shared llm.* fields.
                var section = memberSection(id);
                String memberKey = firstNonEmpty(
                    fileSettings != null ? fileSettings.apiKey() : null,
                    firstNonEmpty(section.apiKey().get(), VasyanConfig.LLM_API_KEY.get()));
                String memberModel = firstNonEmpty(
                    fileSettings != null ? fileSettings.model() : null,
                    firstNonEmpty(section.model().get(), VasyanConfig.LLM_MODEL.get()));
                String memberBaseUrl = firstNonEmpty(
                    fileSettings != null ? fileSettings.baseUrl() : null,
                    firstNonEmpty(section.baseUrl().get(), VasyanConfig.LLM_BASE_URL.get()));

                OpenAICompatibleClient base = OpenAICompatibleClient.forProvider(
                    id,
                    memberBaseUrl,
                    memberKey,
                    memberModel,
                    VasyanConfig.MAX_TOKENS.get(),
                    VasyanConfig.TEMPERATURE.get(),
                    VasyanConfig.LLM_JSON_MODE.get(),
                    VasyanConfig.LLM_TIMEOUT_SECONDS.get());
                if (!presetId) {
                    VasyanMod.LOGGER.info("providerChain: dynamic provider '{}' from vasyan-llm-members.toml", id);
                }
                if (LLMProviders.isValid(id) && LLMProviders.requiresKey(id) && !base.hasApiKey()) {
                    VasyanMod.LOGGER.warn("providerChain: provider '{}' requires an API key " +
                        "but neither llm.members.{}.apiKey nor llm.apiKey is set - " +
                        "its calls will fail until a key is configured.", id, id);
                }
                member = new ResilientLLMClient(base, llmCache, new LLMFallbackHandler());
            }
            members.add(member);
            first = false;
        }

        if (members.isEmpty()) {
            VasyanMod.LOGGER.warn("providerChain is set but has no valid entries - using single provider '{}'",
                primaryProvider);
            return null;
        }

        int retrySeconds = VasyanConfig.FAILOVER_RETRY_SECONDS.get();
        return new ProviderChainClient(members, TaskPlanner::notifyProviderSwitch, retrySeconds);
    }

    private static boolean containsId(List<AsyncLLMClient> members, String id) {
        return members.stream().anyMatch(m -> id.equals(m.getProviderId()));
    }

    /**
     * True when {@code llm.members.<id>} defines at least one non-blank value.
     * Used to decide whether the primary single-provider client can be reused
     * as the chain head (it carries only shared llm.* settings).
     */
    private static boolean hasMemberOverrides(String id) {
        var fileSettings = LlmMembersFile.get(id);
        if (fileSettings != null && fileSettings.hasAny()) {
            return true;
        }
        VasyanConfig.MemberSection s = memberSection(id);
        return isSet(s.apiKey().get()) || isSet(s.model().get()) || isSet(s.baseUrl().get());
    }

    private static boolean isSet(String v) {
        return v != null && !v.isBlank();
    }

    /**
     * Per-provider override section for the given chain member id. Public:
     * used by /vasyan providers health checks. Ids that are not presets map
     * to {@code MEMBER_CUSTOM}; for such ids real values are expected in
     * vasyan-llm-members.toml instead (see {@link LlmMembersFile#get}).
     */
    public static VasyanConfig.MemberSection memberSection(String id) {
        return switch (id) {
            case LLMProviders.OPENAI -> VasyanConfig.MEMBER_OPENAI;
            case LLMProviders.GROQ -> VasyanConfig.MEMBER_GROQ;
            case LLMProviders.GEMINI -> VasyanConfig.MEMBER_GEMINI;
            case LLMProviders.OLLAMA -> VasyanConfig.MEMBER_OLLAMA;
            case LLMProviders.LMSTUDIO -> VasyanConfig.MEMBER_LMSTUDIO;
            case LLMProviders.OPENCODE_GO -> VasyanConfig.MEMBER_OPENCODE_GO;
            case LLMProviders.DEEPSEEK -> VasyanConfig.MEMBER_DEEPSEEK;
            case LLMProviders.OPENROUTER -> VasyanConfig.MEMBER_OPENROUTER;
            case LLMProviders.NEURALDEEP -> VasyanConfig.MEMBER_NEURALDEEP;
            case LLMProviders.ROUTERAI -> VasyanConfig.MEMBER_ROUTERAI;
            case LLMProviders.CLOUD_RU_FM -> VasyanConfig.MEMBER_CLOUD_RU_FM;
            case LLMProviders.SELECTEL_ROUTER -> VasyanConfig.MEMBER_SELECTEL_ROUTER;
            case LLMProviders.TOKENRA -> VasyanConfig.MEMBER_TOKENRA;
            default -> VasyanConfig.MEMBER_CUSTOM;
        };
    }

    /** First non-blank string, or the fallback. */
    private static String firstNonEmpty(String value, String fallback) {
        return value != null && !value.isBlank() ? value : fallback;
    }

    private static String describeChain(ProviderChainClient chain) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < chain.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(chain.getMembers().get(i).getProviderId());
        }
        return sb.append(']').toString();
    }

    /**
     * Chat notification fired by the chain when the active provider actually
     * changes (failover or recovery). Broadcast once per switch, to all
     * players, like other Vasyan chat messages.
     */
    private static void notifyProviderSwitch(String message) {
        VasyanMod.LOGGER.info("LLM provider switch: {}", message);
        try {
            net.minecraft.server.MinecraftServer server = VasyanMod.getCurrentServer();
            if (server != null) {
                for (var player : server.getPlayerList().getPlayers()) {
                    player.sendSystemMessage(net.minecraft.network.chat.Component.literal(message));
                }
            }
        } catch (Exception e) {
            // Never let a notification problem affect request routing.
            VasyanMod.LOGGER.debug("Provider-switch chat notification failed: {}", e.getMessage());
        }
    }

    /**
     * Asynchronously plans tasks for Vasyan using the configured LLM provider.
     *
     * <p>Returns immediately with a CompletableFuture; the LLM call runs on a
     * separate thread with resilience patterns (circuit breaker, retry, rate
     * limiting, caching, fallback).</p>
     */
    public CompletableFuture<ResponseParser.ParsedResponse> planTasksAsync(VasyanEntity vasyan, String command) {
        try {
            String systemPrompt = PromptBuilder.buildSystemPrompt();
            WorldKnowledge worldKnowledge = new WorldKnowledge(vasyan);
            String userPrompt = PromptBuilder.buildUserPrompt(vasyan, command, worldKnowledge);

            String provider = getActiveProvider();
            VasyanMod.LOGGER.info("[Async] Requesting AI plan for Vasyan '{}' using {}: {}",
                vasyan.getVasyanName(), provider, command);
            AgentDebugBuffer.log(vasyan.getVasyanName(), "COMMAND", "[" + provider + "] " + command);

            Map<String, Object> params = Map.of(
                "systemPrompt", systemPrompt,
                "model", VasyanConfig.LLM_MODEL.get(),
                "maxTokens", VasyanConfig.MAX_TOKENS.get(),
                "temperature", VasyanConfig.TEMPERATURE.get()
            );

            return llmClient.sendAsync(userPrompt, params)
                .thenApply(response -> {
                    String content = response.getContent();
                    if (content == null || content.isEmpty()) {
                        VasyanMod.LOGGER.error("[Async] Empty response from LLM");
                        AgentDebugBuffer.log(vasyan.getVasyanName(), "LLM", "empty response");
                        return null;
                    }

                    AgentDebugBuffer.log(vasyan.getVasyanName(), "LLM",
                        "model=" + response.getModel() + " cache=" + response.isFromCache()
                            + " content=" + truncate(content, 400));

                    ResponseParser.ParsedResponse parsed = ResponseParser.parseAIResponse(content);
                    if (parsed == null) {
                        VasyanMod.LOGGER.error("[Async] Failed to parse AI response");
                        AgentDebugBuffer.log(vasyan.getVasyanName(), "PARSE", "FAILED to parse: " + truncate(content, 400));
                        return null;
                    }

                    VasyanMod.LOGGER.info("[Async] Plan received: {} ({} tasks, {}ms, {} tokens, cache: {})",
                        parsed.getPlan(),
                        parsed.getTasks().size(),
                        response.getLatencyMs(),
                        response.getTokensUsed(),
                        response.isFromCache());
                    // Tell the player visibly when the LLM was down and a
                    // local fallback plan was used, so a wrong-looking
                    // behavior (e.g. follow instead of gather) is explained.
                    if ("fallback".equals(response.getProviderId())) {
                        String reason = response.getFailureReason();
                        String hint;
                        if (reason != null && reason.contains("таймаут")) {
                            hint = "увеличь llm.timeoutSeconds в конфиге или возьми модель побыстрее";
                        } else if (reason != null && (reason.contains("соединения")
                                || reason.contains("сетевая ошибка") || reason.contains("хост не найден"))) {
                            hint = "проверь, что LLM-сервер запущен и адрес верный (llm.baseUrl)";
                        } else {
                            hint = "проверь логи сервера";
                        }
                        vasyan.sendChatMessage("⚠️ LLM недоступен: "
                            + (reason != null ? reason : "неизвестная ошибка")
                            + " — запасной план: " + parsed.getPlan()
                            + ". Подсказка: " + hint);
                    }
                    AgentDebugBuffer.log(vasyan.getVasyanName(), "PARSE",
                        "ok, " + parsed.getTasks().size() + " tasks, plan=\"" + truncate(parsed.getPlan(), 200)
                            + "\", tasks=" + truncate(describeTasks(parsed.getTasks()), 300));

                    // "Gather until the inventory is full" is deterministic:
                    // mark every gather task with fill=true (the LLM does not
                    // get to decide the quantity for this quantifier).
                    if (ChatCommandParser.isFillCommand(command)) {
                        for (ru.pravets.vasyan.action.Task task : parsed.getTasks()) {
                            if ("gather".equals(task.getAction())) {
                                task.getParameters().put("fill", true);
                            }
                        }
                        VasyanMod.LOGGER.info("[Async] Fill-inventory mode applied to gather tasks");
                    }

                    // "One full stack" (стак) is deterministic too: replace the
                    // LLM's quantity with the resource's real stack size.
                    if (ChatCommandParser.isStackCommand(command)) {
                        for (ru.pravets.vasyan.action.Task task : parsed.getTasks()) {
                            if ("gather".equals(task.getAction())) {
                                String resource = task.getStringParameter("resource");
                                if (resource == null) {
                                    resource = task.getStringParameter("block");
                                }
                                net.minecraft.world.level.block.Block block =
                                    ru.pravets.vasyan.action.actions.ResourceBlocks.parseBlock(resource);
                                int stackSize = ru.pravets.vasyan.action.actions.ResourceBlocks.stackSizeFor(block);
                                task.getParameters().put("quantity", stackSize);
                                VasyanMod.LOGGER.info("[Async] Stack size {} applied to gather task '{}'", stackSize, resource);
                            }
                        }
                    }

                    // "Gather wood/trees" means ANY log type: the user's words
                    // win over whatever single log type the LLM named.
                    // NOTE: no isWoodRequest(resource) check here - the LLM
                    // typically returns a CONCRETE type (oak_log), which
                    // isWoodRequest() correctly rejects; the command itself
                    // is the wood signal.
                    // The LLM often splits "дерево" into one gather task per
                    // log type (oak_log, birch_log, ...); after the stack
                    // override above those become identical duplicates that
                    // would ALL execute (e.g. 2x "gather wood x64"). Collapse
                    // them into a single any-log task instead.
                    if (ChatCommandParser.isWoodCommand(command)) {
                        int before = parsed.getTasks().size();
                        List<Task> collapsed = collapseWoodGatherTasks(parsed.getTasks());
                        parsed.getTasks().clear();
                        parsed.getTasks().addAll(collapsed);
                        int removed = before - collapsed.size();
                        if (removed > 0) {
                            VasyanMod.LOGGER.info("[Async] Wood request: collapsed {} per-type gather tasks into one any-log task",
                                removed + 1);
                        } else {
                            VasyanMod.LOGGER.info("[Async] Wood request normalized to any-log mode");
                        }
                    }

                    // The deterministic overrides above (stack/wood/fill) can
                    // turn distinct LLM tasks into exact duplicates (same
                    // resource, quantity, fill) even outside wood commands.
                    // Drop the copies so the request is never executed twice.
                    {
                        int before = parsed.getTasks().size();
                        List<Task> deduped = dedupeGatherTasks(parsed.getTasks());
                        if (deduped.size() < before) {
                            VasyanMod.LOGGER.info("[Async] Removed {} duplicate gather task(s), final plan: {}",
                                before - deduped.size(), describeTasks(deduped));
                            parsed.getTasks().clear();
                            parsed.getTasks().addAll(deduped);
                        }
                    }

                    lastPlanRecord = new PlanRecord(
                        command,
                        systemPrompt,
                        userPrompt,
                        content,
                        parsed.getReasoning(),
                        parsed.getPlan(),
                        parsed.getTasks(),
                        response.getLatencyMs(),
                        response.getModel(),
                        response.isFromCache()
                    );

                    return parsed;
                })
                .exceptionally(throwable -> {
                    VasyanMod.LOGGER.error("[Async] Error planning tasks: {}", throwable.getMessage());
                    AgentDebugBuffer.log(vasyan.getVasyanName(), "LLM_ERROR",
                        throwable.getClass().getSimpleName() + ": " + truncate(throwable.getMessage(), 300));
                    return null;
                });

        } catch (Exception e) {
            VasyanMod.LOGGER.error("[Async] Error setting up task planning", e);
            return CompletableFuture.completedFuture(null);
        }
    }

    /**
     * Legacy blocking variant. Blocks the calling thread up to the configured
     * LLM timeout. Prefer {@link #planTasksAsync(VasyanEntity, String)}.
     *
     * <p>The planning snapshot is recorded by {@link #planTasksAsync(VasyanEntity, String)}
     * and is available via {@link #getLastPlanRecord()} after this call returns.</p>
     *
     * @deprecated Use planTasksAsync instead.
     */
    @Deprecated
    public ResponseParser.ParsedResponse planTasks(VasyanEntity vasyan, String command) {
        try {
            return planTasksAsync(vasyan, command).get(VasyanConfig.LLM_TIMEOUT_SECONDS.get() + 5, TimeUnit.SECONDS);
        } catch (Exception e) {
            VasyanMod.LOGGER.error("Error planning tasks (sync)", e);
            return null;
        }
    }

    public LLMCache getLLMCache() {
        return llmCache;
    }

    /**
     * The failover chain, or {@code null} when {@code llm.providerChain} is
     * empty (single-provider mode).
     */
    public ProviderChainClient getProviderChain() {
        return providerChain;
    }

    /**
     * Checks if the configured provider (or at least one chain member) can
     * currently accept requests.
     */
    public boolean isProviderHealthy() {
        return llmClient.isHealthy();
    }

    /**
     * Live health check of the ACTUAL active provider - the chain head in
     * single-provider mode, possibly a failover member otherwise.
     *
     * <p>For HTTP members this performs a real GET {@code /models} request.
     * For non-HTTP members (e.g. test fakes or future non-OpenAI transports)
     * only the cheap circuit-breaker liveness signal is available: the result
     * reflects the breaker state, NOT a live network probe.</p>
     */
    public boolean pingProvider() {
        if (providerChain != null) {
            AsyncLLMClient active = providerChain.getMembers().get(providerChain.getActiveIndex());
            OpenAICompatibleClient delegate = httpDelegateOf(active);
            if (delegate != null) {
                return delegate.checkHealth();
            }
            return active.isHealthy();
        }
        return baseClient.checkHealth();
    }

    /**
     * Live health checks for every chain member (or just the single provider
     * when no chain is configured). Keyed by provider id.
     */
    public Map<String, Boolean> pingProviderChain() {
        List<AsyncLLMClient> members = (providerChain != null)
            ? providerChain.getMembers()
            : List.of(getBaseClient());
        Map<String, Boolean> result = new java.util.LinkedHashMap<>();
        for (AsyncLLMClient member : members) {
            OpenAICompatibleClient delegate = httpDelegateOf(member);
            if (delegate != null) {
                result.put(member.getProviderId(), delegate.checkHealth());
            } else {
                // Non-HTTP members: fall back to the cheap liveness signal.
                result.put(member.getProviderId(), member.isHealthy());
            }
        }
        return result;
    }

    private OpenAICompatibleClient getBaseClient() {
        return baseClient;
    }

    /**
     * The concrete HTTP client behind a chain member (unwrapping
     * {@code ResilientLLMClient} if needed), or null when the member is not an
     * HTTP client. Without unwrapping, per-member model/baseUrl overrides and
     * live health checks are invisible for every chain member.
     */
    private static OpenAICompatibleClient httpDelegateOf(AsyncLLMClient member) {
        if (member instanceof OpenAICompatibleClient openAi) {
            return openAi;
        }
        if (member instanceof ResilientLLMClient resilient) {
            return resilient.unwrapOpenAiClient();
        }
        return null;
    }

    /**
     * The ACTUAL active provider id: the current chain head during normal
     * operation; after a failover this returns whichever member traffic moved
     * to (and after recovery, the head again). Without a chain it is simply
     * the configured {@code llm.provider}.
     */
    public String getActiveProvider() {
        if (providerChain != null) {
            return providerChain.getActiveProviderId();
        }
        String configured = VasyanConfig.AI_PROVIDER.get().toLowerCase().trim();
        return LLMProviders.isValid(configured) ? configured : LLMProviders.OLLAMA;
    }

    /**
     * Model of the ACTUAL active provider. With shared config fields every
     * member resolves the same model unless its preset default applies.
     */
    public String getActiveModel() {
        if (providerChain != null) {
            AsyncLLMClient active = providerChain.getMembers().get(providerChain.getActiveIndex());
            OpenAICompatibleClient delegate = httpDelegateOf(active);
            if (delegate != null) {
                return delegate.getModel();
            }
        }
        String model = VasyanConfig.LLM_MODEL.get();
        if (model == null || model.isEmpty()) {
            return LLMProviders.resolveModel(getActiveProvider(), "");
        }
        return model;
    }

    /**
     * Base URL of the ACTUAL active provider.
     */
    public String getActiveBaseUrl() {
        if (providerChain != null) {
            AsyncLLMClient active = providerChain.getMembers().get(providerChain.getActiveIndex());
            OpenAICompatibleClient delegate = httpDelegateOf(active);
            if (delegate != null) {
                return delegate.getBaseUrl();
            }
        }
        return LLMProviders.resolveBaseUrl(getActiveProvider(), VasyanConfig.LLM_BASE_URL.get());
    }

    public boolean validateTask(Task task) {
        String action = task.getAction();

        return switch (action) {
            case "pathfind" -> task.hasParameters("x", "y", "z");
            case "mine" -> task.hasParameters("block", "quantity");
            case "place" -> task.hasParameters("block", "x", "y", "z");
            case "craft" -> task.hasParameters("item", "quantity");
            case "attack" -> task.hasParameters("target");
            case "follow" -> task.hasParameters("player");
            case "gather" -> task.hasParameters("resource", "quantity");
            case "build" -> task.hasParameters("structure", "blocks", "dimensions");
            default -> {
                VasyanMod.LOGGER.warn("Unknown action type: {}", action);
                yield false;
            }
        };
    }

    public List<Task> validateAndFilterTasks(List<Task> tasks) {
        return tasks.stream()
            .filter(this::validateTask)
            .toList();
    }

    /**
     * Merges ALL gather tasks into a single any-log ("wood") task: the FIRST
     * gather task keeps its parameters (quantity, fill, ...) with
     * resource forced to "wood", and every later gather task is dropped.
     * Non-gather tasks are preserved in their original order; the merged
     * task takes the first gather task's slot.
     *
     * <p>Pure/static and Minecraft-free so it can be unit tested.</p>
     */
    static List<Task> collapseWoodGatherTasks(List<Task> tasks) {
        List<Task> result = new ArrayList<>(tasks.size());
        boolean merged = false;
        for (Task task : tasks) {
            if (!"gather".equals(task.getAction())) {
                result.add(task);
                continue;
            }
            if (merged) {
                // Later per-type gather tasks (birch_log after oak_log, ...)
                // describe the same wood request - drop them.
                continue;
            }
            task.getParameters().put("resource", "wood");
            result.add(task);
            merged = true;
        }
        return result;
    }

    /**
     * Removes gather tasks whose (resource, quantity, fill) triple exactly
     * duplicates an earlier gather task. Gathers for different resources
     * (iron vs coal) or different quantities are kept. Non-gather tasks are
     * untouched and the original order is preserved.
     *
     * <p>Pure/static and Minecraft-free so it can be unit tested.</p>
     */
    static List<Task> dedupeGatherTasks(List<Task> tasks) {
        List<Task> result = new ArrayList<>(tasks.size());
        Set<String> seen = new HashSet<>();
        for (Task task : tasks) {
            if (!"gather".equals(task.getAction())) {
                result.add(task);
                continue;
            }
            String key = String.valueOf(task.getParameter("resource")) + "|"
                + String.valueOf(task.getParameter("quantity")) + "|"
                + String.valueOf(task.getParameter("fill"));
            if (seen.add(key)) {
                result.add(task);
            }
        }
        return result;
    }

    /**
     * Compact one-line plan description for logs, e.g. "gather wood x64;follow Alex".
     */
    private static String describeTasks(List<Task> tasks) {
        StringBuilder sb = new StringBuilder();
        for (Task task : tasks) {
            if (sb.length() > 0) {
                sb.append(';');
            }
            sb.append(task.getAction());
            String what = task.getStringParameter("resource");
            if (what == null) what = task.getStringParameter("block");
            if (what == null) what = task.getStringParameter("target");
            if (what == null) what = task.getStringParameter("player");
            if (what != null) {
                sb.append(' ').append(what);
            }
            Object quantity = task.getParameter("quantity");
            if (quantity != null) {
                sb.append(" x").append(quantity);
            }
        }
        return sb.toString();
    }

    private static String truncate(String str, int maxLength) {
        if (str == null) return "[null]";
        if (str.length() <= maxLength) return str;
        return str.substring(0, maxLength) + "...";
    }
}
