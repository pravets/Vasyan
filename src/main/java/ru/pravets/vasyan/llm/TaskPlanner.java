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
    private volatile PlanRecord lastPlanRecord;

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
        this.llmClient = new ResilientLLMClient(baseClient, llmCache, new LLMFallbackHandler());

        VasyanMod.LOGGER.info("TaskPlanner initialized: provider={}, baseUrl={}, model={}, jsonMode={}",
            provider, baseClient.getBaseUrl(), baseClient.getModel(), jsonMode);
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

            String provider = VasyanConfig.AI_PROVIDER.get().toLowerCase();
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
                        vasyan.sendChatMessage("⚠️ LLM недоступен (" + response.getModel()
                            + ") — запасной план: " + parsed.getPlan());
                    }
                    AgentDebugBuffer.log(vasyan.getVasyanName(), "PARSE",
                        "ok, " + parsed.getTasks().size() + " tasks, plan=\"" + truncate(parsed.getPlan(), 200)
                            + "\", tasks=" + truncate(describeTasks(parsed.getTasks()), 300));

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
     * Checks if the configured provider's async client is healthy.
     */
    public boolean isProviderHealthy() {
        return llmClient.isHealthy();
    }

    /**
     * Live health check of the configured provider endpoint (GET /models).
     */
    public boolean pingProvider() {
        return getBaseClient().checkHealth();
    }

    private OpenAICompatibleClient getBaseClient() {
        return baseClient;
    }

    public String getActiveProvider() {
        return VasyanConfig.AI_PROVIDER.get().toLowerCase();
    }

    public String getActiveModel() {
        String model = VasyanConfig.LLM_MODEL.get();
        if (model == null || model.isEmpty()) {
            return LLMProviders.resolveModel(getActiveProvider(), "");
        }
        return model;
    }

    public String getActiveBaseUrl() {
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
