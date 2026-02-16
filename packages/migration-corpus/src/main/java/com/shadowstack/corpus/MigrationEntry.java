package com.shadowstack.corpus;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * JPA entity representing a single migration transformation record in the corpus.
 * Each entry captures a before/after code snippet pair along with metadata about
 * the transformation rule, verification results, and developer feedback.
 */
@Entity
@Table(name = "migration_entries", indexes = {
        @Index(name = "idx_migration_entries_rule", columnList = "ruleId"),
        @Index(name = "idx_migration_entries_acceptance", columnList = "developerAccepted"),
        @Index(name = "idx_migration_entries_risk", columnList = "riskTier"),
        @Index(name = "idx_migration_entries_created", columnList = "createdAt")
})
public class MigrationEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "rule_id", nullable = false, length = 128)
    private String ruleId;

    @Column(name = "rule_name", nullable = false, length = 256)
    private String ruleName;

    @Column(name = "source_language", nullable = false, length = 64)
    private String sourceLanguage;

    @Column(name = "source_version", nullable = false, length = 32)
    private String sourceVersion;

    @Column(name = "target_language", nullable = false, length = 64)
    private String targetLanguage;

    @Column(name = "target_version", nullable = false, length = 32)
    private String targetVersion;

    @Column(name = "before_snippet", nullable = false, columnDefinition = "TEXT")
    private String beforeSnippet;

    @Column(name = "after_snippet", nullable = false, columnDefinition = "TEXT")
    private String afterSnippet;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "ast_context", columnDefinition = "jsonb")
    private JsonNode astContext;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "libraries_involved", columnDefinition = "jsonb")
    private JsonNode librariesInvolved;

    @Column(name = "risk_tier", nullable = false, length = 32)
    private String riskTier;

    @Column(name = "confidence_score", nullable = false)
    private double confidenceScore;

    @Column(name = "verification_passed", nullable = false)
    private boolean verificationPassed;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "verification_metrics", columnDefinition = "jsonb")
    private JsonNode verificationMetrics;

    @Column(name = "developer_accepted")
    private Boolean developerAccepted;

    @Column(name = "rejection_reason", columnDefinition = "TEXT")
    private String rejectionReason;

    @Column(name = "time_to_accept_ms")
    private Long timeToAcceptMs;

    @Column(name = "embedding", columnDefinition = "vector(768)")
    private float[] embedding;

    @Column(name = "before_ast_hash", length = 128)
    private String beforeAstHash;

    @Column(name = "after_ast_hash", length = 128)
    private String afterAstHash;

    @Column(name = "project_id")
    private UUID projectId;

    @Column(name = "created_at", columnDefinition = "TIMESTAMP WITH TIME ZONE")
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", columnDefinition = "TIMESTAMP WITH TIME ZONE")
    private OffsetDateTime updatedAt;

    public MigrationEntry() {
    }

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = OffsetDateTime.now();
        }
        updatedAt = OffsetDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = OffsetDateTime.now();
    }

    // --- Getters and Setters ---

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public String getRuleId() {
        return ruleId;
    }

    public void setRuleId(String ruleId) {
        this.ruleId = ruleId;
    }

    public String getRuleName() {
        return ruleName;
    }

    public void setRuleName(String ruleName) {
        this.ruleName = ruleName;
    }

    public String getSourceLanguage() {
        return sourceLanguage;
    }

    public void setSourceLanguage(String sourceLanguage) {
        this.sourceLanguage = sourceLanguage;
    }

    public String getSourceVersion() {
        return sourceVersion;
    }

    public void setSourceVersion(String sourceVersion) {
        this.sourceVersion = sourceVersion;
    }

    public String getTargetLanguage() {
        return targetLanguage;
    }

    public void setTargetLanguage(String targetLanguage) {
        this.targetLanguage = targetLanguage;
    }

    public String getTargetVersion() {
        return targetVersion;
    }

    public void setTargetVersion(String targetVersion) {
        this.targetVersion = targetVersion;
    }

    public String getBeforeSnippet() {
        return beforeSnippet;
    }

    public void setBeforeSnippet(String beforeSnippet) {
        this.beforeSnippet = beforeSnippet;
    }

    public String getAfterSnippet() {
        return afterSnippet;
    }

    public void setAfterSnippet(String afterSnippet) {
        this.afterSnippet = afterSnippet;
    }

    public JsonNode getAstContext() {
        return astContext;
    }

    public void setAstContext(JsonNode astContext) {
        this.astContext = astContext;
    }

    public JsonNode getLibrariesInvolved() {
        return librariesInvolved;
    }

    public void setLibrariesInvolved(JsonNode librariesInvolved) {
        this.librariesInvolved = librariesInvolved;
    }

    public String getRiskTier() {
        return riskTier;
    }

    public void setRiskTier(String riskTier) {
        this.riskTier = riskTier;
    }

    public double getConfidenceScore() {
        return confidenceScore;
    }

    public void setConfidenceScore(double confidenceScore) {
        this.confidenceScore = confidenceScore;
    }

    public boolean isVerificationPassed() {
        return verificationPassed;
    }

    public void setVerificationPassed(boolean verificationPassed) {
        this.verificationPassed = verificationPassed;
    }

    public JsonNode getVerificationMetrics() {
        return verificationMetrics;
    }

    public void setVerificationMetrics(JsonNode verificationMetrics) {
        this.verificationMetrics = verificationMetrics;
    }

    public Boolean getDeveloperAccepted() {
        return developerAccepted;
    }

    public void setDeveloperAccepted(Boolean developerAccepted) {
        this.developerAccepted = developerAccepted;
    }

    public String getRejectionReason() {
        return rejectionReason;
    }

    public void setRejectionReason(String rejectionReason) {
        this.rejectionReason = rejectionReason;
    }

    public Long getTimeToAcceptMs() {
        return timeToAcceptMs;
    }

    public void setTimeToAcceptMs(Long timeToAcceptMs) {
        this.timeToAcceptMs = timeToAcceptMs;
    }

    public float[] getEmbedding() {
        return embedding;
    }

    public void setEmbedding(float[] embedding) {
        this.embedding = embedding;
    }

    public String getBeforeAstHash() {
        return beforeAstHash;
    }

    public void setBeforeAstHash(String beforeAstHash) {
        this.beforeAstHash = beforeAstHash;
    }

    public String getAfterAstHash() {
        return afterAstHash;
    }

    public void setAfterAstHash(String afterAstHash) {
        this.afterAstHash = afterAstHash;
    }

    public UUID getProjectId() {
        return projectId;
    }

    public void setProjectId(UUID projectId) {
        this.projectId = projectId;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(OffsetDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(OffsetDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }

    @Override
    public String toString() {
        return "MigrationEntry{" +
                "id=" + id +
                ", ruleId='" + ruleId + '\'' +
                ", ruleName='" + ruleName + '\'' +
                ", sourceLanguage='" + sourceLanguage + '\'' +
                ", targetLanguage='" + targetLanguage + '\'' +
                ", riskTier='" + riskTier + '\'' +
                ", confidenceScore=" + confidenceScore +
                ", developerAccepted=" + developerAccepted +
                '}';
    }
}
