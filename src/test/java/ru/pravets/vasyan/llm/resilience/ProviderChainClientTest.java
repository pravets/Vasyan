package ru.pravets.vasyan.llm.resilience;

import ru.pravets.vasyan.llm.async.LLMResponse;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link ProviderChainClient} failover semantics (issue #33).
 *
 * <p>Uses hand-rolled fakes instead of Mockito: deterministic, no proxying
 * of CompletableFuture chains.</p>
 */
class ProviderChainClientTest {

    /** Fake member: fails (fallback response or exception) N times, then succeeds. */
    private static class FakeMember implements ru.pravets.vasyan.llm.async.AsyncLLMClient {
        private final String id;
        /** Responses to hand out in order; when empty, hands out success forever. */
        private final Deque<Object> script = new ArrayDeque<>();
        int attempts;
        /** Params of the most recent sendAsync call (for override assertions). */
        volatile Map<String, Object> lastParams;

        FakeMember(String id) {
            this.id = id;
        }

        /** Queue a fallback response (counts as unusable by the chain). */
        FakeMember thenFallback() {
            script.add("fallback");
            return this;
        }

        /** Queue an exception. */
        FakeMember thenError() {
            script.add(new RuntimeException("boom: " + id));
            return this;
        }

        /** Queue a real success response. */
        FakeMember thenSuccess() {
            script.add("ok");
            return this;
        }

        @Override
        public CompletableFuture<LLMResponse> sendAsync(String prompt, Map<String, Object> params) {
            attempts++;
            lastParams = params;
            Object next = script.isEmpty() ? "ok" : script.poll();
            if (next instanceof String kind) {
                LLMResponse.Builder builder = LLMResponse.builder()
                    .content("answer from " + id)
                    .model("fake")
                    .latencyMs(1)
                    .tokensUsed(1)
                    .fromCache(false);
                if ("fallback".equals(kind)) {
                    builder.content("[Fallback] pattern").providerId(ProviderChainClient.FALLBACK_PROVIDER_ID);
                } else {
                    builder.providerId(id);
                }
                return CompletableFuture.completedFuture(builder.build());
            }
            return CompletableFuture.failedFuture((Throwable) next);
        }

        @Override
        public String getProviderId() {
            return id;
        }

        @Override
        public boolean isHealthy() {
            return true; // never circuit-breaker OPEN in these tests
        }
    }

    private static LLMResponse sendAndGet(ProviderChainClient chain) throws Exception {
        return chain.sendAsync("prompt", Map.of()).get();
    }

    @Test
    void firstFailureFailsOverToNextMemberWithinSameRequest() throws Exception {
        FakeMember head = new FakeMember("head").thenError();
        FakeMember backup = new FakeMember("backup");
        List<String> switches = new java.util.ArrayList<>();

        ProviderChainClient chain = new ProviderChainClient(
            List.of(head, backup), switches::add, 60);

        LLMResponse response = sendAndGet(chain);

        assertEquals("backup", response.getProviderId());
        assertEquals(1, head.attempts);
        assertEquals(1, backup.attempts);
        assertEquals(1, switches.size(), "exactly one switch notification");
        assertTrue(switches.get(0).contains("backup"));
    }

    @Test
    void fallbackResponseCountsAsMemberFailure() throws Exception {
        // Head answers with a pattern-fallback -> chain must move on.
        FakeMember head = new FakeMember("head").thenFallback();
        FakeMember backup = new FakeMember("backup");
        ProviderChainClient chain = new ProviderChainClient(List.of(head, backup), null, 60);

        LLMResponse response = sendAndGet(chain);

        assertEquals("backup", response.getProviderId(),
            "unusable fallback answer must trigger the next member");
        assertEquals(1, head.attempts, "head attempted exactly once for this request");
    }

    @Test
    void recoversToHeadAfterCooldownElapses() throws Exception {
        FakeMember head = new FakeMember("head").thenError().thenSuccess();
        FakeMember backup = new FakeMember("backup");
        List<String> switches = new java.util.ArrayList<>();

        // 1-second cooldown so the test stays fast but deterministic enough.
        ProviderChainClient chain = new ProviderChainClient(
            List.of(head, backup), switches::add, 1);
        long startNanos = System.nanoTime();

        assertEquals("backup", sendAndGet(chain).getProviderId()); // failover
        awaitCooldownElapsed(startNanos, 1100);
        assertEquals("head", sendAndGet(chain).getProviderId());   // recovery probe succeeds

        assertEquals(2, switches.size(), "failover + failback notifications");
        assertTrue(switches.get(1).contains("head"));
    }


    /**
     * Waits (polling, no fixed sleep) until at least {@code millis} have
     * passed since {@code start} - a CI-friendly cooldown lapse: checks time
     * in small steps instead of one brittle Thread.sleep.
     */
    private static void awaitCooldownElapsed(long startNanos, long millis) throws InterruptedException {
        long deadline = startNanos + millis * 1_000_000L;
        while (System.nanoTime() < deadline) {
            Thread.sleep(25); // small slices; total wait ~= millis
        }
    }

    /** Member that always errors - models a permanently dead provider. */
    private static final class AlwaysDeadMember extends FakeMember {
        AlwaysDeadMember(String id) {
            super(id);
        }

        @Override
        public CompletableFuture<LLMResponse> sendAsync(String prompt, Map<String, Object> params) {
            attempts++;
            return CompletableFuture.failedFuture(new RuntimeException("still down"));
        }
    }

    @Test
    void cooldownPreventsRetryStormAgainstDeadHead() throws Exception {
        AlwaysDeadMember deadHead = new AlwaysDeadMember("dead");
        FakeMember backup = new FakeMember("backup");

        ProviderChainClient chain = new ProviderChainClient(
            List.of(deadHead, backup), null, 3600); // long cooldown

        assertEquals("backup", sendAndGet(chain).getProviderId());
        assertEquals("backup", sendAndGet(chain).getProviderId());
        assertEquals("backup", sendAndGet(chain).getProviderId());

        assertEquals(1, deadHead.attempts,
            "dead head inside cooldown must not be retried on every request");
    }

    @Test
    void allMembersDeadReturnsSyntheticFallbackResponse() {
        FakeMember a = new FakeMember("a").thenError();
        FakeMember b = new FakeMember("b").thenError();

        ProviderChainClient chain = new ProviderChainClient(List.of(a, b), null, 60);

        CompletableFuture<LLMResponse> future = chain.sendAsync("p", Map.of());
        LLMResponse response = assertDoesNotThrow(() -> future.get(),
            "all-dead must still complete normally (no NPE downstream)");

        // Single-provider contract: caller NEVER receives null. When members
        // only threw (no pattern-fallback was produced), the chain synthesizes
        // one with providerId=fallback.
        assertNotNull(response);
        assertEquals(ProviderChainClient.FALLBACK_PROVIDER_ID, response.getProviderId());
        assertNotNull(response.getContent());
        assertFalse(response.getContent().isBlank());
    }

    @Test
    void exceptionalRecoveryProbeFallsBackToBackupWithinSameRequest() throws Exception {
        // Head threw during the recovery probe -> the same request must still
        // be served by the active backup, not complete exceptionally.
        AlwaysDeadMember deadHead = new AlwaysDeadMember("dead");
        FakeMember backup = new FakeMember("backup");

        ProviderChainClient chain = new ProviderChainClient(
            List.of(deadHead, backup), null, 1);
        long startNanos2 = System.nanoTime();

        assertEquals("backup", sendAndGet(chain).getProviderId()); // failover
        awaitCooldownElapsed(startNanos2, 1100);
        LLMResponse response = sendAndGet(chain);                  // probe throws -> backup serves

        assertEquals("backup", response.getProviderId(),
            "exceptional probe must not fail the request; backup answers");
        assertTrue(deadHead.attempts >= 2, "head was probed again after cooldown");
    }

    @Test
    void healthyActiveMemberIsNotSwitchedAwayFrom() throws Exception {
        FakeMember head = new FakeMember("head");
        FakeMember backup = new FakeMember("backup");
        List<String> switches = new java.util.ArrayList<>();

        ProviderChainClient chain = new ProviderChainClient(
            List.of(head, backup), switches::add, 60);

        for (int i = 0; i < 3; i++) {
            assertEquals("head", sendAndGet(chain).getProviderId());
        }
        assertEquals(0, switches.size(), "no notifications without an actual switch");
        assertEquals(0, backup.attempts);
    }

    @Test
    void callerModelOverrideIsNotForwardedToMembers() throws Exception {
        // Regression (PR #38 live log): TaskPlanner puts the ACTIVE provider's
        // model into params; OpenAICompatibleClient prefers params over its own
        // configured model -> every chain member ended up requesting the head's
        // model (all providers saw "ox-alpha"). The chain must strip it so each
        // member speaks with ITS OWN configured model.
        FakeMember head = new FakeMember("head").thenError();
        FakeMember backup = new FakeMember("backup");

        ProviderChainClient chain = new ProviderChainClient(List.of(head, backup), null, 60);

        Map<String, Object> params = Map.of(
            "model", "head-model",
            "maxTokens", 100,
            "temperature", 0.7);
        LLMResponse response = chain.sendAsync("prompt", params).get();

        assertEquals("backup", response.getProviderId());
        assertNotNull(head.lastParams);
        assertNotNull(backup.lastParams);
        assertFalse(head.lastParams.containsKey("model"),
            "head must not receive the caller's model override");
        assertFalse(backup.lastParams.containsKey("model"),
            "backup must not receive the caller's model override");
        assertEquals(100, backup.lastParams.get("maxTokens"),
            "other params must pass through untouched");
    }

    @Test
    void paramsWithoutModelPassThroughUnchanged() throws Exception {
        FakeMember head = new FakeMember("head");
        ProviderChainClient chain = new ProviderChainClient(List.of(head), null, 60);

        Map<String, Object> params = Map.of("maxTokens", 100);
        chain.sendAsync("prompt", params).get();

        assertSame(params, head.lastParams,
            "no model key -> params map is forwarded as-is (no copy overhead)");
    }
}
