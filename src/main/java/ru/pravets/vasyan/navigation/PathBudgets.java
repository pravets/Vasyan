package ru.pravets.vasyan.navigation;

/**
 * Pure time/scope budget for one pathfinding attempt: a think deadline (total attempt budget),
 * a rolling tick deadline (per-tick slice), and a search radius in blocks. No Forge imports —
 * plain arithmetic so it stays unit-testable outside the Minecraft bootstrap.
 *
 * <p>Wall-clock deadlines are absolute {@link System#nanoTime()}-style values; expiry is inclusive
 * ({@code nowNano >= deadline} counts as expired). Timeouts are converted to durations once
 * at {@link #start} and carried in the {@code tickTimeoutNanos} component, which lets
 * {@link #nextTick} roll a fresh per-tick deadline without external state.</p>
 *
 * <p>The tick-based think deadline is independent of wall-clock time and is intended for
 * checks that must survive low server TPS. It is only meaningful for budgets created with
 * {@link #startInTicks}; budgets created with {@link #start} carry {@code 0} in this field.</p>
 *
 * @param thinkDeadlineNano absolute nanos when the whole pathfinding attempt must be over
 * @param tickDeadlineNano  absolute nanos when the current pathfinding tick slice expires
 * @param searchRadius      max distance in blocks to search, passed through unchanged
 * @param tickTimeoutNanos  duration of one tick slice in nanos (implementation detail of nextTick)
 * @param thinkDeadlineTick absolute game tick when the think budget expires for tick-based checks
 */
public record PathBudgets(long thinkDeadlineNano, long tickDeadlineNano, int searchRadius,
                          long tickTimeoutNanos, long thinkDeadlineTick) {

    public PathBudgets {
        if (tickTimeoutNanos <= 0) {
            throw new IllegalArgumentException("tickTimeoutNanos must be > 0, got " + tickTimeoutNanos);
        }
        if (searchRadius <= 0) {
            throw new IllegalArgumentException("searchRadius must be > 0, got " + searchRadius);
        }
    }

    /**
     * Creates budgets from timeouts measured relative to {@code nowNano}.
     *
     * @param nowNano       current time in nanos (e.g. {@code System.nanoTime()})
     * @param thinkTimeoutMs total think budget in milliseconds
     * @param tickTimeoutMs  per-tick slice in milliseconds
     * @param searchRadius   max search distance in blocks
     */
    public static PathBudgets start(long nowNano, long thinkTimeoutMs, long tickTimeoutMs, int searchRadius) {
        if (thinkTimeoutMs <= 0) {
            throw new IllegalArgumentException("thinkTimeoutMs must be > 0, got " + thinkTimeoutMs);
        }
        if (tickTimeoutMs <= 0) {
            throw new IllegalArgumentException("tickTimeoutMs must be > 0, got " + tickTimeoutMs);
        }
        long tickNanos = Math.multiplyExact(tickTimeoutMs, 1_000_000L);
        return new PathBudgets(nowNano + Math.multiplyExact(thinkTimeoutMs, 1_000_000L),
            nowNano + tickNanos, searchRadius, tickNanos, 0L);
    }

    /**
     * Creates budgets where the think deadline is measured in game ticks instead of wall-clock
     * nanos. The per-tick slice still uses {@code tickTimeoutMs} to drive {@link #nextTick}.
     *
     * @param startTick     game tick at which this attempt starts
     * @param thinkTimeoutMs total think budget in milliseconds
     * @param tickTimeoutMs  per-tick slice in milliseconds
     * @param searchRadius   max search distance in blocks
     */
    public static PathBudgets startInTicks(long startTick, long thinkTimeoutMs, long tickTimeoutMs, int searchRadius) {
        if (thinkTimeoutMs <= 0) {
            throw new IllegalArgumentException("thinkTimeoutMs must be > 0, got " + thinkTimeoutMs);
        }
        if (tickTimeoutMs <= 0) {
            throw new IllegalArgumentException("tickTimeoutMs must be > 0, got " + tickTimeoutMs);
        }
        long tickNanos = Math.multiplyExact(tickTimeoutMs, 1_000_000L);
        long nowNano = System.nanoTime();
        return new PathBudgets(nowNano + Math.multiplyExact(thinkTimeoutMs, 1_000_000L),
            nowNano + tickNanos, searchRadius, tickNanos, startTick + thinkTimeoutMs / 50L);
    }

    /**
     * Returns true when the total think budget has run out.
     *
     * @param nowNano current time in nanos
     */
    public boolean thinkExpired(long nowNano) {
        return nowNano >= thinkDeadlineNano;
    }

    /**
     * Returns true when the tick-based think budget has run out.
     *
     * @param currentTick current game tick
     */
    public boolean thinkExpiredTicks(long currentTick) {
        return currentTick > thinkDeadlineTick;
    }

    /**
     * Returns true when the current tick slice has run out.
     *
     * @param nowNano current time in nanos
     */
    public boolean tickExpired(long nowNano) {
        return nowNano >= tickDeadlineNano;
    }

    /**
     * Rolls a fresh tick deadline from {@code nowNano}, leaving the think deadline and radius
     * unchanged.
     *
     * @param nowNano current time in nanos
     */
    public PathBudgets nextTick(long nowNano) {
        return new PathBudgets(thinkDeadlineNano,
            nowNano + tickTimeoutNanos,
            searchRadius,
            tickTimeoutNanos,
            thinkDeadlineTick);
    }
}
