package ru.pravets.vasyan.llm.async;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import ru.pravets.vasyan.llm.LLMProviders;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Universal OpenAI-compatible Chat Completions client.
 *
 * <p>Covers all providers that speak the OpenAI Chat Completions protocol:
 * OpenAI, Groq, Google Gemini (via its /v1beta/openai endpoint), Ollama,
 * LM Studio, OpenCode Go and any custom endpoint.</p>
 *
 * <p>Async core (sendAsync) plus a blocking wrapper (sendRequest) for the
 * legacy sync path. Thread-safe: HttpClient is immutable.</p>
 */
public class OpenAICompatibleClient implements AsyncLLMClient {

    private static final Logger LOGGER = LoggerFactory.getLogger(OpenAICompatibleClient.class);

    private final HttpClient httpClient;
    private final String providerId;
    private final String baseUrl;
    private final String apiKey;
    private final String model;
    private final int maxTokens;
    private final double temperature;
    private final boolean jsonMode;
    private final Duration timeout;

    public OpenAICompatibleClient(String providerId, String baseUrl, String apiKey, String model,
                                  int maxTokens, double temperature, boolean jsonMode, int timeoutSeconds) {
        this.providerId = providerId;
        // Strip trailing slash so that baseUrl + "/chat/completions" never
        // produces a double-slash (tokenra preset has https://tokenra.io/v1/)
        this.baseUrl = baseUrl != null && baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.apiKey = apiKey;
        this.model = model;
        this.maxTokens = maxTokens;
        this.temperature = temperature;
        this.jsonMode = jsonMode;
        this.timeout = Duration.ofSeconds(timeoutSeconds);

        // The per-request timeout (connect + response) is applied on each HttpRequest
        // via .timeout(timeout); the builder-level connectTimeout covers TCP handshake.
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    }

    /**
     * Builds a client for the given provider preset using the resolved values.
     * Convenience factory for TaskPlanner.
     */
    public static OpenAICompatibleClient forProvider(String providerId, String baseUrlOverride, String apiKey,
                                                     String modelOverride, int maxTokens, double temperature,
                                                     boolean jsonMode, int timeoutSeconds) {
        String resolvedBase = LLMProviders.resolveBaseUrl(providerId, baseUrlOverride);
        String resolvedModel = LLMProviders.resolveModel(providerId, modelOverride);
        return new OpenAICompatibleClient(providerId, resolvedBase, apiKey, resolvedModel,
            maxTokens, temperature, jsonMode, timeoutSeconds);
    }

    @Override
    public CompletableFuture<LLMResponse> sendAsync(String prompt, Map<String, Object> params) {
        long startTime = System.currentTimeMillis();

        String requestBody = buildRequestBody(prompt, params);
        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + "/chat/completions"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(requestBody))
            .timeout(timeout);

        if (hasApiKey()) {
            requestBuilder.header("Authorization", "Bearer " + apiKey);
        }

        HttpRequest request = requestBuilder.build();

