package com.shadowstack.corpus;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Analytics DTO aggregating corpus-wide statistics about migration transformations.
 * Used to surface acceptance rates, risk profiles, confidence calibration, and failure patterns.
 */
public class CorpusAnalytics {

    @JsonProperty("acceptance_rate_by_rule")
    private Map<String, Double> acceptanceRateByRule;

    @JsonProperty("risk_distribution")
    private Map<String, Long> riskDistribution;

    @JsonProperty("avg_time_to_accept")
    private Duration avgTimeToAccept;

    @JsonProperty("confidence_calibration")
    private List<CalibrationPoint> confidenceCalibration;

    @JsonProperty("total_entries")
    private long totalEntries;

    @JsonProperty("accepted_count")
    private long acceptedCount;

    @JsonProperty("rejected_count")
    private long rejectedCount;

    @JsonProperty("top_failure_patterns")
    private List<FailurePattern> topFailurePatterns;

    public CorpusAnalytics() {
    }

    // --- Inner DTOs ---

    /**
     * Represents a single point on the confidence calibration curve.
     * Compares predicted confidence bucket against actual observed acceptance rate.
     */
    public static class CalibrationPoint {

        @JsonProperty("predicted_confidence")
        private double predictedConfidence;

        @JsonProperty("actual_acceptance_rate")
        private double actualAcceptanceRate;

        @JsonProperty("sample_count")
        private long sampleCount;

        public CalibrationPoint() {
        }

        public CalibrationPoint(double predictedConfidence, double actualAcceptanceRate, long sampleCount) {
            this.predictedConfidence = predictedConfidence;
            this.actualAcceptanceRate = actualAcceptanceRate;
            this.sampleCount = sampleCount;
        }

        public double getPredictedConfidence() {
            return predictedConfidence;
        }

        public void setPredictedConfidence(double predictedConfidence) {
            this.predictedConfidence = predictedConfidence;
        }

        public double getActualAcceptanceRate() {
            return actualAcceptanceRate;
        }

        public void setActualAcceptanceRate(double actualAcceptanceRate) {
            this.actualAcceptanceRate = actualAcceptanceRate;
        }

        public long getSampleCount() {
            return sampleCount;
        }

        public void setSampleCount(long sampleCount) {
            this.sampleCount = sampleCount;
        }
    }

    /**
     * Represents a recurring failure pattern — a rejection reason and its frequency.
     */
    public static class FailurePattern {

        @JsonProperty("reason")
        private String reason;

        @JsonProperty("occurrences")
        private long occurrences;

        public FailurePattern() {
        }

        public FailurePattern(String reason, long occurrences) {
            this.reason = reason;
            this.occurrences = occurrences;
        }

        public String getReason() {
            return reason;
        }

        public void setReason(String reason) {
            this.reason = reason;
        }

        public long getOccurrences() {
            return occurrences;
        }

        public void setOccurrences(long occurrences) {
            this.occurrences = occurrences;
        }
    }

    // --- Getters and Setters ---

    public Map<String, Double> getAcceptanceRateByRule() {
        return acceptanceRateByRule;
    }

    public void setAcceptanceRateByRule(Map<String, Double> acceptanceRateByRule) {
        this.acceptanceRateByRule = acceptanceRateByRule;
    }

    public Map<String, Long> getRiskDistribution() {
        return riskDistribution;
    }

    public void setRiskDistribution(Map<String, Long> riskDistribution) {
        this.riskDistribution = riskDistribution;
    }

    public Duration getAvgTimeToAccept() {
        return avgTimeToAccept;
    }

    public void setAvgTimeToAccept(Duration avgTimeToAccept) {
        this.avgTimeToAccept = avgTimeToAccept;
    }

    public List<CalibrationPoint> getConfidenceCalibration() {
        return confidenceCalibration;
    }

    public void setConfidenceCalibration(List<CalibrationPoint> confidenceCalibration) {
        this.confidenceCalibration = confidenceCalibration;
    }

    public long getTotalEntries() {
        return totalEntries;
    }

    public void setTotalEntries(long totalEntries) {
        this.totalEntries = totalEntries;
    }

    public long getAcceptedCount() {
        return acceptedCount;
    }

    public void setAcceptedCount(long acceptedCount) {
        this.acceptedCount = acceptedCount;
    }

    public long getRejectedCount() {
        return rejectedCount;
    }

    public void setRejectedCount(long rejectedCount) {
        this.rejectedCount = rejectedCount;
    }

    public List<FailurePattern> getTopFailurePatterns() {
        return topFailurePatterns;
    }

    public void setTopFailurePatterns(List<FailurePattern> topFailurePatterns) {
        this.topFailurePatterns = topFailurePatterns;
    }
}
