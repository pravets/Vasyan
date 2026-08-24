package ru.pravets.vasyan.llm.resilience;

import ru.pravets.vasyan.llm.async.AsyncLLMClient;
import ru.pravets.vasyan.llm.async.LLMCache;
import ru.pravets.vasyan.llm.async.LLMException;
import ru.pravets.vasyan.llm.async.LLMResponse;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Supplier;

/**
 * Decorator that adds resilience patterns to an AsyncLLMClient.
 *
 * <p>Wraps any AsyncLLMClient implementation (OpenAI, Groq, Gemini) with
 * fault tolerance patterns from Resilience4j:</p>
 *
 * <ul>
 *   <li><b>Circuit Breaker:</b> Fail fast when provider is down</li>
 *   <li><b>Retry:</b> Automatic retry with exponential backoff</li>
 *   <li><b>Rate Limiter:</b> Prevent API quota exhaustion</li>
 *   <li><b>Bulkhead:</b> Limit concurrent requests</li>
 *   <li><b>Cache:</b> Response caching (40-60% hit rate)</li>
 *   <li><b>Fallback:</b> Pattern-based responses when all else fails</li>
 * </ul>
 *
 * <p><b>Design Pattern:</b> Decorator pattern - adds behavior without modifying original client</p>
 *
 * <p><b>Request Flow:</b></p>
 * <pre>
 * 1. Check cache → HIT: return cached response
 * 2. Check rate limiter → FULL: wait or reject
 * 3. Check bulkhead → FULL: wait or reject
 * 4. Check circuit breaker → OPEN: fallback
 * 5. Execute request with retry
 * 6. SUCCESS: cache response, return
 * 7. FAILURE: trigger fallback handler
 * </pre>
 *
 * <p><b>Usage Example:</b></p>
 * <pre>
 * AsyncLLMClient rawClient = new OpenAICompatibleClient(providerId, baseUrl, apiKey, model, maxTokens, temp, true, 60);
 * LLMCache cache = new LLMCache();
 * LLMFallbackHandler fallback = new LLMFallbackHandler();
 *
 * AsyncLLMClient resilientClient = new ResilientLLMClient(rawClient, cache, fallback);
 *
 * // Now all calls are protected by circuit breaker, retry, rate limiter, etc.
 * resilientClient.sendAsync("Build a house", params)
 *     .thenAccept(response -> processResponse(response));
 * </pre>
 *
 * @since 1.1.0
 */
public class ResilientLLMClient implements AsyncLLMClient {

    private static final Logger LOGGER = LoggerFactory.getLogger(ResilientLLMClient.class);

    private final AsyncLLMClient delegate;
    private final LLMCache cache;
    private final LLMFallbackHandler fallbackHandler;

    private final CircuitBreaker circuitBreaker;
    private final Retry retry;
    private final RateLimiter rateLimiter;
    private final Bulkhead bulkhead;

    /**
     * Constructs a ResilientLLMClient wrapping the given delegate.
     *
     * <p>Initializes all resilience patterns with provider-specific registries.</p>
     *
     * @param delegate        The underlying AsyncLLMClient to wrap
     * @param cache           Cache for storing responses
     * @param fallbackHandler Handler for fallback responses when all fails
     */
    public ResilientLLMClient(AsyncLLMClient delegate, LLMCache cache, LLMFallbackHandler fallbackHandler) {
        this.delegate = delegate;
        this.cache = cache;
        this.fallbackHandler = fallbackHandler;

        String providerId = delegate.getProviderId();
        LOGGER.info("Initializing resilient client for provider: {}", providerId);

        // Initialize resilience components with provider-specific names
        CircuitBreakerRegistry cbRegistry = CircuitBreakerRegistry.of(
            ResilienceConfig.createCircuitBreakerConfig());
        RetryRegistry retryRegistry = RetryRegistry.of(
            ResilienceConfig.createRetryConfig());
        RateLimiterRegistry rlRegistry = RateLimiterRegistry.of(
            ResilienceConfig.createRateLimiterConfig());
        BulkheadRegistry bhRegistry = BulkheadRegistry.of(
            ResilienceConfig.createBulkheadConfig());

        this.circuitBreaker = cbRegistry.circuitBreaker(providerId);
        this.retry = retryRegistry.retry(providerId);
        this.rateLimiter = rlRegistry.rateLimiter(providerId);
        this.bulkhead = bhRegistry.bulkhead(providerId);

        // Register event listeners for observability
        registerEventListeners(providerId);

        LOGGER.info("Resilient client initialized for provider: {} (circuit breaker: {}, retry: {}, rate limiter: {}, bulkhead: {})",
            providerId, circuitBreaker.getName(), retry.getName(), rateLimiter.getName(), bulkhead.getName());
    }

