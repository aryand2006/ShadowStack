package com.shadowstack.refactor.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Objects;

/**
 * Represents a safety invariant that must hold before and after a refactoring operation.
 *
 * <p>Safety invariants are specific, verifiable conditions that the refactoring engine checks
 * to ensure behavioral equivalence. Each invariant has a unique identifier, a human-readable
 * description, and a verification status.</p>
 *
 * <p>Examples of invariants:</p>
 * <ul>
 *   <li>"no_outer_this_capture" — The anonymous class does not reference the enclosing instance via {@code this}</li>
 *   <li>"functional_interface" — The target interface has exactly one abstract method</li>
 *   <li>"no_mutable_capture" — No captured variables are mutated within the lambda body</li>
 * </ul>
 */
public final class SafetyInvariant {

    private final String invariantId;
    private final String description;
    private final InvariantStatus status;
    private final String evidence;

    /**
     * Status of invariant verification.
     */
    public enum InvariantStatus {
        /** Invariant has been verified to hold. */
        VERIFIED,
        /** Invariant has been violated — transformation is unsafe. */
        VIOLATED,
        /** Invariant could not be checked (e.g., insufficient type information). */
        UNDETERMINED
    }

    @JsonCreator
    public SafetyInvariant(
            @JsonProperty("invariantId") String invariantId,
            @JsonProperty("description") String description,
            @JsonProperty("status") InvariantStatus status,
            @JsonProperty("evidence") String evidence) {
        this.invariantId = Objects.requireNonNull(invariantId, "invariantId must not be null");
        this.description = Objects.requireNonNull(description, "description must not be null");
        this.status = Objects.requireNonNull(status, "status must not be null");
        this.evidence = evidence;
    }

    public String getInvariantId() {
        return invariantId;
    }

    public String getDescription() {
        return description;
    }

    public InvariantStatus getStatus() {
        return status;
    }

    /**
     * Optional evidence string explaining why the invariant holds or is violated.
     */
    public String getEvidence() {
        return evidence;
    }

    /**
     * Returns true if this invariant has been verified to hold.
     */
    public boolean isVerified() {
        return status == InvariantStatus.VERIFIED;
    }

    /**
     * Returns true if this invariant has been violated.
     */
    public boolean isViolated() {
        return status == InvariantStatus.VIOLATED;
    }

    /**
     * Factory method for creating a verified invariant.
     */
    public static SafetyInvariant verified(String id, String description, String evidence) {
        return new SafetyInvariant(id, description, InvariantStatus.VERIFIED, evidence);
    }

    /**
     * Factory method for creating a violated invariant.
     */
    public static SafetyInvariant violated(String id, String description, String evidence) {
        return new SafetyInvariant(id, description, InvariantStatus.VIOLATED, evidence);
    }

    /**
     * Factory method for creating an undetermined invariant.
     */
    public static SafetyInvariant undetermined(String id, String description, String evidence) {
        return new SafetyInvariant(id, description, InvariantStatus.UNDETERMINED, evidence);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof SafetyInvariant that)) return false;
        return invariantId.equals(that.invariantId) && status == that.status;
    }

    @Override
    public int hashCode() {
        return Objects.hash(invariantId, status);
    }

    @Override
    public String toString() {
        return "SafetyInvariant{id='%s', status=%s, desc='%s'}".formatted(invariantId, status, description);
    }
}
