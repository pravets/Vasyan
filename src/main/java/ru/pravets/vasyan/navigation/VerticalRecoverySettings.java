package ru.pravets.vasyan.navigation;

/**
 * Limits for vertical staircase recovery.
 *
 * @param enabled         whether vertical recovery may run at all
 * @param maxDistance     maximum absolute Y difference recovery will attempt
 * @param horizontalRange maximum horizontal Chebyshev distance from bot to goal anchor
 * @param maxScaffoldBlocks maximum scaffold blocks one monitor may place for vertical steps
 */
public record VerticalRecoverySettings(boolean enabled, int maxDistance, int horizontalRange,
                                       int maxScaffoldBlocks) {

    /** Safe defaults matching the navigation config defaults. */
    public static final VerticalRecoverySettings DEFAULT = new VerticalRecoverySettings(true, 16, 6, 32);

    /** Constructor for callers that do not cap scaffold usage explicitly. */
    public VerticalRecoverySettings(boolean enabled, int maxDistance, int horizontalRange) {
        this(enabled, maxDistance, horizontalRange, DEFAULT.maxScaffoldBlocks());
    }

    public VerticalRecoverySettings {
        if (maxDistance <= 0) {
            throw new IllegalArgumentException("maxDistance must be positive: " + maxDistance);
        }
        if (horizontalRange <= 0) {
            throw new IllegalArgumentException("horizontalRange must be positive: " + horizontalRange);
        }
        if (maxScaffoldBlocks <= 0) {
            throw new IllegalArgumentException("maxScaffoldBlocks must be positive: " + maxScaffoldBlocks);
        }
    }
}
