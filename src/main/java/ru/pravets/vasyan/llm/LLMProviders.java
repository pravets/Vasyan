package ru.pravets.vasyan.llm;

import java.util.Map;

/**
 * Provider presets for the LLM layer.
 *
 * <p>Every preset points at an OpenAI-compatible Chat Completions endpoint.
 * The active provider is selected via {@code llm.provider} in the config;
 * {@code baseUrl} and {@code model} can be overridden per-installation.</p>
 */
public final class LLMProviders {

    public static final String OPENAI = "openai";
    public static final String GROQ = "groq";
    public static final String GEMINI = "gemini";
    public static final String OLLAMA = "ollama";
    public static final String LMSTUDIO = "lmstudio";
    public static final String OPENCODE_GO = "opencode-go";
    public static final String DEEPSEEK = "deepseek";
    public static final String ROUTERAI = "routerai";
    public static final String CLOUD_RU_FM = "cloud-ru-fm";
    public static final String SELECTEL_ROUTER = "selectel-router";
    public static final String TOKENRA = "tokenra";
    public static final String CUSTOM = "custom";

    public record Preset(String baseUrl, String defaultModel, boolean requiresKey) {}

    private static final Map<String, Preset> PRESETS = Map.ofEntries(
        Map.entry(OPENAI,     new Preset("https://api.openai.com/v1", "gpt-4o-mini", true)),
Map.entry(GROQ, new Preset("https://api.groq.com/openai/v1", "llama-3.1-8b-instant", true)),
Map.entry(GEMINI, new Preset("https://generativelanguage.googleapis.com/v1beta/openai", "gemini-2.5-flash", true)),
Map.entry(OLLAMA, new Preset("http://127.0.0.1:11434/v1", "llama3.1", false)),
Map.entry(LMSTUDIO, new Preset("http://127.0.0.1:1234/v1", "", false)),
Map.entry(OPENCODE_GO, new Preset("https://opencode.ai/zen/go/v1", "deepseek-v4-flash", true)),
Map.entry(DEEPSEEK, new Preset("https://api.deepseek.com", "deepseek-v4-flash", true)),
Map.entry(ROUTERAI, new Preset("https://routerai.ru/api/v1", "", true)),
Map.entry(CLOUD_RU_FM, new Preset("https://foundation-models.api.cloud.ru/v1", "", true)),
Map.entry(SELECTEL_ROUTER, new Preset("https://api.selectel.ru/aig/v1", "", true)),
Map.entry(TOKENRA, new Preset("https://tokenra.io/v1/", "", true)),
        Map.entry(CUSTOM,     new Preset("", "", false))
    );

    private LLMProviders() {}

    public static boolean isValid(String providerId) {
        return PRESETS.containsKey(providerId);
    }

    public static Preset get(String providerId) {
        Preset preset = PRESETS.get(providerId);
        if (preset == null) {
            throw new IllegalArgumentException("Unknown LLM provider: " + providerId);
        }
        return preset;
    }

    /**
     * Resolves the effective base URL: explicit override wins, otherwise the preset default.
     */
    public static String resolveBaseUrl(String providerId, String override) {
        if (override != null && !override.isBlank()) {
            return override;
        }
        String presetBase = get(providerId).baseUrl();
        if (presetBase == null || presetBase.isEmpty()) {
            throw new IllegalArgumentException(
                "No base URL for provider '" + providerId + "'. Set llm.baseUrl in the config.");
        }
        return presetBase;
    }

    /**
     * Resolves the effective model: explicit value wins, otherwise the preset default.
     */
    public static String resolveModel(String providerId, String override) {
        if (override != null && !override.isBlank()) {
            return override;
        }
        return get(providerId).defaultModel();
    }

    public static boolean requiresKey(String providerId) {
        return get(providerId).requiresKey();
    }
}
