package ru.pravets.vasyan.config;

import net.minecraftforge.common.ForgeConfigSpec;

import java.util.List;

public class VasyanConfig {
    public static final ForgeConfigSpec SPEC;
    public static final ForgeConfigSpec.ConfigValue<String> AI_PROVIDER;
    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> PROVIDER_CHAIN;
    public static final ForgeConfigSpec.IntValue FAILOVER_RETRY_SECONDS;
    public static final ForgeConfigSpec.ConfigValue<String> LLM_BASE_URL;
    public static final ForgeConfigSpec.ConfigValue<String> LLM_API_KEY;
    public static final ForgeConfigSpec.ConfigValue<String> LLM_MODEL;
    public static final ForgeConfigSpec.BooleanValue LLM_JSON_MODE;
    public static final ForgeConfigSpec.IntValue MAX_TOKENS;
    public static final ForgeConfigSpec.DoubleValue TEMPERATURE;
    public static final ForgeConfigSpec.IntValue LLM_TIMEOUT_SECONDS;
    public static final ForgeConfigSpec.IntValue PLANNING_TIMEOUT_SECONDS;
    public static final ForgeConfigSpec.IntValue WORLD_SCAN_RADIUS;
    public static final ForgeConfigSpec.IntValue WORLD_SCAN_STEP;
    public static final ForgeConfigSpec.IntValue WORLD_SCAN_CACHE_TICKS;
    public static final ForgeConfigSpec.IntValue GATHER_SEARCH_RADIUS;
    public static final ForgeConfigSpec.IntValue GATHER_SEARCH_TIMEOUT;
    public static final ForgeConfigSpec.IntValue GATHER_RING_SPACING;
    public static final ForgeConfigSpec.IntValue GATHER_STATIONS_PER_RING;
    public static final ForgeConfigSpec.IntValue ACTION_TICK_DELAY;
    public static final ForgeConfigSpec.BooleanValue ENABLE_CHAT_RESPONSES;
    public static final ForgeConfigSpec.IntValue MAX_ACTIVE_VASYANS;
    public static final ForgeConfigSpec.BooleanValue VOICE_ENABLED;
    public static final ForgeConfigSpec.ConfigValue<String> STT_BASE_URL;
    public static final ForgeConfigSpec.ConfigValue<String> STT_API_KEY;
    public static final ForgeConfigSpec.ConfigValue<String> STT_MODEL;
    public static final ForgeConfigSpec.ConfigValue<String> STT_LANGUAGE;
    public static final ForgeConfigSpec.IntValue VOICE_MAX_RECORDING_SECONDS;
    public static final ForgeConfigSpec.IntValue VOICE_CHUNK_SIZE;
    public static final ForgeConfigSpec.BooleanValue FORCE_LOAD_CHUNKS;

