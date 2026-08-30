package ru.pravets.vasyan.llm.resilience;

import ru.pravets.vasyan.llm.async.LLMException;
import ru.pravets.vasyan.llm.async.LLMResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.regex.Matcher;
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

    // Default response when no pattern matches.
    public static final String DEFAULT_FALLBACK_RESPONSE =
        "{\"reasoning\":\"[Fallback] No pattern matched\",\"plan\":\"Stay near the player\",\"tasks\":[{\"action\":\"follow\",\"parameters\":{\"player\":\"USE_NEARBY_PLAYER_NAME\"}}]}";

    /** Marker separating the situation block from the current user request. */
    private static final String CURRENT_REQUEST_MARKER = "CURRENT REQUEST:";

    // Resource-specific fallback patterns must only match the current request,
    // not the situation block that can mention coal/wood incidentally.
    private static final Pattern COAL_PATTERN = Pattern.compile(
        "(?i).*(mine|dig|collect|gather|добудь|накопай|собери).*"
            + "(\\d+)?\\s*(coal|уг[оа]л[ьяе]?)s?.*", Pattern.DOTALL);
    private static final Pattern COAL_QTY_PATTERN = Pattern.compile(
        "(\\d+)\\s*(coal|уг[оа]л[ьяе]?)s?", Pattern.DOTALL);
    private static final Pattern WOOD_PATTERN = Pattern.compile(
        "(?i).*(копай|копать|руби|рубить|добудь|накопай|собери|наруби|нарезать|gather|chop|collect).*"
            + "(\\d+)?\\s*"
            + "(дерев|wood|бр[её]вн|б[её]вен|лес|дров|log)s?.*");
    private static final Pattern WOOD_QTY_PATTERN = Pattern.compile(
        "(\\d+)\\s*(дерев|wood|бр[её]вн|б[её]вен|лес|дров|log)s?");

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
        String scope = matchingScope(prompt).toLowerCase();

        // Coal gathering keeps the exact requested resource so behavior tests and offline
        // play do not silently turn a coal request into generic iron mining.
        Matcher coal = COAL_PATTERN.matcher(scope);
        if (coal.matches()) {
            // Take the quantity that immediately precedes the resource word
            // (e.g. "gather 1 coal" / "добудь 3 угля"), NOT the first number
            // anywhere in the prompt - the situation block is full of
            // coordinates that would otherwise be mistaken for the quantity.
            int qty = 1;
            Matcher coalQty = COAL_QTY_PATTERN.matcher(scope);
            if (coalQty.find()) {
                try {
                    qty = Integer.parseInt(coalQty.group(1));
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
        Matcher wood = WOOD_PATTERN.matcher(scope);
        if (wood.matches()) {
            int qty = 50;
            Matcher woodQty = WOOD_QTY_PATTERN.matcher(scope);
            if (woodQty.find()) {
                try {
                    qty = Integer.parseInt(woodQty.group(1));
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

    /**
     * Returns the portion of the prompt that should be used for resource-specific
     * pattern matching: the tail after "CURRENT REQUEST:" if present, otherwise
     * the whole prompt. This prevents the situation block from triggering false
     * resource fallbacks (e.g. mentioning "coal_ore" in the situation while the
     * actual request is "gather 10 iron").
     *
     * @param prompt the original prompt
     * @return the scoped text to match against
     */
    private static String matchingScope(String prompt) {
        if (prompt == null) {
            return "";
        }
        int index = prompt.toLowerCase().indexOf(CURRENT_REQUEST_MARKER.toLowerCase());
        if (index < 0) {
            return prompt;
        }
        return prompt.substring(index + CURRENT_REQUEST_MARKER.length());
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
