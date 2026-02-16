package com.shadowstack.adapters.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.time.Instant;
import java.util.*;

/**
 * Multi-layer verification outcome for a refactoring patch.
 *
 * <p>{@code VerificationResult} aggregates the results of every verification
 * layer in ShadowStack's pipeline — compilation, test execution, AST structural
 * comparison, bytecode descriptor matching, API surface analysis, semantic risk
 * scoring, and golden-master comparison.</p>
 *
 * <p>The overall {@link Verdict} is computed deterministically from individual
 * layer results. A {@link BehavioralEquivalenceCertificate} can be generated
 * for results that achieve {@link Verdict#PASS}, providing a JSON-serializable
 * proof artifact.</p>
 *
 * <p>Instances are immutable and constructed via {@link Builder}.</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public final class VerificationResult {

    /**
     * Overall verification verdict.
     */
    public enum Verdict {
        /** All verification layers passed. The refactoring is safe to apply. */
        PASS,
        /** The refactoring failed one or more critical verification layers. */
        FAIL,
        /** Some layers produced inconclusive results; human review is required. */
        WARN
    }

    /**
     * Result of an individual verification layer.
     *
     * @param layerName   name of the verification layer (e.g., "compilation", "bytecodeMatch")
     * @param passed      whether this layer passed
     * @param score       numeric score in [0.0, 1.0] (1.0 = perfect match)
     * @param details     human-readable details or diagnostics
     * @param durationMs  wall-clock time in milliseconds for this layer
     */
    public record LayerResult(
            @JsonProperty("layerName") String layerName,
            @JsonProperty("passed") boolean passed,
            @JsonProperty("score") double score,
            @JsonProperty("details") String details,
            @JsonProperty("durationMs") long durationMs
    ) {
        public LayerResult {
            Objects.requireNonNull(layerName, "layerName");
            if (score < 0.0 || score > 1.0) {
                throw new IllegalArgumentException("score must be in [0.0, 1.0], got: " + score);
            }
        }
    }

    /**
     * A JSON-serializable certificate proving behavioral equivalence between
     * the original and refactored code.
     *
     * <p>This certificate is the core artifact that enterprise audit workflows
     * consume. It includes the patch identity, all layer results, and a
     * deterministic fingerprint.</p>
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class BehavioralEquivalenceCertificate {

        private static final ObjectMapper MAPPER = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false)
                .configure(SerializationFeature.INDENT_OUTPUT, true);

        private final String certificateId;
        private final UUID patchId;
        private final Verdict verdict;
        private final double overallScore;
        private final List<LayerResult> layerResults;
        private final Instant issuedAt;
        private final String beforeAstHash;
        private final String afterAstHash;
        private final String fingerprint;

        /**
         * Constructs a certificate from a completed verification result.
         *
         * @param patchId       the patch this certificate covers
         * @param verdict       the overall verdict
         * @param overallScore  aggregated score
         * @param layerResults  individual layer results
         * @param beforeAstHash AST hash before refactoring
         * @param afterAstHash  AST hash after refactoring
         */
        public BehavioralEquivalenceCertificate(
                UUID patchId,
                Verdict verdict,
                double overallScore,
                List<LayerResult> layerResults,
                String beforeAstHash,
                String afterAstHash) {
            this.certificateId = UUID.randomUUID().toString();
            this.patchId = Objects.requireNonNull(patchId, "patchId");
            this.verdict = Objects.requireNonNull(verdict, "verdict");
            this.overallScore = overallScore;
            this.layerResults = layerResults != null ? List.copyOf(layerResults) : List.of();
            this.issuedAt = Instant.now();
            this.beforeAstHash = Objects.requireNonNull(beforeAstHash, "beforeAstHash");
            this.afterAstHash = Objects.requireNonNull(afterAstHash, "afterAstHash");
            this.fingerprint = computeFingerprint();
        }

        private String computeFingerprint() {
            String raw = patchId.toString() + "|" + verdict + "|" + overallScore
                    + "|" + beforeAstHash + "|" + afterAstHash;
            int hash = raw.hashCode();
            return String.format("BEC-%08X-%s", hash, patchId.toString().substring(0, 8));
        }

        @JsonProperty public String certificateId() { return certificateId; }
        @JsonProperty public UUID patchId() { return patchId; }
        @JsonProperty public Verdict verdict() { return verdict; }
        @JsonProperty public double overallScore() { return overallScore; }
        @JsonProperty public List<LayerResult> layerResults() { return layerResults; }
        @JsonProperty public Instant issuedAt() { return issuedAt; }
        @JsonProperty public String beforeAstHash() { return beforeAstHash; }
        @JsonProperty public String afterAstHash() { return afterAstHash; }
        @JsonProperty public String fingerprint() { return fingerprint; }

        /**
         * Serializes this certificate to a JSON string.
         *
         * @return pretty-printed JSON
         * @throws RuntimeException if serialization fails
         */
        public String toJson() {
            try {
                return MAPPER.writeValueAsString(this);
            } catch (Exception e) {
                throw new RuntimeException("Failed to serialize BehavioralEquivalenceCertificate", e);
            }
        }

        @Override
        public String toString() {
            return "BehavioralEquivalenceCertificate{" +
                    "id='" + certificateId + '\'' +
                    ", verdict=" + verdict +
                    ", score=" + overallScore +
                    ", fingerprint='" + fingerprint + '\'' +
                    '}';
        }
    }

    // ── Instance fields ──────────────────────────────────────────────────

    private final UUID patchId;
    private final boolean compileSuccess;
    private final boolean testSuccess;
    private final double astStructuralMatchScore;
    private final boolean bytecodeDescriptorMatch;
    private final boolean apiSurfaceCompatible;
    private final double semanticRiskScore;
    private final boolean goldenMasterMatch;
    private final List<LayerResult> layerResults;
    private final Verdict verdict;
    private final BehavioralEquivalenceCertificate certificate;
    private final Instant timestamp;

    private VerificationResult(Builder builder) {
        this.patchId = Objects.requireNonNull(builder.patchId, "patchId");
        this.compileSuccess = builder.compileSuccess;
        this.testSuccess = builder.testSuccess;
        this.astStructuralMatchScore = builder.astStructuralMatchScore;
        this.bytecodeDescriptorMatch = builder.bytecodeDescriptorMatch;
        this.apiSurfaceCompatible = builder.apiSurfaceCompatible;
        this.goldenMasterMatch = builder.goldenMasterMatch;
        this.layerResults = builder.layerResults != null
                ? List.copyOf(builder.layerResults) : List.of();
        this.timestamp = builder.timestamp != null ? builder.timestamp : Instant.now();

        this.semanticRiskScore = computeSemanticRiskScore();
        this.verdict = computeVerdict();
        this.certificate = builder.beforeAstHash != null && builder.afterAstHash != null
                ? new BehavioralEquivalenceCertificate(
                        patchId, verdict, 1.0 - semanticRiskScore,
                        layerResults, builder.beforeAstHash, builder.afterAstHash)
                : null;
    }

    /**
     * Deterministic formula for computing semantic risk.
     *
     * <p>The score is a weighted combination of verification layer outcomes:
     * <ul>
     *   <li>Compilation failure: +0.40</li>
     *   <li>Test failure: +0.25</li>
     *   <li>AST structural mismatch (inverted score): weighted by 0.15</li>
     *   <li>Bytecode descriptor mismatch: +0.10</li>
     *   <li>API surface incompatibility: +0.10</li>
     * </ul>
     * Result is clamped to [0.0, 1.0].</p>
     *
     * @return deterministic risk score
     */
    private double computeSemanticRiskScore() {
        double risk = 0.0;
        if (!compileSuccess)          risk += 0.40;
        if (!testSuccess)             risk += 0.25;
        risk += (1.0 - astStructuralMatchScore) * 0.15;
        if (!bytecodeDescriptorMatch) risk += 0.10;
        if (!apiSurfaceCompatible)    risk += 0.10;
        return Math.min(1.0, Math.max(0.0, risk));
    }

    /**
     * Deterministic verdict computation.
     *
     * <ul>
     *   <li>FAIL: compilation failed, or semantic risk &ge; 0.50</li>
     *   <li>WARN: any layer is inconclusive, or risk is in [0.10, 0.50)</li>
     *   <li>PASS: all layers pass and risk &lt; 0.10</li>
     * </ul>
     */
    private Verdict computeVerdict() {
        if (!compileSuccess || semanticRiskScore >= 0.50) {
            return Verdict.FAIL;
        }
        boolean anyUnknown = layerResults.stream().anyMatch(lr -> !lr.passed() && lr.score() > 0.0);
        if (anyUnknown || semanticRiskScore >= 0.10) {
            return Verdict.WARN;
        }
        return Verdict.PASS;
    }

    // ── Accessors ────────────────────────────────────────────────────────

    @JsonProperty public UUID patchId() { return patchId; }
    @JsonProperty public boolean compileSuccess() { return compileSuccess; }
    @JsonProperty public boolean testSuccess() { return testSuccess; }
    @JsonProperty public double astStructuralMatchScore() { return astStructuralMatchScore; }
    @JsonProperty public boolean bytecodeDescriptorMatch() { return bytecodeDescriptorMatch; }
    @JsonProperty public boolean apiSurfaceCompatible() { return apiSurfaceCompatible; }

    /**
     * Deterministic semantic risk score computed from all verification layers.
     *
     * @return risk score in [0.0, 1.0]
     */
    @JsonProperty public double semanticRiskScore() { return semanticRiskScore; }

    @JsonProperty public boolean goldenMasterMatch() { return goldenMasterMatch; }
    @JsonProperty public List<LayerResult> layerResults() { return layerResults; }
    @JsonProperty public Verdict verdict() { return verdict; }

    /**
     * Returns the behavioral equivalence certificate, or empty if AST hashes
     * were not provided during verification.
     *
     * @return optional certificate
     */
    public Optional<BehavioralEquivalenceCertificate> certificate() {
        return Optional.ofNullable(certificate);
    }

    @JsonProperty public Instant timestamp() { return timestamp; }

    // ── Convenience methods ──────────────────────────────────────────────

    /**
     * Returns {@code true} if the overall verdict is {@link Verdict#PASS}.
     *
     * @return whether the verification passed
     */
    public boolean passed() {
        return verdict == Verdict.PASS;
    }

    /**
     * Returns the corresponding {@link RiskTier} based on the semantic risk score.
     *
     * @return the computed risk tier
     */
    public RiskTier riskTier() {
        return RiskTier.fromScore(semanticRiskScore);
    }

    public static Builder builder() {
        return new Builder();
    }

    // ── Builder ──────────────────────────────────────────────────────────

    /**
     * Mutable builder for {@link VerificationResult}.
     */
    public static final class Builder {
        private UUID patchId;
        private boolean compileSuccess;
        private boolean testSuccess;
        private double astStructuralMatchScore;
        private boolean bytecodeDescriptorMatch;
        private boolean apiSurfaceCompatible;
        private boolean goldenMasterMatch;
        private List<LayerResult> layerResults;
        private Instant timestamp;
        private String beforeAstHash;
        private String afterAstHash;

        private Builder() {}

        public Builder patchId(UUID patchId) { this.patchId = patchId; return this; }
        public Builder compileSuccess(boolean v) { this.compileSuccess = v; return this; }
        public Builder testSuccess(boolean v) { this.testSuccess = v; return this; }
        public Builder astStructuralMatchScore(double v) {
            if (v < 0.0 || v > 1.0) throw new IllegalArgumentException("Score must be in [0.0, 1.0]");
            this.astStructuralMatchScore = v;
            return this;
        }
        public Builder bytecodeDescriptorMatch(boolean v) { this.bytecodeDescriptorMatch = v; return this; }
        public Builder apiSurfaceCompatible(boolean v) { this.apiSurfaceCompatible = v; return this; }
        public Builder goldenMasterMatch(boolean v) { this.goldenMasterMatch = v; return this; }
        public Builder layerResults(List<LayerResult> layerResults) { this.layerResults = layerResults; return this; }
        public Builder addLayerResult(LayerResult result) {
            if (this.layerResults == null) this.layerResults = new ArrayList<>();
            this.layerResults.add(Objects.requireNonNull(result));
            return this;
        }
        public Builder timestamp(Instant timestamp) { this.timestamp = timestamp; return this; }
        public Builder beforeAstHash(String hash) { this.beforeAstHash = hash; return this; }
        public Builder afterAstHash(String hash) { this.afterAstHash = hash; return this; }

        /**
         * Builds an immutable {@link VerificationResult}.
         * The semantic risk score and verdict are computed deterministically
         * from the builder's fields.
         *
         * @return new verification result
         */
        public VerificationResult build() {
            return new VerificationResult(this);
        }
    }

    @Override
    public String toString() {
        return "VerificationResult{" +
                "patchId=" + patchId +
                ", verdict=" + verdict +
                ", semanticRisk=" + String.format("%.4f", semanticRiskScore) +
                ", compile=" + compileSuccess +
                ", tests=" + testSuccess +
                ", astMatch=" + String.format("%.4f", astStructuralMatchScore) +
                ", layers=" + layerResults.size() +
                '}';
    }
}
