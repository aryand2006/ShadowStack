package com.shadowstack.verify;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.shadowstack.verify.model.VerificationLayerResult;
import com.shadowstack.verify.model.Verdict;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.*;

/**
 * JSON-serializable certificate attesting to the behavioral equivalence of a code transformation.
 *
 * <p>The BehavioralEquivalenceCertificate is the final artifact produced by the ShadowStack
 * verification pipeline. It provides a comprehensive, auditable record of all verification
 * activities performed on a patch, including:</p>
 *
 * <ul>
 *   <li>The patch identity and timestamp</li>
 *   <li>Results from every verification layer</li>
 *   <li>The overall verdict and risk score</li>
 *   <li>AST hashes for both original and transformed code</li>
 *   <li>The verifier engine version</li>
 *   <li>A digital signature placeholder for future HMAC/PKI integration</li>
 * </ul>
 *
 * <p>Certificates are designed to be:</p>
 * <ul>
 *   <li><strong>Immutable</strong> — Once created, cannot be modified</li>
 *   <li><strong>Portable</strong> — JSON-serializable for storage and transmission</li>
 *   <li><strong>Tamper-evident</strong> — Content hash allows integrity verification</li>
 *   <li><strong>Auditable</strong> — Full provenance chain from detection through verification</li>
 * </ul>
 */
public final class BehavioralEquivalenceCertificate {

    private static final String VERIFIER_VERSION = "1.0.0-SNAPSHOT";
    private static final ObjectMapper MAPPER = createMapper();

    private final UUID certificateId;
    private final UUID patchId;
    private final Instant issuedAt;
    private final String verifierVersion;
    private final Verdict overallVerdict;
    private final double riskScore;
    private final double riskThreshold;
    private final List<VerificationLayerResult> layerResults;
    private final String originalAstHash;
    private final String transformedAstHash;
    private final String sourceFile;
    private final int startLine;
    private final int endLine;
    private final String ruleId;
    private final Map<String, Object> metadata;
    private final String contentHash;
    private final String digitalSignature;

    @JsonCreator
    public BehavioralEquivalenceCertificate(
            @JsonProperty("certificateId") UUID certificateId,
            @JsonProperty("patchId") UUID patchId,
            @JsonProperty("issuedAt") Instant issuedAt,
            @JsonProperty("verifierVersion") String verifierVersion,
            @JsonProperty("overallVerdict") Verdict overallVerdict,
            @JsonProperty("riskScore") double riskScore,
            @JsonProperty("riskThreshold") double riskThreshold,
            @JsonProperty("layerResults") List<VerificationLayerResult> layerResults,
            @JsonProperty("originalAstHash") String originalAstHash,
            @JsonProperty("transformedAstHash") String transformedAstHash,
            @JsonProperty("sourceFile") String sourceFile,
            @JsonProperty("startLine") int startLine,
            @JsonProperty("endLine") int endLine,
            @JsonProperty("ruleId") String ruleId,
            @JsonProperty("metadata") Map<String, Object> metadata,
            @JsonProperty("contentHash") String contentHash,
            @JsonProperty("digitalSignature") String digitalSignature) {
        this.certificateId = certificateId != null ? certificateId : UUID.randomUUID();
        this.patchId = Objects.requireNonNull(patchId, "patchId must not be null");
        this.issuedAt = issuedAt != null ? issuedAt : Instant.now();
        this.verifierVersion = verifierVersion != null ? verifierVersion : VERIFIER_VERSION;
        this.overallVerdict = Objects.requireNonNull(overallVerdict, "overallVerdict must not be null");
        this.riskScore = riskScore;
        this.riskThreshold = riskThreshold;
        this.layerResults = layerResults != null ? List.copyOf(layerResults) : List.of();
        this.originalAstHash = originalAstHash;
        this.transformedAstHash = transformedAstHash;
        this.sourceFile = sourceFile;
        this.startLine = startLine;
        this.endLine = endLine;
        this.ruleId = ruleId;
        this.metadata = metadata != null ? Map.copyOf(metadata) : Map.of();
        this.contentHash = contentHash;
        this.digitalSignature = digitalSignature;
    }

    // --- Getters ---

    public UUID getCertificateId() { return certificateId; }
    public UUID getPatchId() { return patchId; }
    public Instant getIssuedAt() { return issuedAt; }
    public String getVerifierVersion() { return verifierVersion; }
    public Verdict getOverallVerdict() { return overallVerdict; }
    public double getRiskScore() { return riskScore; }
    public double getRiskThreshold() { return riskThreshold; }
    public List<VerificationLayerResult> getLayerResults() { return layerResults; }
    public String getOriginalAstHash() { return originalAstHash; }
    public String getTransformedAstHash() { return transformedAstHash; }
    public String getSourceFile() { return sourceFile; }
    public int getStartLine() { return startLine; }
    public int getEndLine() { return endLine; }
    public String getRuleId() { return ruleId; }
    public Map<String, Object> getMetadata() { return metadata; }
    public String getContentHash() { return contentHash; }
    public String getDigitalSignature() { return digitalSignature; }

    /**
     * Returns true if the certificate indicates a passing verification.
     */
    public boolean isPassing() {
        return overallVerdict == Verdict.PASS;
    }

    /**
     * Returns true if the risk score is within the acceptable threshold.
     */
    public boolean isWithinRiskThreshold() {
        return riskScore <= riskThreshold;
    }

