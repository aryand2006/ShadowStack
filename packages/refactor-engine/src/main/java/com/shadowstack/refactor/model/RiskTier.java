package com.shadowstack.refactor.model;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Risk classification tiers for refactoring operations.
 *
 * <p>Each tier represents a distinct level of behavioral risk associated with a code transformation.
 * The tier influences review requirements, approval workflows, and deployment gates in the
 * ShadowStack pipeline.</p>
 *
 * <ul>
 *   <li>{@link #COSMETIC} — No behavioral change (whitespace, formatting, comment-only changes)</li>
 *   <li>{@link #LOW} — Minimal risk; transformation is mechanically provable (e.g., anonymous → lambda)</li>
 *   <li>{@link #MEDIUM} — Moderate risk; requires test coverage verification</li>
 *   <li>{@link #HIGH} — Significant risk; requires human review and comprehensive test validation</li>
 *   <li>{@link #CRITICAL} — Architectural changes; requires senior review and staged rollout</li>
 * </ul>
 */
public enum RiskTier {

    COSMETIC("cosmetic", 0, "No behavioral change"),
    LOW("low", 1, "Minimal risk, mechanically provable"),
    MEDIUM("medium", 2, "Moderate risk, requires test verification"),
    HIGH("high", 3, "Significant risk, requires human review"),
    CRITICAL("critical", 4, "Architectural change, requires senior review");

    private final String label;
    private final int ordinalLevel;
    private final String description;

    RiskTier(String label, int ordinalLevel, String description) {
        this.label = label;
        this.ordinalLevel = ordinalLevel;
        this.description = description;
    }

    /**
     * Returns the machine-readable label for this risk tier.
     */
    @JsonValue
    public String getLabel() {
        return label;
    }

    /**
     * Returns the numeric ordinal level (0 = lowest risk, 4 = highest).
     */
    public int getOrdinalLevel() {
        return ordinalLevel;
    }

    /**
     * Returns a human-readable description of this risk tier.
     */
    public String getDescription() {
        return description;
    }

    /**
     * Returns true if this tier is at or above the given threshold.
     */
    public boolean isAtOrAbove(RiskTier threshold) {
        return this.ordinalLevel >= threshold.ordinalLevel;
    }

    /**
     * Returns the higher of two risk tiers.
     */
    public static RiskTier max(RiskTier a, RiskTier b) {
        return a.ordinalLevel >= b.ordinalLevel ? a : b;
    }
}
