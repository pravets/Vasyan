package ru.pravets.vasyan.llm.resilience;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import ru.pravets.vasyan.VasyanMod;
import ru.pravets.vasyan.llm.async.AsyncLLMClient;
import ru.pravets.vasyan.llm.async.LLMResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Failover chain over an ordered list of LLM provider clients.
 *
 * <p>Implements {@link AsyncLLMClient}. Each chain member is expected to be a
 * fully resilient client (its own retry, circuit breaker, rate limiter,
 * bulkhead) - this class adds NO extra retry layer on top. It only routes one
 * logical request to the first usable member:</p>
 *
 * <ul>
 *   <li>Routing starts at the current active index (volatile via AtomicInteger,
 *       no locks on the hot path).</li>
 *   <li>A member is skipped when its circuit breaker is OPEN or its cooldown
 *       has not elapsed since its last failure (prevents retry storms against
 *       a dead provider).</li>
 *   <li>The FIRST member producing a usable response wins; it becomes the
 *       active member and an actual switch is announced once through the
 *       notification callback.</li>
 *   <li>If EVERY member fails, the last fallback response is returned -
 *       identical semantics to the single-provider setup where
 *       {@link ResilientLLMClient} degrades to the pattern-based fallback.</li>
 * </ul>
 *
 * <p><b>Fallback detection:</b> ResilientLLMClient never completes
 * exceptionally - after exhausting retries it returns a pattern-based
 * fallback response with {@code providerId == "fallback"}. The chain treats
 * such responses as member failures and moves on to the next member.</p>
 *
 * <p><b>Recovery:</b> while traffic sits on a non-head member, each new
 * request first re-probes the HEAD (highest-priority) provider once at least
 * {@code failoverRetrySeconds} have passed since the head was last attempted.
 * A successful probe fails traffic back to the top of the chain.</p>
 */
public class ProviderChainClient implements AsyncLLMClient {

    private static final Logger LOGGER = LoggerFactory.getLogger(ProviderChainClient.class);

    /** Provider id reported by LLMFallbackHandler responses. */
    public static final String FALLBACK_PROVIDER_ID = "fallback";

    private static final long COOLDOWN_UNSET = 0L;

    private final List<AsyncLLMClient> members;
    private final Consumer<String> onProviderChanged;
    private final long failoverRetryMillis;

    /** Current active member index; volatile semantics via AtomicInteger. */
    private final AtomicInteger activeIndex = new AtomicInteger(0);

    /**
     * Per-member timestamp (epoch millis) of the last FAILURE. A member inside
     * its cooldown window is skipped (prevents retry storms against a dead
     * provider). Plain volatile-like array writes are sufficient: worst case a
     * racing thread skips or tries a member one cooldown window early/late,
     * which is harmless.
     */
    private final java.util.concurrent.atomic.AtomicLongArray lastFailureAt;

    /** Timestamp of the last attempt (success or failure) of the HEAD member; gates recovery probes atomically. */
    private final java.util.concurrent.atomic.AtomicLong lastHeadAttemptAt =
        new java.util.concurrent.atomic.AtomicLong(COOLDOWN_UNSET);

    /**
     * Creates a failover chain.
     *
     * @param members              ordered clients, highest priority first; each should be resilience-wrapped
     * @param onProviderChanged    invoked ONCE per actual active-provider change with a chat-ready message; may be null
     * @param failoverRetrySeconds cooldown (seconds) before the head provider is retried after failover
     */
    public ProviderChainClient(List<AsyncLLMClient> members, Consumer<String> onProviderChanged,
                               int failoverRetrySeconds) {
        this.members = List.copyOf(Objects.requireNonNull(members, "members"));
        if (this.members.isEmpty()) {
            throw new IllegalArgumentException("ProviderChainClient requires at least one member");
        }
        this.onProviderChanged = onProviderChanged;
        this.failoverRetryMillis = Math.max(1, failoverRetrySeconds) * 1000L;
        this.lastFailureAt = new java.util.concurrent.atomic.AtomicLongArray(this.members.size());
    }

