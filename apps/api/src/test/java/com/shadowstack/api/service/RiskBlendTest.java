package com.shadowstack.api.service;

import com.shadowstack.analysis.RiskPosterior;
import com.shadowstack.api.config.ShadowStackConfig;
import com.shadowstack.api.dto.PatchDetailResponse.RiskAssessment;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RiskBlendTest {

    private RefactorOrchestrationService service;

    @BeforeEach
    void setUp() {
        ShadowStackConfig config = new ShadowStackConfig(
                new ShadowStackConfig.RiskConfig(
                        0.3, 0.6, 0.85, 0.35, 0.25, 0.15, 0.85, 0.70, false, 0.2),
                new ShadowStackConfig.RetentionConfig(90, 365, 180),
                new ShadowStackConfig.SecurityProperties("x".repeat(32), 3600_000, "*", null),
                new ShadowStackConfig.PipelineConfig(4, 120, "java")
        );
        service = new RefactorOrchestrationService(config, null, null, null, null);
    }

    @Test
    void cleanVerifyWithStrongEvidenceCanLowerPrior() {
        RiskAssessment prior = new RiskAssessment(0.45, RiskAssessment.RiskTier.MEDIUM, List.of(), 0.9);
        RiskAssessment blended = service.blendRiskAfterVerify(
                prior, 0.0, List.of(), List.of(),
                RiskPosterior.VerifyOutcome.PASS, 6, 6, null, null, null);
        assertThat(blended.score()).isLessThan(0.45);
        assertThat(blended.evidenceStrength()).isGreaterThan(0.7);
        assertThat(blended.factors()).extracting(RiskAssessment.RiskFactor::name)
                .contains("rule_prior", "verify_pipeline", "residual_risk");
    }

    @Test
    void verifyRiskRaisesScoreAndTier() {
        RiskAssessment prior = new RiskAssessment(0.15, RiskAssessment.RiskTier.LOW, List.of(), 0.8);
        RiskAssessment blended = service.blendRiskAfterVerify(
                prior, 0.72, List.of(), List.of(),
                RiskPosterior.VerifyOutcome.PASS, 2, 6, null, null, null);
        assertThat(blended.score()).isGreaterThan(0.4);
        assertThat(blended.tier()).isIn(
                RiskAssessment.RiskTier.MEDIUM,
                RiskAssessment.RiskTier.HIGH,
                RiskAssessment.RiskTier.CRITICAL);
    }

    @Test
    void failedVerifyFloorsAtHighThreshold() {
        RiskAssessment prior = new RiskAssessment(0.15, RiskAssessment.RiskTier.LOW, List.of(), 0.5);
        RiskAssessment blended = service.blendRiskAfterVerify(prior, 0.1, List.of(), false);
        assertThat(blended.score()).isGreaterThanOrEqualTo(0.85);
        assertThat(blended.tier()).isIn(RiskAssessment.RiskTier.HIGH, RiskAssessment.RiskTier.CRITICAL);
    }

    @Test
    void warnUsesMildFloor() {
        RiskAssessment prior = new RiskAssessment(0.15, RiskAssessment.RiskTier.LOW, List.of(), 0.5);
        RiskAssessment blended = service.blendRiskAfterVerify(
                prior, 0.05, List.of(), List.of(),
                RiskPosterior.VerifyOutcome.WARN, 4, 6, null, null, null);
        assertThat(blended.score()).isGreaterThanOrEqualTo(0.35);
        assertThat(blended.score()).isLessThan(0.85);
    }
}
