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
    public static final String CUSTOM = "custom";

    public record Preset(String baseUrl, String defaultModel, boolean requiresKey) {}

    private static final Map<String, Preset> PRESETS = Map.of(
        OPENAI,     new Preset("https://api.openai.com/v1", "gpt-4o-mini", true),
        GROQ,       new Preset("https://api.groq.com/openai/v1", "llama-3.1-8b-instant", true),
        GEMINI,     new Preset("https://generativelanguage.googleapis.com/v1beta/openai", "gemini-2.5-flash", true),
        OLLAMA,     new Preset("http://127.0.0.1:11434/v1", "llama3.1", false),
        LMSTUDIO,   new Preset("http://127.0.0.1:1234/v1", "", false),
        OPENCODE_GO, new Preset("https://opencode.ai/zen/go/v1", "deepseek-v4-flash", true),
        CUSTOM,     new Preset("", "", false)
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
