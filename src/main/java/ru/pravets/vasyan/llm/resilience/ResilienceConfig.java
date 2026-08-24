package ru.pravets.vasyan.llm.resilience;

import ru.pravets.vasyan.llm.async.LLMException;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.core.IntervalFunction;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.TimeoutException;

/**
 * Configuration factory for Resilience4j fault tolerance patterns.
 *
 * <p>Provides pre-configured resilience patterns tuned for LLM API calls:</p>
 * <ul>
 *   <li><b>Circuit Breaker:</b> Fail fast when provider is experiencing issues</li>
 *   <li><b>Retry:</b> Automatic retry with exponential backoff for transient failures</li>
 *   <li><b>Rate Limiter:</b> Prevent API quota exhaustion</li>
 *   <li><b>Bulkhead:</b> Limit concurrent requests per provider</li>
 * </ul>
 *
 * <p><b>Why These Patterns?</b></p>
 * <ul>
 *   <li>Circuit Breaker: Prevents wasting time on failing providers, enables fast fallback</li>
 *   <li>Retry: Handles transient network glitches without user intervention</li>
 *   <li>Rate Limiter: Avoids expensive rate limit errors (HTTP 429)</li>
 *   <li>Bulkhead: Prevents thread pool exhaustion from slow providers</li>
 * </ul>
 *
 * <p><b>Used By:</b> {@link ResilientLLMClient}</p>
 *
 * @since 1.1.0
 */
public class ResilienceConfig {

    // Circuit Breaker thresholds
    private static final int CIRCUIT_BREAKER_SLIDING_WINDOW_SIZE = 10;
    private static final float CIRCUIT_BREAKER_FAILURE_RATE_THRESHOLD = 50.0f;
    private static final int CIRCUIT_BREAKER_WAIT_DURATION_SECONDS = 30;
    private static final int CIRCUIT_BREAKER_HALF_OPEN_CALLS = 3;

    // Retry configuration
    private static final int RETRY_MAX_ATTEMPTS = 3;
    private static final int RETRY_INITIAL_INTERVAL_MS = 1000; // 1s, 2s, 4s exponential backoff

    // Rate Limiter configuration
    private static final int RATE_LIMIT_PER_MINUTE = 10;
    private static final int RATE_LIMITER_TIMEOUT_SECONDS = 5;

    // Bulkhead configuration
    private static final int BULKHEAD_MAX_CONCURRENT_CALLS = 5;
    private static final int BULKHEAD_MAX_WAIT_DURATION_SECONDS = 10;

    /**
     * Creates circuit breaker configuration for LLM clients.
     *
     * <p><b>Circuit States:</b></p>
     * <ul>
     *   <li><b>CLOSED:</b> Normal operation, requests pass through</li>
     *   <li><b>OPEN:</b> Circuit tripped (50% failure rate in last 10 calls), requests rejected immediately</li>
     *   <li><b>HALF_OPEN:</b> After 30s wait, allows 3 test requests to check if provider recovered</li>
     * </ul>
     *
     * <p><b>Configuration:</b></p>
     * <ul>
     *   <li>Sliding window: 10 calls (count-based)</li>
     *   <li>Failure threshold: 50% (5 out of 10 failures → OPEN)</li>
     *   <li>Wait duration: 30 seconds before transitioning to HALF_OPEN</li>
     *   <li>Half-open calls: 3 test requests to determine if circuit should close</li>
     * </ul>
     *
     * <p><b>Recorded Exceptions (trigger circuit):</b></p>
     * <ul>
     *   <li>IOException (network failures)</li>
     *   <li>TimeoutException (slow responses)</li>
     *   <li>LLMException (provider errors)</li>
     * </ul>
     *
     * <p><b>Ignored Exceptions (don't trigger circuit):</b></p>
     * <ul>
     *   <li>IllegalArgumentException (client bugs, not provider issues)</li>
     * </ul>
     *
     * @return CircuitBreakerConfig instance
     */
    public static CircuitBreakerConfig createCircuitBreakerConfig() {
        return CircuitBreakerConfig.custom()
            .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
            .slidingWindowSize(CIRCUIT_BREAKER_SLIDING_WINDOW_SIZE)
            .failureRateThreshold(CIRCUIT_BREAKER_FAILURE_RATE_THRESHOLD)
            .waitDurationInOpenState(Duration.ofSeconds(CIRCUIT_BREAKER_WAIT_DURATION_SECONDS))
            .permittedNumberOfCallsInHalfOpenState(CIRCUIT_BREAKER_HALF_OPEN_CALLS)
            .recordExceptions(IOException.class, TimeoutException.class, LLMException.class)
            .ignoreExceptions(IllegalArgumentException.class)
            .build();
    }

