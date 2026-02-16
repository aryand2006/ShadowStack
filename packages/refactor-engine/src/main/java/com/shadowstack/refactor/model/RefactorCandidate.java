package com.shadowstack.refactor.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.eclipse.jdt.core.dom.ASTNode;

import java.time.Instant;
import java.util.*;

/**
 * Represents a candidate location in the source code eligible for a specific refactoring rule.
 *
 * <p>A RefactorCandidate is produced by the analysis phase of a {@link com.shadowstack.refactor.RefactorRule}.
 * It captures the AST node, its source location, the safety invariants evaluated, and the
 * confidence score for the proposed transformation. Candidates that pass all invariant checks
 * can be promoted to {@link PatchUnit} objects.</p>
 */
public final class RefactorCandidate {

    private final UUID candidateId;
    private final String ruleId;
    private final String sourceFile;
    private final int startLine;
    private final int endLine;
    private final int startPosition;
    private final int length;
    private final String originalSnippet;
    private final String proposedSnippet;
    private final double confidenceScore;
    private final RiskTier riskTier;
    private final List<SafetyInvariant> invariants;
    private final Map<String, Object> analysisMetadata;
    private final String rationale;
    private final Instant analyzedAt;

    // Transient: the actual AST node (not serialized)
    private transient final ASTNode astNode;

    @JsonCreator
    public RefactorCandidate(
            @JsonProperty("candidateId") UUID candidateId,
            @JsonProperty("ruleId") String ruleId,
            @JsonProperty("sourceFile") String sourceFile,
            @JsonProperty("startLine") int startLine,
            @JsonProperty("endLine") int endLine,
            @JsonProperty("startPosition") int startPosition,
            @JsonProperty("length") int length,
            @JsonProperty("originalSnippet") String originalSnippet,
            @JsonProperty("proposedSnippet") String proposedSnippet,
            @JsonProperty("confidenceScore") double confidenceScore,
            @JsonProperty("riskTier") RiskTier riskTier,
            @JsonProperty("invariants") List<SafetyInvariant> invariants,
            @JsonProperty("analysisMetadata") Map<String, Object> analysisMetadata,
            @JsonProperty("rationale") String rationale,
            ASTNode astNode) {
        this.candidateId = candidateId != null ? candidateId : UUID.randomUUID();
        this.ruleId = Objects.requireNonNull(ruleId, "ruleId must not be null");
        this.sourceFile = Objects.requireNonNull(sourceFile, "sourceFile must not be null");
        this.startLine = startLine;
        this.endLine = endLine;
        this.startPosition = startPosition;
        this.length = length;
        this.originalSnippet = originalSnippet;
        this.proposedSnippet = proposedSnippet;
        this.confidenceScore = confidenceScore;
        this.riskTier = riskTier != null ? riskTier : RiskTier.MEDIUM;
        this.invariants = invariants != null ? List.copyOf(invariants) : List.of();
        this.analysisMetadata = analysisMetadata != null ? Map.copyOf(analysisMetadata) : Map.of();
        this.rationale = rationale;
        this.analyzedAt = Instant.now();
        this.astNode = astNode;
    }

    public UUID getCandidateId() { return candidateId; }
    public String getRuleId() { return ruleId; }
    public String getSourceFile() { return sourceFile; }
    public int getStartLine() { return startLine; }
    public int getEndLine() { return endLine; }
    public int getStartPosition() { return startPosition; }
    public int getLength() { return length; }
    public String getOriginalSnippet() { return originalSnippet; }
    public String getProposedSnippet() { return proposedSnippet; }
    public double getConfidenceScore() { return confidenceScore; }
    public RiskTier getRiskTier() { return riskTier; }
    public List<SafetyInvariant> getInvariants() { return invariants; }
    public Map<String, Object> getAnalysisMetadata() { return analysisMetadata; }
    public String getRationale() { return rationale; }
    public Instant getAnalyzedAt() { return analyzedAt; }
    public ASTNode getAstNode() { return astNode; }

    /**
     * Returns true if all safety invariants are verified (none violated).
     */
    public boolean allInvariantsVerified() {
        return invariants.stream().noneMatch(SafetyInvariant::isViolated);
    }

    /**
     * Returns a list of violated invariants.
     */
    public List<SafetyInvariant> getViolatedInvariants() {
        return invariants.stream().filter(SafetyInvariant::isViolated).toList();
    }

    /**
     * Returns true if this candidate overlaps with another candidate in source location.
     */
    public boolean overlaps(RefactorCandidate other) {
        if (!this.sourceFile.equals(other.sourceFile)) return false;
        return this.startLine <= other.endLine && other.startLine <= this.endLine;
    }

    @Override
    public String toString() {
        return "RefactorCandidate{rule='%s', file='%s', lines=%d-%d, confidence=%.2f, invariants=%d}"
                .formatted(ruleId, sourceFile, startLine, endLine, confidenceScore, invariants.size());
    }

    /**
     * Builder for constructing RefactorCandidate instances.
     */
    public static Builder builder(String ruleId, String sourceFile) {
        return new Builder(ruleId, sourceFile);
    }

    public static final class Builder {
        private final String ruleId;
        private final String sourceFile;
        private UUID candidateId;
        private int startLine;
        private int endLine;
        private int startPosition;
        private int length;
        private String originalSnippet;
        private String proposedSnippet;
        private double confidenceScore;
        private RiskTier riskTier;
        private List<SafetyInvariant> invariants = new ArrayList<>();
        private Map<String, Object> analysisMetadata = new HashMap<>();
        private String rationale;
        private ASTNode astNode;

        private Builder(String ruleId, String sourceFile) {
            this.ruleId = ruleId;
            this.sourceFile = sourceFile;
        }

        public Builder candidateId(UUID id) { this.candidateId = id; return this; }
        public Builder startLine(int line) { this.startLine = line; return this; }
        public Builder endLine(int line) { this.endLine = line; return this; }
        public Builder startPosition(int pos) { this.startPosition = pos; return this; }
        public Builder length(int len) { this.length = len; return this; }
        public Builder originalSnippet(String s) { this.originalSnippet = s; return this; }
        public Builder proposedSnippet(String s) { this.proposedSnippet = s; return this; }
        public Builder confidenceScore(double score) { this.confidenceScore = score; return this; }
        public Builder riskTier(RiskTier tier) { this.riskTier = tier; return this; }
        public Builder invariants(List<SafetyInvariant> inv) { this.invariants = new ArrayList<>(inv); return this; }
        public Builder addInvariant(SafetyInvariant inv) { this.invariants.add(inv); return this; }
        public Builder analysisMetadata(Map<String, Object> meta) { this.analysisMetadata = new HashMap<>(meta); return this; }
        public Builder addMetadata(String key, Object value) { this.analysisMetadata.put(key, value); return this; }
        public Builder rationale(String r) { this.rationale = r; return this; }
        public Builder astNode(ASTNode node) { this.astNode = node; return this; }

        public RefactorCandidate build() {
            return new RefactorCandidate(candidateId, ruleId, sourceFile, startLine, endLine,
                    startPosition, length, originalSnippet, proposedSnippet, confidenceScore,
                    riskTier, invariants, analysisMetadata, rationale, astNode);
        }
    }
}
