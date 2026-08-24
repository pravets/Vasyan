package ru.pravets.vasyan.llm.async;

import java.util.Objects;

/**
 * Immutable value object representing a response from an LLM (Large Language Model).
 *
 * <p>Contains the response content along with metadata for observability and debugging:
 * latency tracking, token usage (for cost monitoring), provider identification,
 * and cache status.</p>
 *
 * <p><b>Design Pattern:</b> Builder pattern for flexible construction</p>
 * <p><b>Immutability:</b> All fields are final; thread-safe by design</p>
 *
 * <p><b>Usage Example:</b></p>
 * <pre>
 * LLMResponse response = LLMResponse.builder()
 *     .content("{\"action\":\"mine\",\"parameters\":{\"block\":\"iron_ore\"}}")
 *     .model("gpt-3.5-turbo")
 *     .providerId("openai")
 *     .tokensUsed(150)
 *     .latencyMs(1234)
 *     .fromCache(false)
 *     .build();
 *
 * System.out.println("Latency: " + response.getLatencyMs() + "ms");
 * System.out.println("Cost: " + (response.getTokensUsed() * 0.000002) + " USD");
 * </pre>
 *
 * @since 1.1.0
 */
public class LLMResponse {

    private final String content;
    private final String model;
    private final int tokensUsed;
    private final long latencyMs;
    private final String providerId;
    private final boolean fromCache;

    /**
     * Human-readable failure reason when this response was produced by the
     * fallback handler (null for real LLM responses). Examples:
     * "HttpTimeoutException: request timed out", "ConnectException: refused".
     */
    private final String failureReason;

    private LLMResponse(Builder builder) {
        this.content = Objects.requireNonNull(builder.content, "content cannot be null");
        this.model = Objects.requireNonNull(builder.model, "model cannot be null");
        this.providerId = Objects.requireNonNull(builder.providerId, "providerId cannot be null");
        this.tokensUsed = builder.tokensUsed;
        this.latencyMs = builder.latencyMs;
        this.fromCache = builder.fromCache;
        this.failureReason = builder.failureReason;
    }

    /**
     * Returns why this response came from fallback, or null for real LLM responses.
     *
     * @return short error description ("TIMEOUT", "CONNECTION", "HTTP 500", ...) or null
     */
    public String getFailureReason() {
        return failureReason;
    }

    /**
     * Returns the LLM-generated text content.
     *
     * @return The response content (never null)
     */
    public String getContent() {
        return content;
    }

    /**
     * Returns the model name that generated this response.
     *
     * @return Model name (e.g., "gpt-3.5-turbo", "llama3-70b", "gemini-1.5-flash")
     */
    public String getModel() {
        return model;
    }

    /**
     * Returns the total number of tokens used (prompt + completion).
     *
     * <p>Used for cost tracking. Pricing varies by provider:
     * <ul>
     *   <li>OpenAI GPT-3.5: $0.002 per 1K tokens</li>
     *   <li>Groq: Free tier, then $0.00027 per 1K tokens</li>
     *   <li>Gemini: $0.00025 per 1K tokens</li>
     * </ul></p>
     *
     * @return Total tokens used, or 0 if unavailable
     */
    public int getTokensUsed() {
        return tokensUsed;
    }

    /**
     * Returns the end-to-end latency in milliseconds.
     *
     * <p>Measured from request initiation to response receipt.
     * Does NOT include time spent waiting in rate limiter or bulkhead queues.</p>
     *
     * @return Latency in milliseconds
     */
    public long getLatencyMs() {
        return latencyMs;
    }

    /**
     * Returns the provider ID that generated this response.
     *
     * @return Provider ID ("openai", "groq", "gemini", or "fallback")
     */
    public String getProviderId() {
        return providerId;
    }

    /**
     * Returns whether this response was served from cache.
     *
     * <p>Cache hits save API calls and reduce latency. Monitor cache hit rate
     * for optimization opportunities.</p>
     *
     * @return true if from cache, false if fresh API call
     */
    public boolean isFromCache() {
        return fromCache;
    }

