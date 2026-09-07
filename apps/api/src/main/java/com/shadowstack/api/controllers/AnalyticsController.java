package com.shadowstack.api.controllers;

import com.shadowstack.api.dto.AnalyticsDashboardResponse;
import com.shadowstack.api.dto.AnalyticsDashboardResponse.*;
import com.shadowstack.api.service.AnalyticsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller for analytics endpoints.
 * Aggregates live patch/project store data (demo in-memory or JPA).
 */
@RestController
@RequestMapping("/api/v1/analytics")
@Tag(name = "Analytics", description = "Migration corpus analytics and pipeline metrics")
public class AnalyticsController {

    private final AnalyticsService analyticsService;

    public AnalyticsController(AnalyticsService analyticsService) {
        this.analyticsService = analyticsService;
    }

    @GetMapping("/corpus")
    @PreAuthorize("hasAnyRole('ADMIN', 'ANALYST', 'REVIEWER')")
    @Operation(summary = "Migration corpus analytics", description = "Statistics on patches/patterns by language and category")
    public ResponseEntity<CorpusMetrics> getCorpusAnalytics() {
        return ResponseEntity.ok(analyticsService.corpusMetrics());
    }

    @GetMapping("/acceptance-rates")
    @PreAuthorize("hasAnyRole('ADMIN', 'ANALYST', 'REVIEWER')")
    @Operation(summary = "Per-rule acceptance rates", description = "Acceptance rates broken down by rule and category")
    public ResponseEntity<AcceptanceMetrics> getAcceptanceRates() {
        return ResponseEntity.ok(analyticsService.acceptanceMetrics());
    }

    @GetMapping("/risk-distribution")
    @PreAuthorize("hasAnyRole('ADMIN', 'ANALYST', 'REVIEWER')")
    @Operation(summary = "Risk tier distribution", description = "Distribution of patches across risk tiers")
    public ResponseEntity<RiskDistribution> getRiskDistribution() {
        return ResponseEntity.ok(analyticsService.riskDistribution());
    }

    @GetMapping("/confidence-calibration")
    @PreAuthorize("hasAnyRole('ADMIN', 'ANALYST', 'REVIEWER')")
    @Operation(
            summary = "Confidence calibration",
            description = "Predicted vs actual success rates to assess model calibration"
    )
    public ResponseEntity<ConfidenceCalibration> getConfidenceCalibration() {
        return ResponseEntity.ok(analyticsService.confidenceCalibration());
    }

    @GetMapping("/dashboard")
    @PreAuthorize("hasAnyRole('ADMIN', 'ANALYST', 'REVIEWER')")
    @Operation(summary = "Aggregated dashboard", description = "Full dashboard data combining all analytics")
    public ResponseEntity<AnalyticsDashboardResponse> getDashboard() {
        return ResponseEntity.ok(analyticsService.dashboard());
    }
}
