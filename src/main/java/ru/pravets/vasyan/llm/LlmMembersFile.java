package ru.pravets.vasyan.llm;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.electronwill.nightconfig.core.io.ConfigParser;
import net.minecraftforge.fml.loading.FMLPaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Per-provider LLM endpoint settings stored in a MOD-OWNED file:
 * {@code config/vasyan-llm-members.toml}.
 *
 * <p>Why a separate file instead of sections in vasyan-common.toml?
 * ForgeConfigSpec corrects its file on every load and silently REMOVES any
 * key or section that is not in the compiled schema. Schema keys are static,
 * so arbitrary member names cannot be expressed there - user data written
 * under them gets wiped on startup. This file is parsed directly with
 * NightConfig and never rewritten by the mod, so nothing is ever stripped.</p>
 *
 * <p>Format:</p>
 * <pre>
 * [opencode-go]
 * apiKey = "..."
 * model = "deepseek-v4-pro"
 *
 * [my-own-endpoint]
 * baseUrl = "https://llm.example.com/v1"
 * apiKey = "..."
 * model = "gpt-x"
 * </pre>
 *
 * <p>Any section name is allowed; ids referenced from
 * {@code llm.providerChain} resolve here first, then fall back to the shared
 * Forge-config fields and finally to the preset defaults.</p>
 */
public final class LlmMembersFile {

    private static final Logger LOGGER = LoggerFactory.getLogger(LlmMembersFile.class);
    private static final String FILE_NAME = "vasyan-llm-members.toml";

    private static volatile Map<String, MemberSettings> cache = Map.of();

    private LlmMembersFile() {}

    /**
     * Effective per-member settings parsed from the members file. Every field
     * may be null/blank - meaning "not overridden here, fall through to the
     * next resolution level" (Forge member section, shared llm.*, preset).
     *
     * @param baseUrl endpoint base, e.g. {@code https://llm.example.com/v1}
     * @param apiKey  bearer token sent as {@code Authorization} header
     * @param model   model id passed to the provider's chat completions API
     */
    public record MemberSettings(String baseUrl, String apiKey, String model) {
        /**
         * True when at least one field is set - i.e. this file contributes
         * anything at all for this member.
         */
        public boolean hasAny() {
            return isSet(baseUrl) || isSet(apiKey) || isSet(model);
        }
        private static boolean isSet(String v) {
            return v != null && !v.isBlank();
        }
    }

    /**
     * Settings for the given member id (case-insensitive), or null when the
     * members file has no section for it.
     */
    public static MemberSettings get(String memberId) {
        return cache.get(memberId.toLowerCase(Locale.ROOT));
    }

    /**
     * Re-reads {@code config/vasyan-llm-members.toml} and atomically replaces
     * the cached settings map. Called once during mod construction; call again
     * after manual edits to apply them without a restart. A parse failure
     * keeps the previously loaded values and logs an error.
     */
    public static synchronized void reload() {
        Path file = FMLPaths.CONFIGDIR.get().resolve(FILE_NAME);
        if (!Files.exists(file)) {
            cache = Map.of();
            return;
        }
        try {
            var parser = new com.electronwill.nightconfig.toml.TomlParser();
            CommentedConfig cfg;
            try (var reader = Files.newBufferedReader(file)) {
                cfg = parser.parse(reader);
            }
            Map<String, MemberSettings> out = new HashMap<>();
            for (var entry : cfg.valueMap().entrySet()) {
                if (!(entry.getValue() instanceof CommentedConfig section)) {
                    continue;
                }
                String id = entry.getKey().toLowerCase(Locale.ROOT);
                String base = section.get("baseUrl");
                String key = section.get("apiKey");
                String model = section.get("model");
                MemberSettings ms = new MemberSettings(base, key, model);
                if (ms.hasAny()) {
                    out.put(id, ms);
                }
            }
            cache = Map.copyOf(out);
            LOGGER.info("Loaded {} LLM member override(s) from {}", out.size(), FILE_NAME);
        } catch (Exception e) {
            LOGGER.error("Failed to parse {} - member overrides ignored this session: {}",
                FILE_NAME, e.getMessage());
            cache = Map.of();
        }
    }
}
