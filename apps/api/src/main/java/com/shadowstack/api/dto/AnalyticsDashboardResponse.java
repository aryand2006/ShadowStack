package com.shadowstack.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Aggregated analytics dashboard data for the ShadowStack platform.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AnalyticsDashboardResponse(
        CorpusMetrics corpus,
        AcceptanceMetrics acceptance,
        RiskDistribution riskDistribution,
        ConfidenceCalibration confidenceCalibration,
        PipelineHealth pipelineHealth,
        Instant generatedAt
) {

    public record CorpusMetrics(
            long totalPatterns,
            long totalTransformations,
            long successfulTransformations,
            double overallSuccessRate,
            Map<String, Long> patternsByLanguage,
            Map<String, Long> patternsByCategory
    ) {}

    public record AcceptanceMetrics(
            double overallAcceptanceRate,
            List<RuleAcceptanceRate> byRule,
            List<CategoryAcceptanceRate> byCategory
    ) {
        public record RuleAcceptanceRate(
                String ruleName,
                long totalReviewed,
                long accepted,
                double acceptanceRate
        ) {}

        public record CategoryAcceptanceRate(
                String category,
                long totalReviewed,
                long accepted,
                double acceptanceRate
        ) {}
    }

    public record RiskDistribution(
            long low,
            long medium,
            long high,
            long critical,
            double meanRiskScore,
            double medianRiskScore
    ) {}

    public record ConfidenceCalibration(
            List<CalibrationBucket> buckets,
            double brierScore,
            double expectedCalibrationError
    ) {
        public record CalibrationBucket(
                double predictedMin,
                double predictedMax,
                double actualRate,
                long count
        ) {}
    }

    public record PipelineHealth(
            long totalPatchesGenerated,
            long totalPatchesVerified,
            long totalPatchesAccepted,
            long totalPatchesRejected,
            double meanVerificationTimeSeconds,
            double meanReviewTimeSeconds
    ) {}
}