    /**
     * Registers event listeners for circuit breaker state changes and other events.
     *
     * @param providerId Provider ID for logging
     */
    private void registerEventListeners(String providerId) {
        // Circuit breaker state transitions
        circuitBreaker.getEventPublisher()
            .onStateTransition(event -> {
                LOGGER.warn("[{}] Circuit breaker state: {} -> {}",
                    providerId,
                    event.getStateTransition().getFromState(),
                    event.getStateTransition().getToState());
            });

        // Circuit breaker failures
        circuitBreaker.getEventPublisher()
            .onError(event -> {
                LOGGER.debug("[{}] Circuit breaker recorded error: {} (duration: {}ms)",
                    providerId,
                    event.getThrowable().getClass().getSimpleName(),
                    event.getElapsedDuration().toMillis());
            });

        // Retry events
        retry.getEventPublisher()
            .onRetry(event -> {
                LOGGER.warn("[{}] Retry attempt {} of {} after {} (reason: {})",
                    providerId,
                    event.getNumberOfRetryAttempts(),
                    ResilienceConfig.getRetryMaxAttempts(),
                    event.getWaitInterval(),
                    event.getLastThrowable() != null ? event.getLastThrowable().getMessage() : "unknown");
            });

        // Rate limiter events
        rateLimiter.getEventPublisher()
            .onFailure(event -> {
                LOGGER.warn("[{}] Rate limiter rejected request (limit: {} req/min)",
                    providerId,
                    ResilienceConfig.getRateLimitPerMinute());
            });

        // Bulkhead events
        bulkhead.getEventPublisher()
            .onCallRejected(event -> {
                LOGGER.warn("[{}] Bulkhead rejected request (max concurrent: {})",
                    providerId,
                    ResilienceConfig.getBulkheadMaxConcurrentCalls());
            });
    }

    @Override
    public CompletableFuture<LLMResponse> sendAsync(String prompt, Map<String, Object> params) {
        String model = (String) params.getOrDefault("model", "unknown");
        String providerId = delegate.getProviderId();

        // Step 1: Check cache first (fastest path)
        Optional<LLMResponse> cached = cache.get(prompt, model, providerId);
        if (cached.isPresent()) {
            LOGGER.debug("[{}] Cache hit for prompt (hash: {})", providerId, prompt.hashCode());
            return CompletableFuture.completedFuture(cached.get());
        }

        LOGGER.debug("[{}] Cache miss, executing request with resilience patterns", providerId);

        // Step 2: Execute with resilience patterns
        return executeWithResilience(prompt, params);
    }

