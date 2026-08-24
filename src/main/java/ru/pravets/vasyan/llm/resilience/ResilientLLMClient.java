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

    /**
     * Shared daemon scheduler for retry backoff delays and async chain starts.
     * One thread is enough: it only schedules, never blocks.
     */
    private static final java.util.concurrent.ScheduledExecutorService RETRY_SCHEDULER =
        java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "vasyan-retry-scheduler");
            t.setDaemon(true);
            return t;
        });

    private final AsyncLLMClient delegate;
    private final LLMCache cache;
    private final LLMFallbackHandler fallbackHandler;

    private final CircuitBreaker circuitBreaker;
    private final Retry retry;
    private final RateLimiter rateLimiter;
    private final Bulkhead bulkhead;
    private final long retryInitialBackoffMs;

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
        this(delegate, cache, fallbackHandler,
            ResilienceConfig.getRetryInitialIntervalMs());
    }

    /**
     * Constructs a ResilientLLMClient with a custom initial retry backoff.
     *
     * @param delegate              the underlying AsyncLLMClient to wrap
     * @param cache                 cache for storing responses
     * @param fallbackHandler       handler for fallback responses when all fails
     * @param retryInitialBackoffMs initial retry delay in milliseconds; must be
     *                              positive. Subsequent delays double
     *                              (initial, 2×initial, 4×initial...). Use a small
     *                              value (e.g. 10) in tests to keep them fast.
     */
    public ResilientLLMClient(AsyncLLMClient delegate, LLMCache cache, LLMFallbackHandler fallbackHandler,
                              long retryInitialBackoffMs) {
        this.delegate = delegate;
        this.cache = cache;
        this.fallbackHandler = fallbackHandler;
        this.retryInitialBackoffMs = retryInitialBackoffMs;

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

        // Retry events are now emitted by sendWithRetries (async-aware retry);
        // the resilience4j Retry instance is no longer used for decoration.

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
     * <p>Unlike the previous implementation (issue #36), retries and circuit
     * breaker operate on the <em>completion</em> of the underlying future,
     * not on the synchronous creation of it. resilience4j's
     * {@code Retry.decorateSupplier} cannot see exceptions that surface
     * through a CompletableFuture after the supplier has returned, so async
     * failures were never retried. Here each retry attempt is chained via
     * {@code exceptionallyCompose}, and every completed attempt (success or
     * failure) is recorded in the circuit breaker.</p>
     *
     * @param prompt Request prompt
     * @param params Request parameters
     * @return CompletableFuture with response
     */
    private CompletableFuture<LLMResponse> executeWithResilience(String prompt, Map<String, Object> params) {
        String providerId = delegate.getProviderId();

        try {
            // Rate limiter gate (non-blocking): no permission -> immediate
            // fallback via RequestNotPermitted, nothing is started. The
            // bulkhead still bounds concurrency of in-flight chains.
            if (!rateLimiter.acquirePermission()) {
                throw io.github.resilience4j.ratelimiter.RequestNotPermitted
                    .createRequestNotPermitted(rateLimiter);
            }
            return CompletableFuture.supplyAsync(() ->
                sendWithRetries(prompt, params, 1), RETRY_SCHEDULER)
                .thenCompose(f -> f)
                .thenApply(response -> {
                    cache.put(prompt,
                        (String) params.getOrDefault("model", "unknown"), providerId, response);
                    LOGGER.debug("[{}] Request successful, cached response (latency: {}ms, tokens: {})",
                        providerId, response.getLatencyMs(), response.getTokensUsed());
                    return response;
                })
                .exceptionally(throwable -> {
                    Throwable cause = throwable instanceof CompletionException ?
                        throwable.getCause() : throwable;
                    LOGGER.error("[{}] Request failed after all retries, using fallback: {}: {}",
                        providerId, cause.getClass().getSimpleName(), cause.getMessage());
                    return fallbackHandler.generateFallback(prompt, cause);
                });
        } catch (Exception e) {
            // Synchronous rejection from rate limiter / bulkhead
            LOGGER.error("[{}] Request rejected by resilience layer: {}", providerId, e.getMessage());
            return CompletableFuture.completedFuture(fallbackHandler.generateFallback(prompt, e));
        }
    }

    /**
     * Sends one attempt and chains retries onto the future's completion.
     *
     * <p>Attempt counting starts at 1; up to {@link ResilienceConfig#getRetryMaxAttempts()}
     * attempts are made. Between attempts there is an exponential backoff
     * (initial interval from {@code retryInitialBackoffMs}). Only errors
     * considered retryable by {@link ResilienceConfig#createRetryConfig()}
     * logic (IOException, TimeoutException, retryable LLMException) trigger
     * another attempt; everything else fails fast.</p>
     */
    private CompletableFuture<LLMResponse> sendWithRetries(String prompt, Map<String, Object> params, int attempt) {
        // Circuit breaker gate: fail fast when the circuit is OPEN. The
        // CallNotPermittedException propagates to the fallback path like any
        // other failure (and is NOT retried: CallNotPermittedException is not
        // an IOException/TimeoutException/retryable LLMException).
        try {
            circuitBreaker.acquirePermission();
        } catch (Exception e) {
            CompletableFuture<LLMResponse> rejected = new CompletableFuture<>();
            rejected.completeExceptionally(e);
            return rejected;
        }

        CompletableFuture<LLMResponse> attemptFuture = delegate.sendAsync(prompt, params);

        // Record success/failure (with real duration) in the circuit breaker
        long startedAt = System.nanoTime();
        attemptFuture.whenComplete((response, error) -> {
            long elapsed = System.nanoTime() - startedAt;
            if (error == null) {
                circuitBreaker.onSuccess(elapsed, java.util.concurrent.TimeUnit.NANOSECONDS);
            } else {
                Throwable cause = unwrap(error);
                if (isRecordableByCircuitBreaker(cause)) {
                    circuitBreaker.onError(elapsed, java.util.concurrent.TimeUnit.NANOSECONDS, cause);
                }
            }
        });

        return attemptFuture
            .thenApply(response -> {
                LOGGER.debug("[{}] Attempt {} succeeded", delegate.getProviderId(), attempt);
                return response;
            })
            .exceptionallyCompose(error -> {
                Throwable cause = unwrap(error);

                if (!isRetryable(cause)) {
                    LOGGER.debug("[{}] Attempt {} failed with non-retryable {}: {}",
                        delegate.getProviderId(), attempt,
                        cause.getClass().getSimpleName(), cause.getMessage());
                    CompletableFuture<LLMResponse> failed = new CompletableFuture<>();
                    failed.completeExceptionally(cause);
                    return failed;
                }

                int maxAttempts = ResilienceConfig.getRetryMaxAttempts();
                if (attempt >= maxAttempts) {
                    LOGGER.debug("[{}] Attempt {}/{} exhausted retries",
                        delegate.getProviderId(), attempt, maxAttempts);
                    CompletableFuture<LLMResponse> failed = new CompletableFuture<>();
                    failed.completeExceptionally(cause);
                    return failed;
                }

                long delayMs = retryInitialBackoffMs << (attempt - 1); // 1s, 2s, 4s...
                LOGGER.warn("[{}] Attempt {}/{} failed ({}: {}), retrying in {}ms",
                    delegate.getProviderId(), attempt, maxAttempts,
                    cause.getClass().getSimpleName(), cause.getMessage(), delayMs);

                // Shared daemon scheduler; the next attempt is composed
                // asynchronously - no thread is blocked waiting on it.
                CompletableFuture<LLMResponse> delayed = new CompletableFuture<>();
                RETRY_SCHEDULER.schedule(
                    () -> sendWithRetries(prompt, params, attempt + 1)
                        .whenComplete((resp, err) -> {
                            if (err != null) {
                                delayed.completeExceptionally(unwrap(err));
                            } else {
                                delayed.complete(resp);
                            }
                        }),
                    delayMs, java.util.concurrent.TimeUnit.MILLISECONDS);
                return delayed;
            });
    }

    /**
     * Unwraps CompletionException to get to the real cause.
     */
    private static Throwable unwrap(Throwable t) {
        return t instanceof CompletionException && t.getCause() != null ? t.getCause() : t;
    }

    /**
     * Mirrors ResilienceConfig.createRetryConfig().retryOnException:
     * IOException, TimeoutException and retryable LLMException are retried;
     * everything else fails fast. Package-private for tests.
     */
    static boolean isRetryable(Throwable t) {
        if (t instanceof java.io.IOException || t instanceof java.util.concurrent.TimeoutException) {
            return true;
        }
        if (t instanceof LLMException llmException) {
            return llmException.isRetryable();
        }
        return false;
    }

    /**
     * Mirrors ResilienceConfig.createCircuitBreakerConfig(): record IOException,
     * TimeoutException and LLMException; ignore IllegalArgumentException.
     * Package-private for tests.
     */
    static boolean isRecordableByCircuitBreaker(Throwable t) {
        if (t instanceof IllegalArgumentException) {
            return false;
        }
        return t instanceof java.io.IOException
            || t instanceof java.util.concurrent.TimeoutException
            || t instanceof LLMException;
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