    /**
     * Creates a copy of this response with fromCache flag set to true.
     *
     * <p>Used by LLMCache when storing responses.</p>
     *
     * @param cacheFlag The new cache flag value
     * @return A new LLMResponse instance with updated cache flag
     */
    public LLMResponse withCacheFlag(boolean cacheFlag) {
        return new Builder()
            .content(this.content)
            .model(this.model)
            .providerId(this.providerId)
            .tokensUsed(this.tokensUsed)
            .latencyMs(this.latencyMs)
            .fromCache(cacheFlag)
            .failureReason(this.failureReason)
            .build();
    }

    /**
     * Creates a new Builder for constructing LLMResponse instances.
     *
     * @return A new Builder instance
     */
    public static Builder builder() {
        return new Builder();
    }

    @Override
    public String toString() {
        return "LLMResponse{" +
            "providerId='" + providerId + '\'' +
            ", model='" + model + '\'' +
            ", tokensUsed=" + tokensUsed +
            ", latencyMs=" + latencyMs +
            ", fromCache=" + fromCache +
            ", contentLength=" + (content != null ? content.length() : 0) +
            '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        LLMResponse that = (LLMResponse) o;
        return tokensUsed == that.tokensUsed &&
            latencyMs == that.latencyMs &&
            fromCache == that.fromCache &&
            Objects.equals(content, that.content) &&
            Objects.equals(model, that.model) &&
            Objects.equals(providerId, that.providerId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(content, model, tokensUsed, latencyMs, providerId, fromCache);
    }

    /**
     * Builder for constructing LLMResponse instances.
     *
     * <p><b>Thread Safety:</b> Not thread-safe. Each thread should use its own Builder instance.</p>
     */
    public static class Builder {
        private String content;
        private String model;
        private int tokensUsed;
        private long latencyMs;
        private String providerId;
        private boolean fromCache;
        private String failureReason;

        /**
         * Sets the failure reason for fallback responses (null = real LLM response).
         *
         * @param failureReason Short error description, e.g. "TIMEOUT after 60s"
         * @return This builder for method chaining
         */
        public Builder failureReason(String failureReason) {
            this.failureReason = failureReason;
            return this;
        }

        /**
         * Sets the response content.
         *
         * @param content The LLM-generated text (required)
         * @return This builder for method chaining
         */
        public Builder content(String content) {
            this.content = content;
            return this;
        }

        /**
         * Sets the model name.
         *
         * @param model The model that generated the response (required)
         * @return This builder for method chaining
         */
        public Builder model(String model) {
            this.model = model;
            return this;
        }

        /**
         * Sets the tokens used.
         *
         * @param tokensUsed Total tokens consumed (prompt + completion)
         * @return This builder for method chaining
         */
        public Builder tokensUsed(int tokensUsed) {
            this.tokensUsed = tokensUsed;
            return this;
        }

        /**
         * Sets the latency.
         *
         * @param latencyMs End-to-end latency in milliseconds
         * @return This builder for method chaining
         */
        public Builder latencyMs(long latencyMs) {
            this.latencyMs = latencyMs;
            return this;
        }

        /**
         * Sets the provider ID.
         *
         * @param providerId Provider identifier (required, e.g., "openai", "groq", "gemini")
         * @return This builder for method chaining
         */
        public Builder providerId(String providerId) {
            this.providerId = providerId;
            return this;
        }

        /**
         * Sets whether the response came from cache.
         *
         * @param fromCache true if cached, false if fresh API call
         * @return This builder for method chaining
         */
        public Builder fromCache(boolean fromCache) {
            this.fromCache = fromCache;
            return this;
        }

        /**
         * Builds and returns the LLMResponse instance.
         *
         * @return A new LLMResponse
         * @throws NullPointerException if content, model, or providerId are null
         */
        public LLMResponse build() {
            return new LLMResponse(this);
        }
    }
}
