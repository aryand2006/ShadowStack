package com.shadowstack.refactor.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.*;

/**
 * An atomic, self-contained patch representing a single refactoring transformation.
 *
 * <p>PatchUnit is the fundamental unit of work in the ShadowStack refactoring pipeline.
 * Each patch is independent, non-overlapping with other patches, and carries full provenance
 * information including the rule that generated it, confidence scoring, safety invariants,
 * and a human-readable rationale.</p>
 *
 * <p>Patches are designed to be:</p>
 * <ul>
 *   <li><strong>Atomic</strong> — Each patch is a single, indivisible transformation</li>
 *   <li><strong>Independent</strong> — Patches can be applied in any order without conflicts</li>
 *   <li><strong>Traceable</strong> — Full audit trail from detection through verification</li>
 *   <li><strong>Reversible</strong> — Before/after snippets enable rollback</li>
 * </ul>
 */
public final class PatchUnit {

    private final UUID patchId;
    private final String ruleId;
    private final String sourceFile;
    private final int startLine;
    private final int endLine;
    private final String beforeSnippet;
    private final String afterSnippet;
    private final String unifiedDiff;
    private final double confidenceScore;
    private final RiskTier riskTier;
    private final List<SafetyInvariant> invariants;
    private final Map<String, Object> metadata;
    private final String rationale;
    private final Instant createdAt;

    @JsonCreator
    public PatchUnit(
            @JsonProperty("patchId") UUID patchId,
            @JsonProperty("ruleId") String ruleId,
            @JsonProperty("sourceFile") String sourceFile,
            @JsonProperty("startLine") int startLine,
            @JsonProperty("endLine") int endLine,
            @JsonProperty("beforeSnippet") String beforeSnippet,
            @JsonProperty("afterSnippet") String afterSnippet,
            @JsonProperty("unifiedDiff") String unifiedDiff,
            @JsonProperty("confidenceScore") double confidenceScore,
            @JsonProperty("riskTier") RiskTier riskTier,
            @JsonProperty("invariants") List<SafetyInvariant> invariants,
            @JsonProperty("metadata") Map<String, Object> metadata,
            @JsonProperty("rationale") String rationale,
            @JsonProperty("createdAt") Instant createdAt) {
        this.patchId = patchId != null ? patchId : UUID.randomUUID();
        this.ruleId = Objects.requireNonNull(ruleId, "ruleId must not be null");
        this.sourceFile = Objects.requireNonNull(sourceFile, "sourceFile must not be null");
        this.startLine = startLine;
        this.endLine = endLine;
        this.beforeSnippet = Objects.requireNonNull(beforeSnippet, "beforeSnippet must not be null");
        this.afterSnippet = Objects.requireNonNull(afterSnippet, "afterSnippet must not be null");
        this.unifiedDiff = unifiedDiff;
        this.confidenceScore = Math.max(0.0, Math.min(1.0, confidenceScore));
        this.riskTier = riskTier != null ? riskTier : RiskTier.MEDIUM;
        this.invariants = invariants != null ? List.copyOf(invariants) : List.of();
        this.metadata = metadata != null ? Map.copyOf(metadata) : Map.of();
        this.rationale = rationale;
        this.createdAt = createdAt != null ? createdAt : Instant.now();
    }

    // --- Getters ---

    public UUID getPatchId() { return patchId; }
    public String getRuleId() { return ruleId; }
    public String getSourceFile() { return sourceFile; }
    public int getStartLine() { return startLine; }
    public int getEndLine() { return endLine; }
    public String getBeforeSnippet() { return beforeSnippet; }
    public String getAfterSnippet() { return afterSnippet; }
    public String getUnifiedDiff() { return unifiedDiff; }
    public double getConfidenceScore() { return confidenceScore; }
    public RiskTier getRiskTier() { return riskTier; }
    public List<SafetyInvariant> getInvariants() { return invariants; }
    public Map<String, Object> getMetadata() { return metadata; }
    public String getRationale() { return rationale; }
    public Instant getCreatedAt() { return createdAt; }

    /**
     * Returns true if all safety invariants are verified.
     */
    public boolean allInvariantsHold() {
        return invariants.stream().allMatch(SafetyInvariant::isVerified);
    }

    /**
     * Returns the number of lines changed by this patch.
     */
    public int lineSpan() {
        return endLine - startLine + 1;
    }

    /**
     * Returns true if this patch overlaps with another patch in the same file.
     * Used to ensure patch independence.
     */
    public boolean overlapsWith(PatchUnit other) {
        if (!this.sourceFile.equals(other.sourceFile)) {
            return false;
        }
        return this.startLine <= other.endLine && other.startLine <= this.endLine;
    }