    /**
     * Creates retry configuration with exponential backoff.
     *
     * <p><b>Retry Schedule:</b></p>
     * <ul>
     *   <li>Attempt 1: Immediate (no delay)</li>
     *   <li>Attempt 2: 1 second delay</li>
     *   <li>Attempt 3: 2 seconds delay</li>
     *   <li>Attempt 4: 4 seconds delay (if maxAttempts increased)</li>
     * </ul>
     *
     * <p><b>Total retry time:</b> ~7 seconds for 3 attempts</p>
     *
     * <p><b>Retryable Conditions:</b></p>
     * <ul>
     *   <li>IOException (network failures)</li>
     *   <li>TimeoutException (slow responses)</li>
     *   <li>LLMException with {@code isRetryable() == true}</li>
     * </ul>
     *
     * <p><b>Non-Retryable:</b></p>
     * <ul>
     *   <li>Circuit breaker OPEN</li>
     *   <li>Authentication errors</li>
     *   <li>Invalid request format</li>
     * </ul>
     *
     * @return RetryConfig instance
     */
    public static RetryConfig createRetryConfig() {
        return RetryConfig.custom()
            .maxAttempts(RETRY_MAX_ATTEMPTS)
            .intervalFunction(IntervalFunction.ofExponentialBackoff(RETRY_INITIAL_INTERVAL_MS, 2))
            .retryOnException(throwable -> {
                // Always retry IOException and TimeoutException
                if (throwable instanceof IOException || throwable instanceof TimeoutException) {
                    return true;
                }

                // For LLMException, check retryable flag
                if (throwable instanceof LLMException) {
                    return ((LLMException) throwable).isRetryable();
                }

                // Don't retry other exceptions
                return false;
            })
            .build();
    }

    /**
     * Creates rate limiter configuration to prevent API quota exhaustion.
     *
     * <p><b>Configuration:</b></p>
     * <ul>
     *   <li>Limit: 10 requests per minute per provider</li>
     *   <li>Timeout: Wait max 5 seconds for a permit</li>
     *   <li>Behavior: If no permit available after 5s, throw RateLimitExceededException</li>
     * </ul>
     *
     * <p><b>Why 10 req/min?</b></p>
     * <ul>
     *   <li>Conservative limit to prevent hitting provider rate limits</li>
     *   <li>OpenAI: 3 req/min (free), 60 req/min (paid) - 10 is safe middle ground</li>
     *   <li>Groq: 30 req/min (free tier)</li>
     *   <li>Gemini: 60 req/min</li>
     * </ul>
     *
     * <p><b>Tuning:</b> Increase limit if you have paid tier with higher quotas</p>
     *
     * @return RateLimiterConfig instance
     */
    public static RateLimiterConfig createRateLimiterConfig() {
        return RateLimiterConfig.custom()
            .limitRefreshPeriod(Duration.ofMinutes(1))
            .limitForPeriod(RATE_LIMIT_PER_MINUTE)
            .timeoutDuration(Duration.ofSeconds(RATE_LIMITER_TIMEOUT_SECONDS))
            .build();
    }

    /**
     * Creates bulkhead configuration to limit concurrent requests.
     *
     * <p><b>Configuration:</b></p>
     * <ul>
     *   <li>Max concurrent calls: 5 per provider</li>
     *   <li>Max wait duration: 10 seconds to acquire permission</li>
     *   <li>Behavior: If can't acquire permit after 10s, throw BulkheadFullException</li>
     * </ul>
     *
     * <p><b>Why Limit Concurrency?</b></p>
     * <ul>
     *   <li>Prevents thread pool exhaustion from slow providers</li>
     *   <li>Ensures one slow provider doesn't block other providers</li>
     *   <li>Matches thread pool size (5 threads per provider)</li>
     * </ul>
     *
     * <p><b>Example Scenario:</b></p>
     * <pre>
     * // If OpenAI is slow:
     * // - Max 5 concurrent OpenAI requests (bulkhead)
     * // - Groq and Gemini unaffected (separate bulkheads)
     * // - 6th OpenAI request waits up to 10s, then fails
     * </pre>
     *
     * @return BulkheadConfig instance
     */
    public static BulkheadConfig createBulkheadConfig() {
        return BulkheadConfig.custom()
            .maxConcurrentCalls(BULKHEAD_MAX_CONCURRENT_CALLS)
            .maxWaitDuration(Duration.ofSeconds(BULKHEAD_MAX_WAIT_DURATION_SECONDS))
            .build();
    }

    /**
     * Returns circuit breaker sliding window size for testing/monitoring.
     *
     * @return Sliding window size (number of calls)
     */
    public static int getCircuitBreakerSlidingWindowSize() {
        return CIRCUIT_BREAKER_SLIDING_WINDOW_SIZE;
    }

    /**
     * Returns circuit breaker failure rate threshold for testing/monitoring.
     *
     * @return Failure rate threshold (0.0 - 100.0)
     */
    public static float getCircuitBreakerFailureRateThreshold() {
        return CIRCUIT_BREAKER_FAILURE_RATE_THRESHOLD;
    }

    /**
     * Returns retry max attempts for testing/monitoring.
     *
     * @return Maximum retry attempts
     */
    public static int getRetryMaxAttempts() {
        return RETRY_MAX_ATTEMPTS;
    }

    /**
     * Returns the initial retry backoff interval in milliseconds.
     *
     * @return Initial backoff before the first retry
     */
    public static long getRetryInitialIntervalMs() {
        return RETRY_INITIAL_INTERVAL_MS;
    }

    /**
     * Returns rate limit per minute for testing/monitoring.
     *
     * @return Rate limit (requests per minute)
     */
    public static int getRateLimitPerMinute() {
        return RATE_LIMIT_PER_MINUTE;
    }

    /**
     * Returns bulkhead max concurrent calls for testing/monitoring.
     *
     * @return Maximum concurrent calls
     */
    public static int getBulkheadMaxConcurrentCalls() {
        return BULKHEAD_MAX_CONCURRENT_CALLS;
    }
}
