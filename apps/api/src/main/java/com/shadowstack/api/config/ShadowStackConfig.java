package com.shadowstack.api.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Type-safe configuration properties for ShadowStack.
 * Bound to the {@code shadowstack.*} namespace in application.yml.
 */
@ConfigurationProperties(prefix = "shadowstack")
public record ShadowStackConfig(
        RiskConfig risk,
        RetentionConfig retention,
        SecurityProperties security,
        PipelineConfig pipeline
) {

    /**
     * Risk scoring thresholds and auto-apply policies.
     */
    public record RiskConfig(
            double lowThreshold,
            double mediumThreshold,
            double highThreshold,
            boolean autoApplyEnabled,
            double maxAutoApplyRisk
    ) {
        /**
         * Determine the risk tier for a given score.
         */
        public String tierFor(double score) {
            if (score <= lowThreshold) return "LOW";
            if (score <= mediumThreshold) return "MEDIUM";
            if (score <= highThreshold) return "HIGH";
            return "CRITICAL";
        }

        /**
         * Whether the given risk score qualifies for auto-apply.
         */
        public boolean canAutoApply(double score) {
            return autoApplyEnabled && score <= maxAutoApplyRisk;
        }
    }

    /**
     * Data retention policies.
     */
    public record RetentionConfig(
            int auditLogDays,
            int patchHistoryDays,
            int verificationEvidenceDays
    ) {}

    /**
     * Security-related properties (JWT, CORS).
     */
    public record SecurityProperties(
            String jwtSecret,
            long jwtExpirationMs,
            String corsAllowedOrigins
    ) {}

    /**
     * Pipeline execution limits.
     */
    public record PipelineConfig(
            int maxConcurrentAnalyses,
            int verificationTimeoutSeconds,
            String defaultLanguage
    ) {}
}