    /**
     * Computes a unified diff between before and after snippets.
     *
     * @param beforeLines the original source lines
     * @param afterLines  the transformed source lines
     * @param fileName    the file name for the diff header
     * @param startLine   the starting line number in the original file
     * @return a unified diff string
     */
    public static String computeUnifiedDiff(
            List<String> beforeLines, List<String> afterLines, String fileName, int startLine) {
        StringBuilder diff = new StringBuilder();
        diff.append("--- a/").append(fileName).append('\n');
        diff.append("+++ b/").append(fileName).append('\n');

        // Simple diff: show all before lines as removed, all after lines as added
        // For a production system, this would use a proper LCS-based diff algorithm
        int beforeCount = beforeLines.size();
        int afterCount = afterLines.size();

        diff.append("@@ -%d,%d +%d,%d @@\n".formatted(startLine, beforeCount, startLine, afterCount));

        // Use a longest common subsequence approach for meaningful diffs
        int[][] lcs = computeLCSTable(beforeLines, afterLines);
        List<DiffLine> diffLines = backtrackDiff(lcs, beforeLines, afterLines,
                beforeLines.size(), afterLines.size());

        for (DiffLine line : diffLines) {
            switch (line.type) {
                case CONTEXT -> diff.append(' ').append(line.content).append('\n');
                case REMOVED -> diff.append('-').append(line.content).append('\n');
                case ADDED   -> diff.append('+').append(line.content).append('\n');
            }
        }

        return diff.toString();
    }

    private static int[][] computeLCSTable(List<String> a, List<String> b) {
        int m = a.size();
        int n = b.size();
        int[][] table = new int[m + 1][n + 1];
        for (int i = 1; i <= m; i++) {
            for (int j = 1; j <= n; j++) {
                if (a.get(i - 1).equals(b.get(j - 1))) {
                    table[i][j] = table[i - 1][j - 1] + 1;
                } else {
                    table[i][j] = Math.max(table[i - 1][j], table[i][j - 1]);
                }
            }
        }
        return table;
    }

    private static List<DiffLine> backtrackDiff(
            int[][] lcs, List<String> a, List<String> b, int i, int j) {
        List<DiffLine> result = new ArrayList<>();
        backtrackRecursive(lcs, a, b, i, j, result);
        return result;
    }

    private static void backtrackRecursive(
            int[][] lcs, List<String> a, List<String> b, int i, int j, List<DiffLine> result) {
        if (i > 0 && j > 0 && a.get(i - 1).equals(b.get(j - 1))) {
            backtrackRecursive(lcs, a, b, i - 1, j - 1, result);
            result.add(new DiffLine(DiffLineType.CONTEXT, a.get(i - 1)));
        } else if (j > 0 && (i == 0 || lcs[i][j - 1] >= lcs[i - 1][j])) {
            backtrackRecursive(lcs, a, b, i, j - 1, result);
            result.add(new DiffLine(DiffLineType.ADDED, b.get(j - 1)));
        } else if (i > 0 && (j == 0 || lcs[i][j - 1] < lcs[i - 1][j])) {
            backtrackRecursive(lcs, a, b, i - 1, j, result);
            result.add(new DiffLine(DiffLineType.REMOVED, a.get(i - 1)));
        }
    }

    private enum DiffLineType { CONTEXT, REMOVED, ADDED }

    private record DiffLine(DiffLineType type, String content) {}

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof PatchUnit that)) return false;
        return patchId.equals(that.patchId);
    }

    @Override
    public int hashCode() {
        return patchId.hashCode();
    }

    @Override
    public String toString() {
        return "PatchUnit{id=%s, rule='%s', file='%s', lines=%d-%d, confidence=%.2f, risk=%s}"
                .formatted(patchId, ruleId, sourceFile, startLine, endLine, confidenceScore, riskTier);
    }

    // --- Builder ---

    public static Builder builder(String ruleId, String sourceFile) {
        return new Builder(ruleId, sourceFile);
    }

    public static final class Builder {
        private final String ruleId;
        private final String sourceFile;
        private UUID patchId;
        private int startLine;
        private int endLine;
        private String beforeSnippet;
        private String afterSnippet;
        private String unifiedDiff;
        private double confidenceScore;
        private RiskTier riskTier;
        private List<SafetyInvariant> invariants = new ArrayList<>();
        private Map<String, Object> metadata = new HashMap<>();
        private String rationale;
        private Instant createdAt;

        private Builder(String ruleId, String sourceFile) {
            this.ruleId = ruleId;
            this.sourceFile = sourceFile;
        }

        public Builder patchId(UUID id) { this.patchId = id; return this; }
        public Builder startLine(int line) { this.startLine = line; return this; }
        public Builder endLine(int line) { this.endLine = line; return this; }
        public Builder beforeSnippet(String s) { this.beforeSnippet = s; return this; }
        public Builder afterSnippet(String s) { this.afterSnippet = s; return this; }
        public Builder unifiedDiff(String d) { this.unifiedDiff = d; return this; }
        public Builder confidenceScore(double score) { this.confidenceScore = score; return this; }
        public Builder riskTier(RiskTier tier) { this.riskTier = tier; return this; }
        public Builder invariants(List<SafetyInvariant> inv) { this.invariants = new ArrayList<>(inv); return this; }
        public Builder addInvariant(SafetyInvariant inv) { this.invariants.add(inv); return this; }
        public Builder metadata(Map<String, Object> meta) { this.metadata = new HashMap<>(meta); return this; }
        public Builder addMetadata(String key, Object value) { this.metadata.put(key, value); return this; }
        public Builder rationale(String r) { this.rationale = r; return this; }
        public Builder createdAt(Instant t) { this.createdAt = t; return this; }

        public PatchUnit build() {
            return new PatchUnit(patchId, ruleId, sourceFile, startLine, endLine,
                    beforeSnippet, afterSnippet, unifiedDiff, confidenceScore,
                    riskTier, invariants, metadata, rationale, createdAt);
        }
    }
}