        LOGGER.debug("[{}] Sending async request (prompt length: {} chars)", providerId, prompt.length());

        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
            .thenApply(response -> {
                long latencyMs = System.currentTimeMillis() - startTime;

                if (response.statusCode() != 200) {
                    LLMException.ErrorType errorType = determineErrorType(response.statusCode());
                    boolean retryable = response.statusCode() == 429 || response.statusCode() >= 500;

                    LOGGER.error("[{}] API error: status={}, body={}", providerId,
                        response.statusCode(), truncate(response.body(), 200));

                    throw new LLMException(
                        "[" + providerId + "] API error: HTTP " + response.statusCode(),
                        errorType, providerId, retryable);
                }

                return parseResponse(response.body(), latencyMs);
            });
    }

    /**
     * Blocking variant used by the legacy sync path. Blocks up to the configured timeout.
     */
    public LLMResponse sendRequest(String systemPrompt, String userPrompt) {
        try {
            Map<String, Object> params = Map.of(
                "systemPrompt", systemPrompt,
                "model", model,
                "maxTokens", maxTokens,
                "temperature", temperature
            );
            return sendAsync(userPrompt, params).get(timeout.toSeconds(), java.util.concurrent.TimeUnit.SECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            LOGGER.error("[{}] Request timed out after {}s", providerId, timeout.toSeconds());
            throw new LLMException("[" + providerId + "] Request timed out", LLMException.ErrorType.TIMEOUT,
                providerId, true, e);
        } catch (Exception e) {
            if (e instanceof LLMException) {
                throw (LLMException) e;
            }
            LOGGER.error("[{}] Request failed", providerId, e);
            throw new LLMException("[" + providerId + "] Request failed: " + e.getMessage(),
                LLMException.ErrorType.CLIENT_ERROR, providerId, true, e);
        }
    }

    /**
     * Health check: GET {baseUrl}/models with a short timeout.
     * Used by /vasyan providers and diagnostics.
     */
    public boolean checkHealth() {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/models"))
                .timeout(Duration.ofSeconds(3))
                .GET()
                .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            boolean healthy = response.statusCode() == 200;
            if (!healthy) {
                LOGGER.warn("[{}] Health check failed: HTTP {}", providerId, response.statusCode());
            }
            return healthy;
        } catch (Exception e) {
            LOGGER.warn("[{}] Health check failed ({}): {} against {}",
                providerId, e.getClass().getSimpleName(),
                e.getMessage() != null ? e.getMessage() : "no message", baseUrl);
            return false;
        }
    }

    String buildRequestBody(String prompt, Map<String, Object> params) {
        String modelToUse = (String) params.getOrDefault("model", model);
        int maxTokensToUse = (int) params.getOrDefault("maxTokens", maxTokens);
        double tempToUse = (double) params.getOrDefault("temperature", temperature);

        JsonObject body = new JsonObject();
        if (modelToUse != null && !modelToUse.isEmpty()) {
            body.addProperty("model", modelToUse);
        }
        body.addProperty("max_tokens", maxTokensToUse);
        body.addProperty("temperature", tempToUse);

        if (jsonMode) {
            JsonObject responseFormat = new JsonObject();
            responseFormat.addProperty("type", "json_object");
            body.add("response_format", responseFormat);
        }

        JsonArray messages = new JsonArray();

        String systemPrompt = (String) params.get("systemPrompt");
        if (systemPrompt != null && !systemPrompt.isEmpty()) {
            JsonObject systemMessage = new JsonObject();
            systemMessage.addProperty("role", "system");
            systemMessage.addProperty("content", systemPrompt);
            messages.add(systemMessage);
        }

        JsonObject userMessage = new JsonObject();
        userMessage.addProperty("role", "user");
        userMessage.addProperty("content", prompt);
        messages.add(userMessage);

        body.add("messages", messages);

        return body.toString();
    }

    private LLMResponse parseResponse(String responseBody, long latencyMs) {
        try {
            JsonObject json = JsonParser.parseString(responseBody).getAsJsonObject();

            if (!json.has("choices") || json.getAsJsonArray("choices").isEmpty()) {
                throw new LLMException(
                    "[" + providerId + "] Response missing 'choices' array",
                    LLMException.ErrorType.INVALID_RESPONSE, providerId, false);
            }

            JsonObject firstChoice = json.getAsJsonArray("choices").get(0).getAsJsonObject();
            JsonObject message = firstChoice.getAsJsonObject("message");
            String content = message.get("content").getAsString();

            int tokensUsed = 0;
            if (json.has("usage")) {
                JsonObject usage = json.getAsJsonObject("usage");
                tokensUsed = usage.get("total_tokens").getAsInt();
            }

            LOGGER.debug("[{}] Response received (latency: {}ms, tokens: {})", providerId, latencyMs, tokensUsed);

            return LLMResponse.builder()
                .content(content)
                .model(model)
                .providerId(providerId)
                .latencyMs(latencyMs)
                .tokensUsed(tokensUsed)
                .fromCache(false)
                .build();

        } catch (LLMException e) {
            throw e;
        } catch (Exception e) {
            LOGGER.error("[{}] Failed to parse response: {}", providerId, truncate(responseBody, 200), e);
            throw new LLMException("[" + providerId + "] Failed to parse response: " + e.getMessage(),
                LLMException.ErrorType.INVALID_RESPONSE, providerId, false, e);
        }
    }

    private LLMException.ErrorType determineErrorType(int statusCode) {
        return switch (statusCode) {
            case 429 -> LLMException.ErrorType.RATE_LIMIT;
            case 401, 403 -> LLMException.ErrorType.AUTH_ERROR;
            case 400 -> LLMException.ErrorType.CLIENT_ERROR;
            case 408 -> LLMException.ErrorType.TIMEOUT;
            default -> statusCode >= 500 ? LLMException.ErrorType.SERVER_ERROR
                                         : LLMException.ErrorType.CLIENT_ERROR;
        };
    }

    private String truncate(String str, int maxLength) {
        if (str == null) return "[null]";
        if (str.length() <= maxLength) return str;
        return str.substring(0, maxLength) + "...";
    }

    @Override
    public String getProviderId() {
        return providerId;
    }

    @Override
    public boolean isHealthy() {
        return true; // resilience layer tracks circuit breaker state
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public String getModel() {
        return model;
    }

    public boolean hasApiKey() {
        return apiKey != null && !apiKey.isEmpty();
    }
}