    /**
     * Executes the request with all resilience patterns applied.
     *
     * @param prompt Request prompt
     * @param params Request parameters
     * @return CompletableFuture with response
     */
    private CompletableFuture<LLMResponse> executeWithResilience(String prompt, Map<String, Object> params) {
        String providerId = delegate.getProviderId();
        String model = (String) params.getOrDefault("model", "unknown");

        // Create supplier that wraps the async call
        Supplier<CompletableFuture<LLMResponse>> asyncSupplier = () -> delegate.sendAsync(prompt, params);

        // Apply resilience patterns in order: RateLimiter -> Bulkhead -> CircuitBreaker -> Retry
        // Each decorator wraps the previous one
        Supplier<CompletableFuture<LLMResponse>> decoratedSupplier = decorateWithResilience(asyncSupplier);

        try {
            return decoratedSupplier.get()
                .thenApply(response -> {
                    // Cache successful response
                    cache.put(prompt, model, providerId, response);
                    LOGGER.debug("[{}] Request successful, cached response (latency: {}ms, tokens: {})",
                        providerId, response.getLatencyMs(), response.getTokensUsed());
                    return response;
                })
                .exceptionally(throwable -> {
                    // Unwrap CompletionException if needed
                    Throwable cause = throwable instanceof CompletionException ?
                        throwable.getCause() : throwable;

                    LOGGER.error("[{}] Request failed after all retries, using fallback: {}: {}",
                        providerId, cause.getClass().getSimpleName(), cause.getMessage());

                    // Generate fallback response
                    return fallbackHandler.generateFallback(prompt, cause);
                });

        } catch (Exception e) {
            // Handle synchronous exceptions from rate limiter/bulkhead
            LOGGER.error("[{}] Request rejected by resilience layer: {}", providerId, e.getMessage());
            return CompletableFuture.completedFuture(fallbackHandler.generateFallback(prompt, e));
        }
    }

    /**
     * Decorates the supplier with all resilience patterns.
     *
     * <p><b>Order of decoration (innermost to outermost):</b></p>
     * <ol>
     *   <li>Retry (innermost) - retries on failure</li>
     *   <li>Circuit Breaker - fails fast if circuit is open</li>
     *   <li>Bulkhead - limits concurrent calls</li>
     *   <li>Rate Limiter (outermost) - limits call rate</li>
     * </ol>
     *
     * @param supplier Original supplier
     * @return Decorated supplier
     */
    private Supplier<CompletableFuture<LLMResponse>> decorateWithResilience(
            Supplier<CompletableFuture<LLMResponse>> supplier) {

        // Apply Retry
        Supplier<CompletableFuture<LLMResponse>> withRetry = Retry.decorateSupplier(retry, supplier);

        // Apply Circuit Breaker
        Supplier<CompletableFuture<LLMResponse>> withCircuitBreaker =
            CircuitBreaker.decorateSupplier(circuitBreaker, withRetry);

        // Apply Bulkhead
        Supplier<CompletableFuture<LLMResponse>> withBulkhead =
            Bulkhead.decorateSupplier(bulkhead, withCircuitBreaker);

        // Apply Rate Limiter
        Supplier<CompletableFuture<LLMResponse>> withRateLimiter =
            RateLimiter.decorateSupplier(rateLimiter, withBulkhead);

        return withRateLimiter;
    }

    @Override
    public String getProviderId() {
        return delegate.getProviderId();
    }

    @Override
    public boolean isHealthy() {
        // Client is healthy if circuit breaker is not OPEN
        return circuitBreaker.getState() != CircuitBreaker.State.OPEN;
    }

    /**
     * Returns the current circuit breaker state.
     *
     * @return Circuit breaker state (CLOSED, OPEN, or HALF_OPEN)
     */
    public CircuitBreaker.State getCircuitBreakerState() {
        return circuitBreaker.getState();
    }

    /**
     * Returns the circuit breaker metrics.
     *
     * @return Circuit breaker metrics (failure rate, call counts, etc.)
     */
    public CircuitBreaker.Metrics getCircuitBreakerMetrics() {
        return circuitBreaker.getMetrics();
    }

    /**
     * Returns the rate limiter metrics.
     *
     * @return Rate limiter metrics (available permissions, waiting threads)
     */
    public RateLimiter.Metrics getRateLimiterMetrics() {
        return rateLimiter.getMetrics();
    }

    /**
     * Returns the bulkhead metrics.
     *
     * @return Bulkhead metrics (available concurrent calls, max allowed)
     */
    public Bulkhead.Metrics getBulkheadMetrics() {
        return bulkhead.getMetrics();
    }

    /**
     * Manually transitions the circuit breaker to CLOSED state.
     *
     * <p><b>Warning:</b> Use with caution. Only for testing or manual recovery.</p>
     */
    public void resetCircuitBreaker() {
        circuitBreaker.reset();
        LOGGER.info("[{}] Circuit breaker manually reset to CLOSED", delegate.getProviderId());
    }
}
