package com.shadowstack.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.UUID;

/**
 * Summary item for the review queue — a patch awaiting human review.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ReviewQueueItem(
        UUID patchId,
        UUID projectId,
        String projectName,
        String ruleName,
        String ruleCategory,
        String filePath,
        int startLine,
        int endLine,
        double riskScore,
        PatchDetailResponse.RiskAssessment.RiskTier riskTier,
        double evidenceStrength,
        String shortDescription,
        boolean verificationPassed,
        int invariantsPreserved,
        int invariantsTotal,
        Instant generatedAt,
        Instant verifiedAt
) {
}