    @Override
    public CompletableFuture<LLMResponse> sendAsync(String prompt, Map<String, Object> params) {
        // Strip the caller-supplied "model" override: it carries the ACTIVE
        // provider's model (TaskPlanner.getActiveModel()) and would otherwise
        // force every chain member to request the head's model
        // (OpenAICompatibleClient.buildRequestBody prefers params over its
        // own configured model). Each member must speak with ITS OWN model.
        Map<String, Object> memberParams = withoutModelOverride(params);

        int size = members.size();
        int startIndex = Math.min(activeIndex.get(), size - 1);

        LOGGER.debug("[chain] routing request from '{}' ({}/{}), headProbe={}",
            idAt(startIndex), startIndex + 1, size, shouldProbeHead(startIndex));

        // Cooldown-gated recovery probe of the highest-priority provider.
        // CAS-gated: only ONE concurrent request wins the probe slot per
        // cooldown window; the rest walk the chain from their active index.
        if (shouldProbeHead(startIndex)) {
            AsyncLLMClient head = members.get(0);
            if (markHeadAttempt()) {
                return head.sendAsync(prompt, memberParams)
                    .thenCompose(response -> {
                        if (!isUsable(response)) {
                            markFailure(0);
                            LOGGER.debug("[chain] recovery probe of '{}' still failing", head.getProviderId());
                            return walk(prompt, memberParams, startIndex);
                        }
                        // Recovered: CLEAR the stale failure timestamp so the
                        // next request does not skip the head due to its old
                        // cooldown and flap back to the backup.
                        clearFailure(0);
                        boolean switched = setActive(0);
                        LOGGER.info("[chain] head provider '{}' recovered, failing back", head.getProviderId());
                        notifySwitch(head.getProviderId(), true, switched);
                        return CompletableFuture.completedFuture(response);
                    })
                    .exceptionallyCompose(throwable -> {
                        // Probe itself threw (network error etc.): same-request
                        // fallback must still proceed to the active backup.
                        // Non-blocking: exceptionallyCompose chains the walk
                        // without parking this thread (a .get() here could
                        // deadlock a single-threaded executor shared with the
                        // backup client).
                        markFailure(0);
                        Throwable cause = throwable instanceof java.util.concurrent.CompletionException ce && ce.getCause() != null
                            ? ce.getCause() : throwable;
                        LOGGER.debug("[chain] recovery probe of '{}' threw: {}",
                            head.getProviderId(), cause.getMessage());
                        return walk(prompt, memberParams, startIndex);
                    });
            }
            // Lost the race: serve via the normal walk.
        }

        return walk(prompt, memberParams, startIndex);
    }

    /**
     * Returns params without the "model" key. The "model" entry produced by
     * the caller describes the ACTIVE provider only; forwarding it to every
     * member would make all of them request the head's model. Returns the
     * original map when there is nothing to strip.
     */
    private static Map<String, Object> withoutModelOverride(Map<String, Object> params) {
        if (params == null || !params.containsKey("model")) {
            return params;
        }
        Map<String, Object> copy = new java.util.HashMap<>(params);
        copy.remove("model");
        return copy;
    }

