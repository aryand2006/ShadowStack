package com.shadowstack.analysis;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RiskPosteriorTest {

    @Test
    void cleanStrongEvidenceLowersMediumPrior() {
        RiskPosterior.Result result = RiskPosterior.blend(
                0.45, 0.20, 0.0, 0.0, 0.90,
                RiskPosterior.VerifyOutcome.PASS,
                RiskPosterior.BlendConfig.DEFAULTS,
                List.of());
        assertTrue(result.residualRisk() < 0.45);
        assertTrue(result.tier().equals("LOW") || result.tier().equals("MEDIUM"));
        assertEquals(0.90, result.evidenceStrength(), 1e-9);
    }

    @Test
    void failFloorsAtFailFloor() {
        RiskPosterior.Result result = RiskPosterior.blend(
                0.15, 0.15, 0.05, 0.0, 0.2,
                RiskPosterior.VerifyOutcome.FAIL,
                RiskPosterior.BlendConfig.DEFAULTS,
                List.of());
        assertTrue(result.residualRisk() >= 0.85);
    }

    @Test
    void warnUsesMildFloorNotFailFloor() {
        RiskPosterior.Result result = RiskPosterior.blend(
                0.15, 0.15, 0.05, 0.0, 0.4,
                RiskPosterior.VerifyOutcome.WARN,
                RiskPosterior.BlendConfig.DEFAULTS,
                List.of());
        assertTrue(result.residualRisk() >= 0.35);
        assertTrue(result.residualRisk() < 0.85);
    }

    @Test
    void blastRaisesWhenEvidenceWeak() {
        RiskPosterior.Result lowEvidence = RiskPosterior.blend(
                0.30, 0.30, 0.10, 0.80, 0.20,
                RiskPosterior.VerifyOutcome.PASS,
                RiskPosterior.BlendConfig.DEFAULTS,
                List.of());
        RiskPosterior.Result highEvidence = RiskPosterior.blend(
                0.30, 0.30, 0.10, 0.80, 0.95,
                RiskPosterior.VerifyOutcome.PASS,
                RiskPosterior.BlendConfig.DEFAULTS,
                List.of());
        assertTrue(lowEvidence.residualRisk() > highEvidence.residualRisk());
    }

    @Test
    void calibratorShrinksTowardRejects() {
        RulePriorCalibrator calibrator = new RulePriorCalibrator();
        for (int i = 0; i < 20; i++) {
            calibrator.recordReject("FooRule");
        }
        double calibrated = calibrator.calibratedPrior("FooRule", 0.15);
        assertTrue(calibrated > 0.15);
    }
}
