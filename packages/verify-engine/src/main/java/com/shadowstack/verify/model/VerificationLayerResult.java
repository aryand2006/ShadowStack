package com.shadowstack.verify.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * Result from a single verification layer execution.
 *
 * <p>Each verification layer produces a result containing its verdict, a risk contribution
 * score, diagnostic details, and timing information. Results are aggregated by the
 * {@link com.shadowstack.verify.VerificationPipeline} to determine the overall verdict.</p>
 */
public final class VerificationLayerResult {

    private final String layerId;
    private final Verdict verdict;
    private final double riskContribution;
    private final String summary;
    private final Map<String, Object> details;
    private final List<String> diagnostics;
    private final Instant executedAt;
    private final Duration executionTime;

    @JsonCreator
    public VerificationLayerResult(
            @JsonProperty("layerId") String layerId,
            @JsonProperty("verdict") Verdict verdict,
            @JsonProperty("riskContribution") double riskContribution,
            @JsonProperty("summary") String summary,
            @JsonProperty("details") Map<String, Object> details,
            @JsonProperty("diagnostics") List<String> diagnostics,
            @JsonProperty("executedAt") Instant executedAt,
            @JsonProperty("executionTime") Duration executionTime) {
        this.layerId = Objects.requireNonNull(layerId);
        this.verdict = Objects.requireNonNull(verdict);
        this.riskContribution = Math.max(0.0, Math.min(1.0, riskContribution));
        this.summary = Objects.requireNonNull(summary);
        this.details = details != null ? Map.copyOf(details) : Map.of();
        this.diagnostics = diagnostics != null ? List.copyOf(diagnostics) : List.of();
        this.executedAt = executedAt != null ? executedAt : Instant.now();
        this.executionTime = executionTime;
    }

    public String getLayerId() { return layerId; }
    public Verdict getVerdict() { return verdict; }
    public double getRiskContribution() { return riskContribution; }
    public String getSummary() { return summary; }
    public Map<String, Object> getDetails() { return details; }
    public List<String> getDiagnostics() { return diagnostics; }
    public Instant getExecutedAt() { return executedAt; }
    public Duration getExecutionTime() { return executionTime; }

    public boolean passed() { return verdict == Verdict.PASS; }
    public boolean failed() { return verdict == Verdict.FAIL; }

    @Override
    public String toString() {
        return "VerificationLayerResult{layer='%s', verdict=%s, risk=%.3f, summary='%s'}"
                .formatted(layerId, verdict, riskContribution, summary);
    }

    // --- Builder ---

    public static Builder builder(String layerId) {
        return new Builder(layerId);
    }

    public static final class Builder {
        private final String layerId;
        private Verdict verdict = Verdict.PASS;
        private double riskContribution = 0.0;
        private String summary = "";
        private Map<String, Object> details = new LinkedHashMap<>();
        private List<String> diagnostics = new ArrayList<>();
        private Instant executedAt;
        private Duration executionTime;

        private Builder(String layerId) {
            this.layerId = layerId;
        }

        public Builder verdict(Verdict v) { this.verdict = v; return this; }
        public Builder riskContribution(double r) { this.riskContribution = r; return this; }
        public Builder summary(String s) { this.summary = s; return this; }
        public Builder details(Map<String, Object> d) { this.details = new LinkedHashMap<>(d); return this; }
        public Builder addDetail(String key, Object value) { this.details.put(key, value); return this; }
        public Builder diagnostics(List<String> d) { this.diagnostics = new ArrayList<>(d); return this; }
        public Builder addDiagnostic(String d) { this.diagnostics.add(d); return this; }
        public Builder executedAt(Instant t) { this.executedAt = t; return this; }
        public Builder executionTime(Duration d) { this.executionTime = d; return this; }

        public VerificationLayerResult build() {
            return new VerificationLayerResult(layerId, verdict, riskContribution, summary,
                    details, diagnostics, executedAt, executionTime);
        }
    }
}