    static {
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();

        builder.comment("LLM provider configuration. All providers use the OpenAI-compatible Chat Completions API.",
            "provider: openai | groq | gemini | ollama | lmstudio | opencode-go | custom",
            "  ollama     -> http://127.0.0.1:11434/v1 (no key needed)",
            "  lmstudio   -> http://127.0.0.1:1234/v1 (no key needed)",
            "  opencode-go-> https://opencode.ai/zen/go/v1 (key from OpenCode Zen, models like deepseek-v4-flash)",
            "  custom     -> any OpenAI-compatible endpoint, baseUrl is required")
            .push("llm");

        AI_PROVIDER = builder
            .comment("Active LLM provider")
            .define("provider", "ollama");

        PROVIDER_CHAIN = builder
            .comment("Provider failover chain, in priority order. Example:",
                "  providerChain = [\"opencode-go\", \"ollama\"]",
                "If a request to the active provider fails, the next provider in this",
                "list is tried within the SAME request. When the head (highest-priority)",
                "provider recovers, traffic automatically fails back after",
                "failoverRetrySeconds. Empty or missing = single-provider mode using",
                "'provider' only (backward compatible). Unknown ids are skipped with a",
                "warning; duplicates are removed.")
            .defineListAllowEmpty("providerChain", java.util.Collections::<String>emptyList,
                o -> o instanceof String s && !s.isBlank());

        FAILOVER_RETRY_SECONDS = builder
            .comment("Seconds before the chain retries the highest-priority provider",
                "after failing over to a lower-priority one (cooldown).",
                "Also throttles recovery probes so a dead head is not hammered.")
            .defineInRange("failoverRetrySeconds", 60, 5, 3600);

        LLM_BASE_URL = builder
            .comment("Base URL override. Empty = preset default (e.g. http://127.0.0.1:11434/v1 for ollama).",
                "Required for provider 'custom'.")
            .define("baseUrl", "");

        LLM_API_KEY = builder
            .comment("API key. Empty for ollama/lmstudio; required for openai/groq/gemini/opencode-go.")
            .define("apiKey", "");

        LLM_MODEL = builder
            .comment("Model name. Empty = preset default (deepseek-v4-flash for opencode-go, llama3.1 for ollama).",
                "For lmstudio leave empty to use whatever model is currently loaded.")
            .define("model", "");

        LLM_JSON_MODE = builder
            .comment("Send response_format: {\"type\":\"json_object\"}. Greatly improves JSON output reliability.",
                "Disable if your provider rejects this field.")
            .define("jsonMode", true);

        MAX_TOKENS = builder
            .comment("Maximum tokens per API request")
            .defineInRange("maxTokens", 8000, 100, 65536);

        TEMPERATURE = builder
            .comment("Temperature for AI responses (0.0-2.0, lower is more deterministic)")
            .defineInRange("temperature", 0.7, 0.0, 2.0);

        LLM_TIMEOUT_SECONDS = builder
            .comment("Per-request timeout in seconds")
            .defineInRange("timeoutSeconds", 60, 5, 300);

        PLANNING_TIMEOUT_SECONDS = builder
            .comment("Maximum time in seconds the ActionExecutor will wait for async LLM planning to complete.",
                "This is a safety guard above the per-HTTP-request timeout.")
            .defineInRange("planningTimeoutSeconds", 75, 5, 600);

        builder.pop();

        builder.comment("Vasyan Vision (world perception) Configuration",
            "Vasyan scans the world around him to find blocks and entities. The scan is",
            "honest: a block is only seen if there is a clear line of sight (no cheats).",
            "Scans run on demand and results are cached for a few ticks.")
            .push("vision");

        WORLD_SCAN_RADIUS = builder
            .comment("Vision radius in blocks (how far Vasyan can see)")
            .defineInRange("scanRadius", 32, 8, 64);

        WORLD_SCAN_STEP = builder
            .comment("Scan grid step (1 = every block, 2 = every other block).",
                "Lower = more precise but slower. 2 is fine for finding trees/ores/chests.")
            .defineInRange("scanStep", 2, 1, 8);

        WORLD_SCAN_CACHE_TICKS = builder
            .comment("How many ticks a vision scan result is reused (20 ticks = 1 second)")
            .defineInRange("scanCacheTicks", 20, 5, 200);

        builder.pop();

        builder.comment("Vasyan Gathering (resource search) Configuration",
            "How Vasyan searches for resources: a walking spiral of look-out",
            "stations around the start point, scanning with vision at each station.",
            "Vasyan never digs tunnels - he only mines visible blocks.")
            .push("gather");

        GATHER_SEARCH_RADIUS = builder
            .comment("Search radius in blocks (how far from the start point Vasyan walks)")
            .defineInRange("searchRadius", 32, 8, 128);

        GATHER_SEARCH_TIMEOUT = builder
            .comment("Max search time in ticks before giving up (20 ticks = 1 second)")
            .defineInRange("searchTimeoutTicks", 1200, 100, 72000);

        GATHER_RING_SPACING = builder
            .comment("Distance between search rings (blocks)")
            .defineInRange("ringSpacing", 8, 4, 32);

        GATHER_STATIONS_PER_RING = builder
            .comment("Look-out stations per ring")
            .defineInRange("stationsPerRing", 8, 4, 16);

        builder.pop();

        builder.comment("Vasyan Behavior Configuration").push("behavior");

        ACTION_TICK_DELAY = builder
            .comment("Ticks between action checks (20 ticks = 1 second)")
            .defineInRange("actionTickDelay", 20, 1, 100);

        ENABLE_CHAT_RESPONSES = builder
            .comment("Allow Vasyans to respond in chat")
            .define("enableChatResponses", true);

        MAX_ACTIVE_VASYANS = builder
            .comment("Maximum number of Vasyans that can be active simultaneously")
            .defineInRange("maxActiveVasyans", 10, 1, 50);

        FORCE_LOAD_CHUNKS = builder
            .comment("Keep the chunk each Vasyan stands in force-loaded so Vasyans",
                "keep working on a dedicated server even when no player is online")
            .define("forceLoadChunks", true);

        builder.pop();

        builder.comment("Vasyan Voice Commands Configuration",
            "Push-to-talk voice commands (key V): the client records the microphone",
            "and sends the audio to the server, which transcribes it via ANY",
            "OpenAI-compatible /audio/transcriptions endpoint and dispatches the",
            "text as a normal chat command.").push("voice");

        VOICE_ENABLED = builder
            .comment("Enable voice commands (requires a microphone and an STT endpoint)",
                "Disabled by default: audio leaves the client and reaches the configured",
                "STT endpoint - enable explicitly after setting sttApiKey")
            .define("enabled", false);

        STT_BASE_URL = builder
            .comment("Base URL of an OpenAI-compatible STT endpoint.",
                "Any provider works: set your own, e.g. https://routerai.ru/api/v1",
                "The transcription call is POST {baseUrl}/audio/transcriptions")
            .define("sttBaseUrl", "https://routerai.ru/api/v1");

        STT_API_KEY = builder
            .comment("API key for the STT endpoint (stored server-side only)")
            .define("sttApiKey", "");

        STT_MODEL = builder
            .comment("STT model name (any model your endpoint supports)")
            .define("sttModel", "openai/whisper-large-v3-turbo");

        STT_LANGUAGE = builder
            .comment("STT language hint; empty = auto-detect",
                "e.g. \"ru\" for Russian commands, \"en\" for English")
            .define("sttLanguage", "ru");

        VOICE_MAX_RECORDING_SECONDS = builder
            .comment("Maximum recording length in seconds (auto-stop)")
            .defineInRange("maxRecordingSeconds", 10, 2, 60);

        VOICE_CHUNK_SIZE = builder
            .comment("Audio chunk size in bytes sent per network packet")
            .defineInRange("chunkSize", 16384, 4096, 32767);

        builder.pop();

        SPEC = builder.build();
    }
}