    /**
     * Builds a lazy sequential pipeline over the chain starting at startIndex:
     * each member is contacted ONLY if all earlier (in visit order) attempts
     * yielded nothing. Members whose circuit breaker is OPEN or who are inside
     * their failure cooldown are skipped outright.
     *
     * <p>Skip decisions use a snapshot taken when the request arrives; the
     * actual HTTP work happens lazily as the pipeline advances.</p>
     */
    private CompletableFuture<LLMResponse> walk(String prompt, Map<String, Object> params, int startIndex) {
        int size = members.size();
        CompletableFuture<LLMResponse> pipeline = CompletableFuture.completedFuture(null);
        boolean[] anyMemberEligible = {false};
        // Last unusable (pattern-fallback) response seen across the walk; if
        // EVERY member fails, this is still returned so the caller degrades
        // gracefully instead of getting a bare null.
        LLMResponse[] lastFallbackResponse = {null};

        for (int offset = 0; offset < size; offset++) {
            int index = (startIndex + offset) % size;
            AsyncLLMClient member = members.get(index);

            if (!member.isHealthy()) {
                LOGGER.debug("[chain] skipping '{}' - circuit breaker OPEN", member.getProviderId());
                continue;
            }
            if (inCooldown(index)) {
                LOGGER.debug("[chain] skipping '{}' - failure cooldown active", member.getProviderId());
                continue;
            }
            anyMemberEligible[0] = true;

            final AsyncLLMClient target = member;
            final int idx = index;

            pipeline = pipeline.thenCompose(previous -> {
                if (previous != null) {
                    // An earlier member already satisfied this request.
                    return CompletableFuture.completedFuture(previous);
                }
                markAttempt(idx);
                return target.sendAsync(prompt, params)
                    .thenCompose(response -> {
                        if (isUsable(response)) {
                            boolean switched = setActive(idx);
                            if (switched) {
                                LOGGER.info("[chain] failed over to '{}'", target.getProviderId());
                            } else {
                                LOGGER.debug("[chain] success via '{}'", target.getProviderId());
                            }
                            notifySwitch(target.getProviderId(), idx == 0, switched);
                            return CompletableFuture.completedFuture(response);
                        }
                        // Unusable = pattern-fallback or empty answer from
                        // this member's resilience layer; remember it as the
                        // best-effort result ONLY if it has content (an empty
                        // answer would just get discarded downstream anyway),
                        // then treat as a member failure.
                        if (response != null
                                && response.getContent() != null
                                && !response.getContent().isBlank()) {
                            lastFallbackResponse[0] = response;
                        }
                        markFailure(idx);
                        LOGGER.warn("[chain] member '{}' returned only a fallback response",
                            target.getProviderId());
                        return CompletableFuture.completedFuture(null);
                    })
                    .exceptionally(throwable -> {
                        Throwable cause = throwable instanceof java.util.concurrent.CompletionException
                            ? throwable.getCause()
                            : throwable;
                        markFailure(idx);
                        LOGGER.warn("[chain] member '{}' failed: {}", target.getProviderId(),
                            cause != null ? cause.getMessage() : throwable.getMessage());
                        return null;
                    });
            });
        }

        final boolean noneEligible = !anyMemberEligible[0];
        return pipeline.thenApply(result -> {
            if (result != null) {
                return result;
            }
            if (noneEligible) {
                LOGGER.warn("[chain] no eligible member available right now (all OPEN or cooling down)");
            }
            // Every eligible member failed (or threw). Preserve the
            // single-provider contract: the caller NEVER receives null.
            // Prefer a real pattern-fallback produced by a member's resilience
            // layer; if members only threw, synthesize one like
            // {@link LLMFallbackHandler} does.
            LLMResponse fallback = lastFallbackResponse[0];
            if (fallback == null) {
                // No member was even attempted (all breakers OPEN / cooling
                // down): synthesizing the blind DEFAULT here turns EVERY
                // command into "follow the player" - the prompt is never
                // pattern-matched (scenario J regression: a "gather 1 coal"
                // issued while ollama's breaker was still OPEN became
                // "Stay near the player"). Run the same pattern matcher the
                // member resilience layer would have run.
                LLMResponse handlerFallback = new LLMFallbackHandler().generateFallback(prompt, null);
                fallback = LLMResponse.builder()
                    .content(handlerFallback.getContent())
                    .model(handlerFallback.getModel())
                    .providerId(handlerFallback.getProviderId())
                    .tokensUsed(handlerFallback.getTokensUsed())
                    .latencyMs(handlerFallback.getLatencyMs())
                    .fromCache(handlerFallback.isFromCache())
                    .failureReason("all providers in the failover chain are unavailable")
                    .build();
            }
            return fallback;
        });
    }

