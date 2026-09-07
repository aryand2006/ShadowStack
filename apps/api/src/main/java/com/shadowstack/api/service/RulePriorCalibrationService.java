package com.shadowstack.api.service;

import com.shadowstack.analysis.RulePriorCalibrator;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Persists accept/reject outcomes and exposes calibrated rule priors.
 */
@Service
public class RulePriorCalibrationService {

    private static final Logger log = LoggerFactory.getLogger(RulePriorCalibrationService.class);

    private final JdbcTemplate jdbc;
    private final RulePriorCalibrator calibrator = new RulePriorCalibrator();

    public RulePriorCalibrationService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @PostConstruct
    void load() {
        try {
            jdbc.query(
                    "SELECT rule_name, accepts, rejects FROM ss_rule_prior_calibration",
                    rs -> {
                        calibrator.seed(
                                rs.getString("rule_name"),
                                rs.getLong("accepts"),
                                rs.getLong("rejects"));
                    });
            log.info("Loaded rule prior calibration rows={}", calibrator.snapshot().size());
        } catch (Exception e) {
            log.warn("Rule prior calibration table not ready yet: {}", e.getMessage());
        }
    }

    public double calibratedPrior(String ruleName, double basePrior) {
        return calibrator.calibratedPrior(ruleName, basePrior);
    }

    public void recordDecision(String ruleName, boolean accepted) {
        if (accepted) {
            calibrator.recordAccept(ruleName);
        } else {
            calibrator.recordReject(ruleName);
        }
        persist(ruleName);
    }

    private void persist(String ruleName) {
        try {
            long[] counts = calibrator.snapshot().getOrDefault(
                    ruleName == null ? "unknown" : ruleName.trim().toLowerCase(),
                    new long[]{0, 0});
            // snapshot keys are normalized lowercase
            String key = ruleName == null ? "unknown" : ruleName.trim().toLowerCase();
            long[] live = calibrator.snapshot().getOrDefault(key, counts);
            jdbc.update("""
                    INSERT INTO ss_rule_prior_calibration(rule_name, accepts, rejects, updated_at)
                    VALUES (?, ?, ?, NOW())
                    ON CONFLICT (rule_name) DO UPDATE
                      SET accepts = EXCLUDED.accepts,
                          rejects = EXCLUDED.rejects,
                          updated_at = NOW()
                    """, key, live[0], live[1]);
        } catch (Exception e) {
            log.warn("Could not persist rule prior calibration for {}: {}", ruleName, e.getMessage());
        }
    }

    RulePriorCalibrator calibrator() {
        return calibrator;
    }
}