    /**
     * Returns the number of layers that failed.
     */
    public long failedLayerCount() {
        return layerResults.stream().filter(VerificationLayerResult::failed).count();
    }

    /**
     * Serializes this certificate to a JSON string.
     */
    public String toJson() throws IOException {
        return MAPPER.writeValueAsString(this);
    }

    /**
     * Serializes this certificate to a pretty-printed JSON string.
     */
    public String toJsonPretty() throws IOException {
        return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(this);
    }

    /**
     * Deserializes a certificate from a JSON string.
     */
    public static BehavioralEquivalenceCertificate fromJson(String json) throws IOException {
        return MAPPER.readValue(json, BehavioralEquivalenceCertificate.class);
    }

    /**
     * Verifies the content hash integrity of this certificate.
     *
     * @return true if the content hash matches, or if no hash was set
     */
    public boolean verifyIntegrity() {
        if (contentHash == null) return true;
        String computed = computeContentHash();
        return contentHash.equals(computed);
    }

    /**
     * Computes a SHA-256 hash of the certificate's core content (excluding the hash and signature fields).
     */
    private String computeContentHash() {
        String content = "%s|%s|%s|%s|%.6f|%.6f|%s|%s|%s|%d|%d|%s|%d"
                .formatted(certificateId, patchId, issuedAt, overallVerdict,
                        riskScore, riskThreshold, originalAstHash, transformedAstHash,
                        sourceFile, startLine, endLine, ruleId, layerResults.size());
        return sha256(content);
    }

    private static String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            return "hash-unavailable";
        }
    }

    private static ObjectMapper createMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false);
        mapper.configure(SerializationFeature.INDENT_OUTPUT, false);
        return mapper;
    }

    @Override
    public String toString() {
        return "BehavioralEquivalenceCertificate{id=%s, patch=%s, verdict=%s, risk=%.3f, layers=%d}"
                .formatted(certificateId, patchId, overallVerdict, riskScore, layerResults.size());
    }

    // --- Builder ---

    /**
     * Creates a certificate from a pipeline result.
     */
    public static Builder fromPipelineResult(VerificationPipeline.PipelineResult pipelineResult) {
        return new Builder(pipelineResult.patchId())
                .overallVerdict(pipelineResult.verdict())
                .riskScore(pipelineResult.riskScore())
                .riskThreshold(pipelineResult.riskThreshold())
                .layerResults(pipelineResult.layerResults());
    }

    public static Builder builder(UUID patchId) {
        return new Builder(patchId);
    }

    public static final class Builder {
        private final UUID patchId;
        private UUID certificateId;
        private Instant issuedAt;
        private String verifierVersion;
        private Verdict overallVerdict;
        private double riskScore;
        private double riskThreshold;
        private List<VerificationLayerResult> layerResults;
        private String originalAstHash;
        private String transformedAstHash;
        private String sourceFile;
        private int startLine;
        private int endLine;
        private String ruleId;
        private Map<String, Object> metadata = new LinkedHashMap<>();
        private String digitalSignature;

        private Builder(UUID patchId) {
            this.patchId = patchId;
        }

        public Builder certificateId(UUID id) { this.certificateId = id; return this; }
        public Builder issuedAt(Instant t) { this.issuedAt = t; return this; }
        public Builder verifierVersion(String v) { this.verifierVersion = v; return this; }
        public Builder overallVerdict(Verdict v) { this.overallVerdict = v; return this; }
        public Builder riskScore(double s) { this.riskScore = s; return this; }
        public Builder riskThreshold(double t) { this.riskThreshold = t; return this; }
        public Builder layerResults(List<VerificationLayerResult> r) { this.layerResults = r; return this; }
        public Builder originalAstHash(String h) { this.originalAstHash = h; return this; }
        public Builder transformedAstHash(String h) { this.transformedAstHash = h; return this; }
        public Builder sourceFile(String f) { this.sourceFile = f; return this; }
        public Builder startLine(int l) { this.startLine = l; return this; }
        public Builder endLine(int l) { this.endLine = l; return this; }
        public Builder ruleId(String id) { this.ruleId = id; return this; }
        public Builder metadata(Map<String, Object> m) { this.metadata = new LinkedHashMap<>(m); return this; }
        public Builder addMetadata(String key, Object value) { this.metadata.put(key, value); return this; }
        public Builder digitalSignature(String sig) { this.digitalSignature = sig; return this; }

        /**
         * Builds the certificate, computing the content hash automatically.
         */
        public BehavioralEquivalenceCertificate build() {
            BehavioralEquivalenceCertificate cert = new BehavioralEquivalenceCertificate(
                    certificateId, patchId, issuedAt, verifierVersion,
                    overallVerdict, riskScore, riskThreshold, layerResults,
                    originalAstHash, transformedAstHash, sourceFile, startLine, endLine,
                    ruleId, metadata, null, digitalSignature);

            // Compute content hash
            String hash = cert.computeContentHash();

            // Rebuild with hash included
            return new BehavioralEquivalenceCertificate(
                    cert.certificateId, patchId, cert.issuedAt, cert.verifierVersion,
                    overallVerdict, riskScore, riskThreshold, layerResults,
                    originalAstHash, transformedAstHash, sourceFile, startLine, endLine,
                    ruleId, metadata, hash, digitalSignature);
        }
    }
}
