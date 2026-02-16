package com.shadowstack.adapters.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Objects;

/**
 * Represents a single safety check that must hold before, during, or after
 * a refactoring transformation.
 *
 * <p>Safety invariants are the atomic units of ShadowStack's verification
 * pipeline. Each invariant belongs to a {@link Category} and carries an
 * evidence string that explains <em>why</em> the invariant is in its
 * current {@link Status}.</p>
 *
 * <p>Instances are immutable once constructed.</p>
 */
public final class SafetyInvariant {

    /**
     * Broad categories of safety properties that ShadowStack verifies.
     */
    public enum Category {
        /** Type assignments remain valid after the transformation. */
        TYPE_SAFETY,
        /** Thread-safety contracts (synchronized blocks, volatile fields) are preserved. */
        THREAD_SAFETY,
        /** Observable behavior is unchanged for all reachable inputs. */
        BEHAVIORAL_EQUIVALENCE,
        /** Public API surface (method signatures, return types) is compatible. */
        API_COMPATIBILITY,
        /** Resource management (try-with-resources, close calls) is preserved. */
        RESOURCE_SAFETY,
        /** Exception flow (checked/unchecked, catch hierarchy) is unchanged. */
        EXCEPTION_SAFETY,
        /** Null-safety contracts (nullable annotations, null checks) are maintained. */
        NULL_SAFETY,
        /** Serialization compatibility (serialVersionUID, field layout) is preserved. */
        SERIALIZATION_COMPATIBILITY
    }

    /**
     * Tri-state evaluation result for a safety invariant.
     */
    public enum Status {
        /** The invariant has been proven to hold. */
        SATISFIED,
        /** The invariant has been proven to be violated. */
        VIOLATED,
        /** The invariant could not be conclusively evaluated. */
        UNKNOWN
    }

    private final String invariantId;
    private final String description;
    private final Category category;
    private final Status status;
    private final String evidence;

    /**
     * Constructs a new safety invariant.
     *
     * @param invariantId unique identifier for this invariant instance (e.g., "TS-001")
     * @param description human-readable description of what this invariant checks
     * @param category    the safety category
     * @param status      current evaluation status
     * @param evidence    explanation or proof supporting the status determination
     * @throws NullPointerException if any argument is null
     */
    @JsonCreator
    public SafetyInvariant(
            @JsonProperty("invariantId") String invariantId,
            @JsonProperty("description") String description,
            @JsonProperty("category") Category category,
            @JsonProperty("status") Status status,
            @JsonProperty("evidence") String evidence) {
        this.invariantId = Objects.requireNonNull(invariantId, "invariantId must not be null");
        this.description = Objects.requireNonNull(description, "description must not be null");
        this.category = Objects.requireNonNull(category, "category must not be null");
        this.status = Objects.requireNonNull(status, "status must not be null");
        this.evidence = Objects.requireNonNull(evidence, "evidence must not be null");
    }

    /** @return unique identifier for this invariant instance */
    @JsonProperty
    public String invariantId() {
        return invariantId;
    }

    /** @return human-readable description of the safety check */
    @JsonProperty
    public String description() {
        return description;
    }

    /** @return the safety category this invariant belongs to */
    @JsonProperty
    public Category category() {
        return category;
    }

    /** @return current evaluation status */
    @JsonProperty
    public Status status() {
        return status;
    }

    /** @return explanation or proof supporting the status */
    @JsonProperty
    public String evidence() {
        return evidence;
    }

    /**
     * Returns {@code true} if this invariant has been proven to hold.
     *
     * @return whether status is {@link Status#SATISFIED}
     */
    public boolean isSatisfied() {
        return status == Status.SATISFIED;
    }

    /**
     * Returns {@code true} if this invariant is violated, indicating the
     * refactoring should be rejected or flagged for human review.
     *
     * @return whether status is {@link Status#VIOLATED}
     */
    public boolean isViolated() {
        return status == Status.VIOLATED;
    }

    /**
     * Creates a new invariant identical to this one but with a different status and evidence.
     *
     * @param newStatus   the updated status
     * @param newEvidence the updated evidence
     * @return a new {@code SafetyInvariant} instance
     */
    public SafetyInvariant withResult(Status newStatus, String newEvidence) {
        return new SafetyInvariant(invariantId, description, category, newStatus, newEvidence);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof SafetyInvariant that)) return false;
        return Objects.equals(invariantId, that.invariantId)
                && category == that.category
                && status == that.status;
    }

    @Override
    public int hashCode() {
        return Objects.hash(invariantId, category, status);
    }

    @Override
    public String toString() {
        return "SafetyInvariant{" +
                "id='" + invariantId + '\'' +
                ", category=" + category +
                ", status=" + status +
                ", description='" + description + '\'' +
                '}';
    }
}
