package com.shadowstack.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Comprehensive patch detail including diff, rationale, invariants,
 * risk assessment, and verification evidence.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PatchDetailResponse(
        UUID patchId,
        UUID projectId,
        UUID candidateId,
        String ruleName,
        String ruleCategory,
        PatchStatus status,
        String filePath,
        int startLine,
        int endLine,
        String unifiedDiff,
        String rationale,
        List<Invariant> invariants,
        RiskAssessment risk,
        VerificationEvidence verificationEvidence,
        ReviewInfo review,
        Instant createdAt,
        Instant updatedAt
) {

    public enum PatchStatus {
        GENERATED,
        VERIFYING,
        VERIFIED,
        VERIFICATION_FAILED,
        PENDING_REVIEW,
        ACCEPTED,
        REJECTED,
        APPLIED
    }

    public record Invariant(
            String type,
            String description,
            String expression,
            boolean preserved
    ) {}

    public record RiskAssessment(
            double score,
            RiskTier tier,
            List<RiskFactor> factors,
            double confidenceScore
    ) {
        public enum RiskTier {
            LOW, MEDIUM, HIGH, CRITICAL
        }

        public record RiskFactor(
                String name,
                String description,
                double weight,
                double contribution
        ) {}
    }

    public record VerificationEvidence(
            boolean behaviorallyEquivalent,
            int testsPassed,
            int testsFailed,
            int testsSkipped,
            List<String> invariantsVerified,
            List<String> invariantsViolated,
            Map<String, Object> proofArtifacts,
            Instant verifiedAt
    ) {}

    public record ReviewInfo(
            String reviewer,
            boolean accepted,
            String reason,
            Instant reviewedAt
    ) {}
}
