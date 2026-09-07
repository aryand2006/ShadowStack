package com.shadowstack.api.service;

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
                new ShadowStackConfig.RiskConfig(0.3, 0.6, 0.85, false, 0.2),
                new ShadowStackConfig.RetentionConfig(90, 365, 180),
                new ShadowStackConfig.SecurityProperties("x".repeat(32), 3600_000, "*", null),
                new ShadowStackConfig.PipelineConfig(4, 120, "java")
        );
        // Only blendRiskAfterVerify is exercised; collaborators unused.
        service = new RefactorOrchestrationService(config, null, null, null);
    }

    @Test
    void cleanVerifyKeepsRulePriorWhenHigher() {
        RiskAssessment prior = new RiskAssessment(0.45, RiskAssessment.RiskTier.MEDIUM, List.of(), 0.9);
        RiskAssessment blended = service.blendRiskAfterVerify(prior, 0.0, List.of(), true);
        assertThat(blended.score()).isEqualTo(0.45);
        assertThat(blended.tier()).isEqualTo(RiskAssessment.RiskTier.MEDIUM);
        assertThat(blended.factors()).extracting(RiskAssessment.RiskFactor::name)
                .contains("rule_prior", "verify_pipeline");
    }

    @Test
    void verifyRiskRaisesScoreAndTier() {
        RiskAssessment prior = new RiskAssessment(0.15, RiskAssessment.RiskTier.LOW, List.of(), 0.8);
        RiskAssessment blended = service.blendRiskAfterVerify(prior, 0.72, List.of(), true);
        assertThat(blended.score()).isEqualTo(0.72);
        assertThat(blended.tier()).isEqualTo(RiskAssessment.RiskTier.HIGH);
    }

    @Test
    void failedVerifyFloorsAtHighThreshold() {
        RiskAssessment prior = new RiskAssessment(0.15, RiskAssessment.RiskTier.LOW, List.of(), 0.5);
        RiskAssessment blended = service.blendRiskAfterVerify(prior, 0.1, List.of(), false);
        assertThat(blended.score()).isGreaterThanOrEqualTo(0.85);
        assertThat(blended.tier()).isIn(RiskAssessment.RiskTier.HIGH, RiskAssessment.RiskTier.CRITICAL);
    }
}
