package com.shadowstack.adapters.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.*;

/**
 * Represents a potential refactoring transformation identified in the source code.
 *
 * <p>A {@code RefactorCandidate} captures everything needed to understand, review,
 * and apply a single code transformation: the location in source, the rule that
 * identified it, before/after code snippets, confidence scoring, risk assessment,
 * and the set of safety invariants that must hold.</p>
 *
 * <p>Instances are immutable and constructed via {@link Builder}.</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public final class RefactorCandidate {

    private final String candidateId;
    private final String sourceFile;
    private final int startLine;
    private final int endLine;

    private final String ruleId;
    private final String ruleName;
    private final String ruleCategory;

    private final String beforeSnippet;
    private final String proposedAfterSnippet;

    private final double confidenceScore;
    private final RiskTier riskTier;

    private final List<SafetyInvariant> safetyInvariants;

    private final String enclosingClass;
    private final String enclosingMethod;
    private final Map<String, String> astContext;

    private RefactorCandidate(Builder builder) {
        this.candidateId = builder.candidateId != null ? builder.candidateId : UUID.randomUUID().toString();
        this.sourceFile = Objects.requireNonNull(builder.sourceFile, "sourceFile must not be null");
        this.startLine = builder.startLine;
        this.endLine = builder.endLine;
        this.ruleId = Objects.requireNonNull(builder.ruleId, "ruleId must not be null");
        this.ruleName = Objects.requireNonNull(builder.ruleName, "ruleName must not be null");
        this.ruleCategory = builder.ruleCategory != null ? builder.ruleCategory : "GENERAL";
        this.beforeSnippet = Objects.requireNonNull(builder.beforeSnippet, "beforeSnippet must not be null");
        this.proposedAfterSnippet = Objects.requireNonNull(builder.proposedAfterSnippet, "proposedAfterSnippet must not be null");

        if (builder.confidenceScore < 0.0 || builder.confidenceScore > 1.0) {
            throw new IllegalArgumentException(
                    "confidenceScore must be in [0.0, 1.0], got: " + builder.confidenceScore);
        }
        this.confidenceScore = builder.confidenceScore;
        this.riskTier = Objects.requireNonNull(builder.riskTier, "riskTier must not be null");
        this.safetyInvariants = builder.safetyInvariants != null
                ? List.copyOf(builder.safetyInvariants) : List.of();
        this.enclosingClass = builder.enclosingClass;
        this.enclosingMethod = builder.enclosingMethod;
        this.astContext = builder.astContext != null
                ? Map.copyOf(builder.astContext) : Map.of();
    }

    // ── Accessors ────────────────────────────────────────────────────────

    /** @return unique candidate identifier */
    @JsonProperty public String candidateId() { return candidateId; }

    /** @return relative path of the source file containing this candidate */
    @JsonProperty public String sourceFile() { return sourceFile; }

    /** @return first line of the code region (1-based, inclusive) */
    @JsonProperty public int startLine() { return startLine; }

    /** @return last line of the code region (1-based, inclusive) */
    @JsonProperty public int endLine() { return endLine; }

    /** @return identifier of the refactoring rule that produced this candidate */
    @JsonProperty public String ruleId() { return ruleId; }

    /** @return human-readable name of the refactoring rule */
    @JsonProperty public String ruleName() { return ruleName; }

    /** @return category of the refactoring rule (e.g., "MODERNIZATION", "CLEANUP") */
    @JsonProperty public String ruleCategory() { return ruleCategory; }

    /** @return original source code snippet */
    @JsonProperty public String beforeSnippet() { return beforeSnippet; }

    /** @return proposed transformed source code snippet */
    @JsonProperty public String proposedAfterSnippet() { return proposedAfterSnippet; }

    /**
     * Confidence that this refactoring is correct and beneficial.
     *
     * @return score in [0.0, 1.0] where 1.0 is maximum confidence
     */
    @JsonProperty public double confidenceScore() { return confidenceScore; }

    /** @return risk classification for this candidate */
    @JsonProperty public RiskTier riskTier() { return riskTier; }

    /** @return unmodifiable list of safety invariants that must hold */
    @JsonProperty public List<SafetyInvariant> safetyInvariants() { return safetyInvariants; }

    /** @return FQN of the enclosing class, or null if not applicable */
    @JsonProperty public String enclosingClass() { return enclosingClass; }

    /** @return signature of the enclosing method, or null if at class level */
    @JsonProperty public String enclosingMethod() { return enclosingMethod; }

    /** @return additional AST context metadata (e.g., node type, parent node type) */
    @JsonProperty public Map<String, String> astContext() { return astContext; }

    // ── Convenience methods ──────────────────────────────────────────────

    /**
     * Returns {@code true} if all safety invariants are satisfied.
     *
     * @return whether the candidate passes all safety checks
     */
    public boolean allInvariantsSatisfied() {
        return safetyInvariants.stream().allMatch(SafetyInvariant::isSatisfied);
    }

    /**
     * Returns {@code true} if any safety invariant is violated.
     *
     * @return whether any safety check failed
     */
    public boolean hasViolatedInvariants() {
        return safetyInvariants.stream().anyMatch(SafetyInvariant::isViolated);
    }

    /**
     * Returns the number of source lines affected by this candidate.
     *
     * @return line span (endLine - startLine + 1)
     */
    public int lineSpan() {
        return endLine - startLine + 1;
    }

    /**
     * Creates a new builder pre-populated with this candidate's values.
     *
     * @return a new builder for creating a modified copy
     */
    public Builder toBuilder() {
        return new Builder()
                .candidateId(candidateId)
                .sourceFile(sourceFile)
                .startLine(startLine)
                .endLine(endLine)
                .ruleId(ruleId)
                .ruleName(ruleName)
                .ruleCategory(ruleCategory)
                .beforeSnippet(beforeSnippet)
                .proposedAfterSnippet(proposedAfterSnippet)
                .confidenceScore(confidenceScore)
                .riskTier(riskTier)
                .safetyInvariants(safetyInvariants)
                .enclosingClass(enclosingClass)
                .enclosingMethod(enclosingMethod)
                .astContext(astContext);
    }

    public static Builder builder() {
        return new Builder();
    }

    // ── Builder ──────────────────────────────────────────────────────────

    /**
     * Mutable builder for {@link RefactorCandidate}.
     */
    public static final class Builder {
        private String candidateId;
        private String sourceFile;
        private int startLine;
        private int endLine;
        private String ruleId;
        private String ruleName;
        private String ruleCategory;
        private String beforeSnippet;
        private String proposedAfterSnippet;
        private double confidenceScore;
        private RiskTier riskTier;
        private List<SafetyInvariant> safetyInvariants;
        private String enclosingClass;
        private String enclosingMethod;
        private Map<String, String> astContext;

        private Builder() {}

        public Builder candidateId(String candidateId) { this.candidateId = candidateId; return this; }
        public Builder sourceFile(String sourceFile) { this.sourceFile = sourceFile; return this; }
        public Builder startLine(int startLine) { this.startLine = startLine; return this; }
        public Builder endLine(int endLine) { this.endLine = endLine; return this; }
        public Builder ruleId(String ruleId) { this.ruleId = ruleId; return this; }
        public Builder ruleName(String ruleName) { this.ruleName = ruleName; return this; }
        public Builder ruleCategory(String ruleCategory) { this.ruleCategory = ruleCategory; return this; }
        public Builder beforeSnippet(String beforeSnippet) { this.beforeSnippet = beforeSnippet; return this; }
        public Builder proposedAfterSnippet(String proposedAfterSnippet) { this.proposedAfterSnippet = proposedAfterSnippet; return this; }
        public Builder confidenceScore(double confidenceScore) { this.confidenceScore = confidenceScore; return this; }
        public Builder riskTier(RiskTier riskTier) { this.riskTier = riskTier; return this; }
        public Builder safetyInvariants(List<SafetyInvariant> safetyInvariants) { this.safetyInvariants = safetyInvariants; return this; }
        public Builder addSafetyInvariant(SafetyInvariant invariant) {
            if (this.safetyInvariants == null) this.safetyInvariants = new ArrayList<>();
            this.safetyInvariants.add(Objects.requireNonNull(invariant));
            return this;
        }
        public Builder enclosingClass(String enclosingClass) { this.enclosingClass = enclosingClass; return this; }
        public Builder enclosingMethod(String enclosingMethod) { this.enclosingMethod = enclosingMethod; return this; }
        public Builder astContext(Map<String, String> astContext) { this.astContext = astContext != null ? new LinkedHashMap<>(astContext) : null; return this; }
        public Builder putAstContext(String key, String value) {
            if (this.astContext == null) this.astContext = new LinkedHashMap<>();
            this.astContext.put(key, value);
            return this;
        }

        /**
         * Builds an immutable {@link RefactorCandidate}.
         *
         * @return new candidate instance
         * @throws NullPointerException     if required fields are null
         * @throws IllegalArgumentException if confidenceScore is out of range
         */
        public RefactorCandidate build() {
            return new RefactorCandidate(this);
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof RefactorCandidate that)) return false;
        return Objects.equals(candidateId, that.candidateId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(candidateId);
    }

    @Override
    public String toString() {
        return "RefactorCandidate{" +
                "id='" + candidateId + '\'' +
                ", rule='" + ruleId + '\'' +
                ", file='" + sourceFile + '\'' +
                ", lines=" + startLine + "-" + endLine +
                ", confidence=" + confidenceScore +
                ", risk=" + riskTier +
                '}';
    }
}
