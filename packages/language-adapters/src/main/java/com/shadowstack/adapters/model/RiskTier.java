package com.shadowstack.adapters.model;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Risk classification for refactoring candidates.
 *
 * <p>Each tier maps to a numeric threshold on a [0.0, 1.0] risk scale.
 * A candidate whose computed risk score falls at or above the tier's
 * {@link #threshold()} is assigned that tier.</p>
 *
 * <p>Thresholds are deliberately non-overlapping and ordered from lowest
 * to highest risk so that {@link #fromScore(double)} can perform a
 * single descending scan.</p>
 */
public enum RiskTier {

    /** Trivially safe transformations — formatting, comment updates, import reordering. */
    LOW(0.0, "Low-risk refactoring — safe to auto-apply"),

    /** Structural changes that preserve API surface — extract method, rename local variable. */
    MODERATE(0.25, "Moderate-risk refactoring — review recommended"),

    /** Changes that may alter observable behavior — loop restructuring, exception flow changes. */
    HIGH(0.50, "High-risk refactoring — mandatory review + verification"),

    /** Changes to concurrency, security-sensitive code, or public API contracts. */
    CRITICAL(0.75, "Critical-risk refactoring — requires multi-layer verification and sign-off");

    private final double threshold;
    private final String description;

    RiskTier(double threshold, String description) {
        this.threshold = threshold;
        this.description = description;
    }

    /**
     * Returns the minimum risk score that maps to this tier.
     *
     * @return threshold in [0.0, 1.0]
     */
    public double threshold() {
        return threshold;
    }

    /**
     * Human-readable description of what this tier implies for review workflows.
     *
     * @return non-null description string
     */
    public String description() {
        return description;
    }

    /**
     * Determines the appropriate {@code RiskTier} for a given numeric risk score.
     *
     * <p>Scans tiers from highest to lowest; returns the first tier whose
     * threshold the score meets or exceeds. Defaults to {@link #LOW}.</p>
     *
     * @param score risk score in [0.0, 1.0]
     * @return the matching tier, never null
     * @throws IllegalArgumentException if score is outside [0.0, 1.0]
     */
    public static RiskTier fromScore(double score) {
        if (score < 0.0 || score > 1.0) {
            throw new IllegalArgumentException("Risk score must be in [0.0, 1.0], got: " + score);
        }
        if (score >= CRITICAL.threshold) return CRITICAL;
        if (score >= HIGH.threshold) return HIGH;
        if (score >= MODERATE.threshold) return MODERATE;
        return LOW;
    }

    /**
     * Returns whether this tier requires human sign-off before applying the refactoring.
     *
     * @return {@code true} for {@link #HIGH} and {@link #CRITICAL}
     */
    public boolean requiresReview() {
        return this == HIGH || this == CRITICAL;
    }

    /**
     * Returns whether this tier mandates full multi-layer verification.
     *
     * @return {@code true} only for {@link #CRITICAL}
     */
    public boolean requiresFullVerification() {
        return this == CRITICAL;
    }

    @JsonValue
    @Override
    public String toString() {
        return name();
    }
}
