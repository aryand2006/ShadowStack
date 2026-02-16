package com.shadowstack.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Verification pipeline results for a patch, including test outcomes
 * and behavioral equivalence certificate status.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record VerificationResultResponse(
        UUID patchId,
        UUID verificationId,
        VerificationStatus status,
        boolean behaviorallyEquivalent,
        TestResults testResults,
        InvariantResults invariantResults,
        CertificateInfo certificate,
        long durationMs,
        Instant startedAt,
        Instant completedAt
) {

    public enum VerificationStatus {
        PENDING,
        RUNNING,
        PASSED,
        FAILED,
        ERROR,
        TIMEOUT
    }

    public record TestResults(
            int totalTests,
            int passed,
            int failed,
            int skipped,
            int errors,
            List<TestFailure> failures
    ) {
        public record TestFailure(
                String testClass,
                String testMethod,
                String message,
                String stackTrace
        ) {}
    }

    public record InvariantResults(
            int totalInvariants,
            int preserved,
            int violated,
            List<InvariantCheck> checks
    ) {
        public record InvariantCheck(
                String invariantId,
                String description,
                boolean preserved,
                String evidence
        ) {}
    }

    public record CertificateInfo(
            UUID certificateId,
            boolean issued,
            String summary,
            Map<String, Object> proofArtifacts,
            Instant issuedAt
    ) {}
}
