package ru.pravets.vasyan.llm.resilience;

import org.junit.jupiter.api.Test;
import ru.pravets.vasyan.llm.async.AsyncLLMClient;
import ru.pravets.vasyan.llm.async.LLMCache;
import ru.pravets.vasyan.llm.async.LLMResponse;

import java.io.IOException;
import java.net.http.HttpTimeoutException;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies that the resilience layer actually retries ASYNC failures.
 *
 * <p>Regression test for issue #36: resilience4j {@code Retry.decorateSupplier}
 * only sees the synchronous creation of the CompletableFuture - the supplier
 * returns immediately with a future object, so async exceptions never trigger
 * a retry. These tests pin the fixed behavior: failures that surface through
 * the future MUST be retried.</p>
 */
class ResilientLLMClientAsyncRetryTest {

    /** Delegate whose first N sendAsync calls complete exceptionally. */
    private static final class FlakyAsyncClient implements AsyncLLMClient {
        private final int failuresBeforeSuccess;
        private final Throwable failure;
        private final AtomicInteger attempts = new AtomicInteger();

        FlakyAsyncClient(int failuresBeforeSuccess, Throwable failure) {
            this.failuresBeforeSuccess = failuresBeforeSuccess;
            this.failure = failure;
        }

        @Override
        public CompletableFuture<LLMResponse> sendAsync(String prompt, Map<String, Object> params) {
            int attempt = attempts.incrementAndGet();
            if (attempt <= failuresBeforeSuccess) {
                CompletableFuture<LLMResponse> failed = new CompletableFuture<>();
                failed.completeExceptionally(failure);
                return failed;
            }
            return CompletableFuture.completedFuture(LLMResponse.builder()
                .content("ok").model("test-model").providerId("flaky")
                .latencyMs(1).tokensUsed(0).fromCache(false).build());
        }

        @Override
        public String getProviderId() {
            return "flaky";
        }

        @Override
        public boolean isHealthy() {
            return true;
        }
    }

    private ResilientLLMClient client(AsyncLLMClient delegate) {
        // Fast backoff so the test does not sleep seconds
        return new ResilientLLMClient(delegate, new LLMCache(), new LLMFallbackHandler(), 10);
    }

    @Test
    void asyncFailureIsRetriedAndEventuallySucceeds() throws Exception {
        FlakyAsyncClient delegate = new FlakyAsyncClient(2,
            new IOException("transient network glitch"));

        LLMResponse response = client(delegate).sendAsync("hello", Map.of()).get();

        assertEquals("ok", response.getContent());
        assertEquals(3, delegate.attempts.get(),
            "Two failed futures + one success = 3 attempts");
    }

    @Test
    void asyncTimeoutIsRetried() throws Exception {
        FlakyAsyncClient delegate = new FlakyAsyncClient(1,
            new HttpTimeoutException("request did not finish in time"));

        LLMResponse response = client(delegate).sendAsync("hello", Map.of()).get();

        assertEquals("ok", response.getContent());
        assertEquals(2, delegate.attempts.get(), "Timeout must be retried");
    }

    @Test
    void nonRetryableErrorIsNotRetried() throws Exception {
        FlakyAsyncClient delegate = new FlakyAsyncClient(99,
            new IllegalArgumentException("bad request, retrying is pointless"));

        // Fallback turns any failure into a successful response, so the
        // contract is: single attempt, then a fallback answer.
        LLMResponse response = client(delegate).sendAsync("hello", Map.of()).get();

        assertTrue(response.getContent().contains("[Fallback]"),
            "Non-retryable failure must end in fallback");
        assertEquals(1, delegate.attempts.get(),
            "Non-retryable error must fail fast without further attempts");
    }

    @Test
    void exhaustedRetriesStopAtMaxAttempts() throws Exception {
        FlakyAsyncClient delegate = new FlakyAsyncClient(Integer.MAX_VALUE,
            new IOException("always down"));

        LLMResponse response = client(delegate).sendAsync("hello", Map.of()).get();

        assertTrue(response.getContent().contains("[Fallback]"),
            "Exhausted retries must end in fallback");
        assertEquals(ResilienceConfig.getRetryMaxAttempts(), delegate.attempts.get(),
            "Exactly maxAttempts tries, no more");
    }

    @Test
    void retryablePredicatesMatchConfigRules() {
        // IOException / TimeoutException -> retryable, recordable
        assertTrue(ResilientLLMClient.isRetryable(new IOException("io")));
        assertTrue(ResilientLLMClient.isRetryable(
            new java.net.http.HttpTimeoutException("slow")));
        assertTrue(ResilientLLMClient.isRecordableByCircuitBreaker(new IOException("io")));

        // Retryable vs non-retryable LLMException
        assertTrue(ResilientLLMClient.isRetryable(new ru.pravets.vasyan.llm.async.LLMException(
            "server blew up", ru.pravets.vasyan.llm.async.LLMException.ErrorType.SERVER_ERROR,
            "p", true)));
        assertFalse(ResilientLLMClient.isRetryable(new ru.pravets.vasyan.llm.async.LLMException(
            "bad key", ru.pravets.vasyan.llm.async.LLMException.ErrorType.AUTH_ERROR,
            "p", false)));

        // IllegalArgumentException -> not retried, not recorded by CB
        assertFalse(ResilientLLMClient.isRetryable(new IllegalArgumentException("bad")));
        assertFalse(ResilientLLMClient.isRecordableByCircuitBreaker(new IllegalArgumentException("bad")));

        // Unknown exceptions -> neither retried nor recorded
        RuntimeException mystery = new IllegalStateException("???");
        assertFalse(ResilientLLMClient.isRetryable(mystery));
        assertFalse(ResilientLLMClient.isRecordableByCircuitBreaker(mystery));
    }
}
