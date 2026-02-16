package com.shadowstack.api.controllers;

import com.shadowstack.api.dto.AnalyticsDashboardResponse;
import com.shadowstack.api.dto.AnalyticsDashboardResponse.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * REST controller for analytics endpoints.
 * Provides migration corpus analytics, acceptance rates,
 * risk distribution, confidence calibration, and dashboard data.
 * <p>
 * In production, these endpoints aggregate data from the migration-corpus
 * module and the review/verification history stored in the database.
 */
@RestController
@RequestMapping("/api/v1/analytics")
@Tag(name = "Analytics", description = "Migration corpus analytics and pipeline metrics")
public class AnalyticsController {

    @GetMapping("/corpus")
    @PreAuthorize("hasAnyRole('ADMIN', 'ANALYST', 'REVIEWER')")
    @Operation(summary = "Migration corpus analytics", description = "Statistics on the migration pattern corpus")
    public ResponseEntity<CorpusMetrics> getCorpusAnalytics() {
        // In production: query migration-corpus module
        CorpusMetrics metrics = new CorpusMetrics(
                0L, 0L, 0L, 0.0,
                Map.of(), Map.of()
        );
        return ResponseEntity.ok(metrics);
    }

    @GetMapping("/acceptance-rates")
    @PreAuthorize("hasAnyRole('ADMIN', 'ANALYST', 'REVIEWER')")
    @Operation(summary = "Per-rule acceptance rates", description = "Acceptance rates broken down by rule and category")
    public ResponseEntity<AcceptanceMetrics> getAcceptanceRates() {
        // In production: aggregate from review history
        AcceptanceMetrics metrics = new AcceptanceMetrics(
                0.0, List.of(), List.of()
        );
        return ResponseEntity.ok(metrics);
    }

    @GetMapping("/risk-distribution")
    @PreAuthorize("hasAnyRole('ADMIN', 'ANALYST', 'REVIEWER')")
    @Operation(summary = "Risk tier distribution", description = "Distribution of patches across risk tiers")
    public ResponseEntity<RiskDistribution> getRiskDistribution() {
        // In production: aggregate from patch store
        RiskDistribution distribution = new RiskDistribution(
                0L, 0L, 0L, 0L, 0.0, 0.0
        );
        return ResponseEntity.ok(distribution);
    }

    @GetMapping("/confidence-calibration")
    @PreAuthorize("hasAnyRole('ADMIN', 'ANALYST', 'REVIEWER')")
    @Operation(
            summary = "Confidence calibration",
            description = "Predicted vs actual success rates to assess model calibration"
    )
    public ResponseEntity<ConfidenceCalibration> getConfidenceCalibration() {
        // In production: compute from historical predictions vs outcomes
        ConfidenceCalibration calibration = new ConfidenceCalibration(
                List.of(), 0.0, 0.0
        );
        return ResponseEntity.ok(calibration);
    }

    @GetMapping("/dashboard")
    @PreAuthorize("hasAnyRole('ADMIN', 'ANALYST', 'REVIEWER')")
    @Operation(summary = "Aggregated dashboard", description = "Full dashboard data combining all analytics")
    public ResponseEntity<AnalyticsDashboardResponse> getDashboard() {
        // In production: aggregate all metrics into a single response
        AnalyticsDashboardResponse dashboard = new AnalyticsDashboardResponse(
                new CorpusMetrics(0L, 0L, 0L, 0.0, Map.of(), Map.of()),
                new AcceptanceMetrics(0.0, List.of(), List.of()),
                new RiskDistribution(0L, 0L, 0L, 0L, 0.0, 0.0),
                new ConfidenceCalibration(List.of(), 0.0, 0.0),
                new PipelineHealth(0L, 0L, 0L, 0L, 0.0, 0.0),
                Instant.now()
        );
        return ResponseEntity.ok(dashboard);
    }
}
