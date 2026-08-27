package ru.pravets.vasyan.llm.resilience;

import ru.pravets.vasyan.llm.async.LLMException;
import ru.pravets.vasyan.llm.async.LLMResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.regex.Pattern;

/**
 * Fallback handler that generates pattern-based responses when LLM calls fail.
 *
 * <p>Provides graceful degradation when all LLM providers are unavailable.
 * Responses are emitted in the exact format expected by
 * {@link ru.pravets.vasyan.llm.ResponseParser} and pass
 * {@code TaskPlanner.validateTask} so that the agent keeps doing something
 * sensible instead of silently doing nothing.</p>
 *
 * <p><b>When is this used?</b></p>
 * <ul>
 *   <li>Circuit breaker is OPEN (provider experiencing failures)</li>
 *   <li>All retry attempts exhausted</li>
 *   <li>Rate limiter rejects request</li>
 *   <li>Network is completely unavailable</li>
 * </ul>
 */
public class LLMFallbackHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(LLMFallbackHandler.class);

    // Pattern-based fallback responses in ResponseParser JSON format.
    // IMPORTANT: parameters must satisfy TaskPlanner.validateTask:
    //   mine   -> block, quantity
    //   build  -> structure, blocks, dimensions
    //   attack -> target
    //   follow -> player
    //   place  -> block, x, y, z
    // There is no "wait" action - the safe default is "follow".
    private static final Map<Pattern, String> PATTERN_RESPONSES = Map.of(
        // Mining patterns
        Pattern.compile("(?i).*(mine|dig|collect|gather|ore|diamond|iron|coal|stone).*"),
        "{\"reasoning\":\"[Fallback] Mining action detected\",\"plan\":\"Mine iron ore\",\"tasks\":[{\"action\":\"mine\",\"parameters\":{\"block\":\"iron_ore\",\"quantity\":10}}]}",

        // Building patterns
        Pattern.compile("(?i).*(build|construct|create|make).*(house|home|shelter|structure|base).*"),
        "{\"reasoning\":\"[Fallback] Building action detected\",\"plan\":\"Build a house\",\"tasks\":[{\"action\":\"build\",\"parameters\":{\"structure\":\"house\",\"blocks\":[\"oak_planks\",\"cobblestone\"],\"dimensions\":[9,6,9]}}]}",

        // Combat patterns
        Pattern.compile("(?i).*(attack|fight|kill|destroy|hostile|monster|zombie|skeleton|creeper).*"),
        "{\"reasoning\":\"[Fallback] Combat action detected\",\"plan\":\"Attack hostiles\",\"tasks\":[{\"action\":\"attack\",\"parameters\":{\"target\":\"hostile\"}}]}",

        // Follow patterns
        Pattern.compile("(?i).*(follow|come|here|with me|accompany).*"),
        "{\"reasoning\":\"[Fallback] Follow action detected\",\"plan\":\"Follow the player\",\"tasks\":[{\"action\":\"follow\",\"parameters\":{\"player\":\"USE_NEARBY_PLAYER_NAME\"}}]}",

        // Movement patterns -> follow the player (pathfind needs coordinates we don't have)
        Pattern.compile("(?i).*(go to|move to|walk to|travel|path|navigate).*"),
        "{\"reasoning\":\"[Fallback] Movement detected, following player\",\"plan\":\"Follow the player\",\"tasks\":[{\"action\":\"follow\",\"parameters\":{\"player\":\"USE_NEARBY_PLAYER_NAME\"}}]}",

        // Placement patterns
        Pattern.compile("(?i).*(place|put|set).*(block|torch|door).*"),
        "{\"reasoning\":\"[Fallback] Placement action detected\",\"plan\":\"Place a torch\",\"tasks\":[{\"action\":\"place\",\"parameters\":{\"block\":\"torch\",\"x\":0,\"y\":0,\"z\":0}}]}",

        // Stop patterns -> there is no wait action, follow is the safest no-op
        Pattern.compile("(?i).*(stop|halt|cancel|wait|pause|stay).*"),
        "{\"reasoning\":\"[Fallback] Idle action detected\",\"plan\":\"Stay near the player\",\"tasks\":[{\"action\":\"follow\",\"parameters\":{\"player\":\"USE_NEARBY_PLAYER_NAME\"}}]}"
    );

    // Default response when no pattern matches. Public: ProviderChainClient
    // synthesizes the same all-dead answer without instantiating a handler.
    public static final String DEFAULT_FALLBACK_RESPONSE =
        "{\"reasoning\":\"[Fallback] No pattern matched\",\"plan\":\"Stay near the player\",\"tasks\":[{\"action\":\"follow\",\"parameters\":{\"player\":\"USE_NEARBY_PLAYER_NAME\"}}]}";

    /**
     * Generates a fallback response based on pattern matching.
     *
     * @param prompt Original prompt that failed
     * @param error  The error that triggered the fallback (for logging)
     * @return LLMResponse containing pattern-matched action or default follow action
     */
    public LLMResponse generateFallback(String prompt, Throwable error) {
        LOGGER.warn("Generating fallback response for prompt: '{}' (error: {})",
            truncatePrompt(prompt, 50),
            error != null ? error.getClass().getSimpleName() + ": " + error.getMessage() : "unknown");

        String responseContent = matchPattern(prompt);
        String matchedPattern = responseContent.equals(DEFAULT_FALLBACK_RESPONSE) ? "default" : "pattern-match";

        LOGGER.info("Fallback response generated (matched: {})", matchedPattern);

        return LLMResponse.builder()
            .content(responseContent)
            .model("fallback-pattern-matcher")
            .providerId("fallback")
            .latencyMs(0)
            .tokensUsed(0)
            .fromCache(false)
            .failureReason(describeFailure(error))
            .build();
    }

    /**
     * Builds a short player-readable failure reason from the triggering error.
     *
     * @param error the exception that caused the fallback (may be null)
     * @return e.g. "таймаут 60s", "нет соединения", "HTTP 500", "unknown"
     */
    static String describeFailure(Throwable error) {
        if (error == null) {
            return "unknown";
        }
        // Unwrap CompletionException chains
        Throwable t = error;
        while (t.getCause() != null && t.getCause() != t
                && t instanceof java.util.concurrent.CompletionException) {
            t = t.getCause();
        }
        if (t instanceof java.net.http.HttpTimeoutException
                || t instanceof java.util.concurrent.TimeoutException) {
            return "таймаут";
        }
        if (t instanceof java.nio.channels.UnresolvedAddressException) {
            return "хост не найден";
        }
        if (t instanceof java.net.ConnectException) {
            return "нет соединения";
        }
        if (t instanceof io.github.resilience4j.ratelimiter.RequestNotPermitted) {
            return "rate limit";
        }
        if (t instanceof java.io.IOException) {
            return "сетевая ошибка";
        }
        if (t instanceof LLMException llm) {
            return switch (llm.getErrorType()) {
                case TIMEOUT -> "таймаут";
                case RATE_LIMIT -> "rate limit";
                case SERVER_ERROR -> "ошибка сервера";
                case AUTH_ERROR -> "неверный API-ключ";
                case NETWORK_ERROR -> "нет соединения";
                default -> llm.getErrorType().name().toLowerCase();
            };
        }
        return t.getClass().getSimpleName();
    }

    /**
     * Matches the prompt against known patterns.
     *
     * @param prompt The prompt to analyze
     * @return Matching response JSON or default response
     */
    private String matchPattern(String prompt) {
        if (prompt == null || prompt.isEmpty()) {
            return DEFAULT_FALLBACK_RESPONSE;
        }

        String lowerPrompt = prompt.toLowerCase();

        // Coal gathering keeps the exact requested resource so behavior tests and offline
        // play do not silently turn a coal request into generic iron mining.
        java.util.regex.Matcher coal = java.util.regex.Pattern.compile(
            "(?i).*(mine|dig|collect|gather|добудь|накопай|собери).*"
            + "(\\d+)?\\s*(coal|уг[оа]л[ьяе]?)s?.*", Pattern.DOTALL).matcher(lowerPrompt);
        if (coal.matches()) {
            int qty = 1;
            java.util.regex.Matcher num = java.util.regex.Pattern.compile("\\d+").matcher(lowerPrompt);
            if (num.find()) {
                try {
                    qty = Integer.parseInt(num.group());
                } catch (NumberFormatException ignored) {
                    // keep default
                }
            }
            LOGGER.info("Fallback -> gather coal x{}", qty);
            return "{\"reasoning\":\"[Fallback] Coal gathering detected\",\"plan\":\"Gather coal\","
                + "\"tasks\":[{\"action\":\"gather\",\"parameters\":{\"resource\":\"coal\",\"quantity\":" + qty + "}}]}";
        }

        // Wood/tree gathering, RU + EN, with an optional quantity:
        // "накопай 100 дерева" / "руби берёзу" / "chop 20 wood". Falls back
        // to gather-any-log (wood) instead of the generic "follow" default.
        java.util.regex.Matcher wood = java.util.regex.Pattern.compile(
            "(?i).*(копай|копать|руби|рубить|добудь|накопай|собери|наруби|нарезать|gather|chop|collect).*"
            + "(\\d+)?\\s*"
            + "(дерев|wood|бр[её]вн|б[её]вен|лес|дров|log)s?.*").matcher(lowerPrompt);
        if (wood.matches()) {
            int qty = 50;
            java.util.regex.Matcher num = java.util.regex.Pattern.compile("\\d+").matcher(lowerPrompt);
            if (num.find()) {
                try {
                    qty = Integer.parseInt(num.group());
                } catch (NumberFormatException ignored) {
                    // keep default
                }
            }
            LOGGER.info("Fallback -> gather wood x{}", qty);
            return "{\"reasoning\":\"[Fallback] Wood gathering detected\",\"plan\":\"Gather wood\","
                + "\"tasks\":[{\"action\":\"gather\",\"parameters\":{\"resource\":\"wood\",\"quantity\":" + qty + "}}]}";
        }

        for (Map.Entry<Pattern, String> entry : PATTERN_RESPONSES.entrySet()) {
            if (entry.getKey().matcher(lowerPrompt).matches()) {
                LOGGER.debug("Matched pattern: {}", entry.getKey().pattern());
                return entry.getValue();
            }
        }

        LOGGER.debug("No pattern matched, using default response");
        return DEFAULT_FALLBACK_RESPONSE;
    }

    private String truncatePrompt(String prompt, int maxLength) {
        if (prompt == null) {
            return "[null]";
        }
        if (prompt.length() <= maxLength) {
            return prompt;
        }
        return prompt.substring(0, maxLength) + "...";
    }

    /**
     * Checks if a prompt would match any known pattern.
     */
    public boolean wouldMatchPattern(String prompt) {
        if (prompt == null || prompt.isEmpty()) {
            return false;
        }

        String lowerPrompt = prompt.toLowerCase();
        return PATTERN_RESPONSES.keySet().stream()
            .anyMatch(pattern -> pattern.matcher(lowerPrompt).matches());
    }

    /**
     * Returns the number of registered patterns.
     */
    public int getPatternCount() {
        return PATTERN_RESPONSES.size();
    }
}