    /**
     * True when the response is a real provider answer rather than the
     * pattern-fallback. Empty/blank content also counts as unusable: the
     * caller ({@code TaskPlanner.planTasksAsync}) would discard it anyway,
     * so the chain treats it as a member failure and moves on.
     */
    static boolean isUsable(LLMResponse response) {
        return response != null
            && !FALLBACK_PROVIDER_ID.equals(response.getProviderId())
            && response.getContent() != null
            && !response.getContent().isBlank();
    }

    /**
     * Whether this request should begin with a cooldown-gated probe of the
     * head provider. Pure check - the caller must confirm the slot via
     * {@link #markHeadAttempt()} (CAS) before actually probing.
     */
    private boolean shouldProbeHead(int activeIdx) {
        return activeIdx > 0
            && System.currentTimeMillis() - lastHeadAttemptAt.get() >= failoverRetryMillis;
    }

    /** A member that just failed is skipped until the cooldown elapses. */
    private boolean inCooldown(int index) {
        long failed = lastFailureAt.get(index);
        return failed != COOLDOWN_UNSET && System.currentTimeMillis() - failed < failoverRetryMillis;
    }

    private void markFailure(int index) {
        this.lastFailureAt.set(index, System.currentTimeMillis());
    }

    /**
     * Atomically claims the recovery-probe slot: updates the head attempt
     * timestamp and returns true only for the FIRST caller in the current
     * window. Losers must not probe (prevents duplicate parallel probes).
     */
    private boolean markHeadAttempt() {
        long now = System.currentTimeMillis();
        long prev = lastHeadAttemptAt.getAndSet(now);
        return now - prev >= failoverRetryMillis;
    }

    /** Clears a member's failure cooldown (used when the head recovers). */
    private void clearFailure(int index) {
        this.lastFailureAt.set(index, COOLDOWN_UNSET);
    }

    /** Records that a member was just attempted (only matters for the head's recovery gate). */
    private void markAttempt(int index) {
        if (index == 0) {
            markHeadAttempt();
        }
    }

    private boolean setActive(int index) {
        int previous = activeIndex.getAndSet(index);
        return previous != index;
    }

    private void notifySwitch(String newProviderId, boolean backToHead, boolean changed) {
        if (!changed || onProviderChanged == null || newProviderId == null) {
            return;
        }
        String message = backToHead
            ? "🔄 LLM провайдер восстановлен → " + newProviderId
            : "⚠️ LLM провайдер недоступен, переключаюсь на " + newProviderId;
        try {
            onProviderChanged.accept(message);
        } catch (Exception e) {
            LOGGER.warn("[chain] provider-change listener failed: {}", e.getMessage());
        }
    }

    private String idAt(int index) {
        return members.get(index).getProviderId();
    }

    @Override
    public String getProviderId() {
        return idAt(getActiveIndex());
    }

    @Override
    public boolean isHealthy() {
        return members.stream().anyMatch(AsyncLLMClient::isHealthy);
    }

    /**
     * Provider id of the member that would serve the next request - the ACTUAL
     * active provider, which may differ from the chain head during failover.
     */
    public String getActiveProviderId() {
        return getProviderId();
    }

    /** Index of the current active member (for observability). */
    public int getActiveIndex() {
        return Math.min(activeIndex.get(), members.size() - 1);
    }

    /** Number of members in the chain. */
    public int size() {
        return members.size();
    }

    /** Read-only view of the chain members, highest priority first. */
    public List<AsyncLLMClient> getMembers() {
        return members;
    }

    /**
     * Extracts the circuit-breaker state from a chain member without coupling
     * to concrete client classes. Unknown wrappers report CLOSED.
     */
    public static CircuitBreaker.State cbStateOf(AsyncLLMClient client) {
        if (client instanceof ResilientLLMClient resilient) {
            return resilient.getCircuitBreakerState();
        }
        return CircuitBreaker.State.CLOSED;
    }

    /** Millis until the given member leaves its failure cooldown (0 = not cooling down). */
    public long getMemberCooldownRemainingMillis(int index) {
        long failed = lastFailureAt.get(index);
        if (failed == COOLDOWN_UNSET) {
            return 0L;
        }
        return Math.max(0L, failoverRetryMillis - (System.currentTimeMillis() - failed));
    }
}
