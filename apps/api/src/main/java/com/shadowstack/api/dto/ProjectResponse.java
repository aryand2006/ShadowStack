package com.shadowstack.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Response payload representing a ShadowStack project.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ProjectResponse(
        UUID id,
        String name,
        String description,
        String repositoryUrl,
        String branch,
        String sourceLanguage,
        String targetLanguageVersion,
        ProjectStatus status,
        Instant createdAt,
        Instant updatedAt,
        BaselineSummary baseline,
        AnalysisSummary analysisSummary
) {

    public enum ProjectStatus {
        CREATED,
        INGESTING,
        BASELINE_CAPTURED,
        ANALYZING,
        READY,
        ERROR
    }

    public record BaselineSummary(
            int totalFiles,
            int totalMethods,
            int totalTestCases,
            int testsPassing,
            Instant capturedAt
    ) {}

    public record AnalysisSummary(
            int totalCandidates,
            int patchesGenerated,
            int patchesVerified,
            int patchesAccepted,
            Map<String, Integer> riskDistribution
    ) {}
}
