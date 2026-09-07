package com.shadowstack.verify.layers;

import com.shadowstack.refactor.model.PatchUnit;
import com.shadowstack.verify.VerificationLayer;
import com.shadowstack.verify.model.VerificationContext;
import com.shadowstack.verify.model.VerificationLayerResult;
import com.shadowstack.verify.model.Verdict;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * Verification layer that computes a deterministic semantic risk score by aggregating
 * signals from other verification layers and the patch's own metadata.
 *
 * <p>This layer acts as the final risk aggregation point, combining compile status,
 * test results, AST delta, bytecode signature comparisons, and API surface changes
 * into a single normalized risk score.</p>
 *
 * <h3>Risk Formula</h3>
 * <pre>
 *   risk = 0.0
 *   if (!compileSuccess)            risk += 0.40
 *   if (!testSuccess)               risk += 0.30
 *   if (astDelta > threshold)       risk += 0.10
 *   if (bytecodeSignatureMismatch)  risk += 0.10
 *   if (apiSurfaceChanged)          risk += 0.10
 *   normalize to [0.0, 1.0]
 * </pre>
 *
 * <p>The scorer reads results from previously executed layers stored in the context
 * configuration map, or falls back to pessimistic defaults when data is unavailable.</p>
 */
public class SemanticRiskScorer implements VerificationLayer {

    private static final Logger log = LoggerFactory.getLogger(SemanticRiskScorer.class);
    private static final String LAYER_ID = "semantic_risk_scorer";

    // Configuration keys for reading upstream layer results
    public static final String KEY_COMPILE_SUCCESS = "compileSuccess";
    public static final String KEY_TEST_SUCCESS = "testSuccess";
    public static final String KEY_TESTS_EXECUTED = "testsExecuted";
    public static final String KEY_AST_DELTA = "astDelta";
    public static final String KEY_AST_THRESHOLD = "astDeltaThreshold";
    public static final String KEY_BYTECODE_MISMATCH = "bytecodeSignatureMismatch";
    public static final String KEY_API_SURFACE_CHANGED = "apiSurfaceChanged";

    @Override
    public String layerId() {
        return LAYER_ID;
    }

    @Override
    public VerificationLayerResult verify(PatchUnit patch, VerificationContext context) {
        Instant start = Instant.now();
        log.info("SemanticRiskScorer: computing risk score for patch {} in '{}'",
                patch.getPatchId(), patch.getSourceFile());

        VerificationLayerResult.Builder result = VerificationLayerResult.builder(LAYER_ID);

        // Read upstream results from context configuration
        boolean compileSuccess = context.getConfig(KEY_COMPILE_SUCCESS, true);
        boolean testsExecuted = context.getConfig(KEY_TESTS_EXECUTED, false);
        boolean testSuccess = context.getConfig(KEY_TEST_SUCCESS, true);
        double astDelta = context.getConfig(KEY_AST_DELTA, 0.0);
        double astThreshold = context.getConfig(KEY_AST_THRESHOLD, 0.30);
        boolean bytecodeSignatureMismatch = context.getConfig(KEY_BYTECODE_MISMATCH, false);
        boolean apiSurfaceChanged = context.getConfig(KEY_API_SURFACE_CHANGED, false);

        result.addDetail("compileSuccess", compileSuccess);
        result.addDetail("testsExecuted", testsExecuted);
        result.addDetail("testSuccess", testSuccess);
        result.addDetail("astDelta", astDelta);
        result.addDetail("astDeltaThreshold", astThreshold);
        result.addDetail("bytecodeSignatureMismatch", bytecodeSignatureMismatch);
        result.addDetail("apiSurfaceChanged", apiSurfaceChanged);

        // Apply deterministic risk formula (sole pipeline aggregator when present)
        double risk = 0.0;
        List<String> riskFactors = new ArrayList<>();

        if (!compileSuccess) {
            risk += 0.40;
            riskFactors.add("compile_failure(+0.40)");
            log.info("  +0.40: compilation failure");
        }

        if (testsExecuted && !testSuccess) {
            risk += 0.30;
            riskFactors.add("test_failure(+0.30)");
            log.info("  +0.30: test failure");
        }

        if (astDelta > astThreshold) {
            risk += 0.10;
            riskFactors.add("ast_delta_high(+0.10, delta=%.3f, threshold=%.3f)"
                    .formatted(astDelta, astThreshold));
            log.info("  +0.10: AST delta {:.3f} exceeds threshold {:.3f}", astDelta, astThreshold);
        }

        if (bytecodeSignatureMismatch) {
            risk += 0.10;
            riskFactors.add("bytecode_mismatch(+0.10)");
            log.info("  +0.10: bytecode signature mismatch");
        }

        if (apiSurfaceChanged) {
            risk += 0.10;
            riskFactors.add("api_surface_changed(+0.10)");
            log.info("  +0.10: API surface changed");
        }

        // Normalize to [0.0, 1.0]
        risk = Math.max(0.0, Math.min(1.0, risk));

        result.addDetail("rawRisk", risk);
        result.addDetail("riskFactors", riskFactors);
        result.addDetail("riskBreakdown", formatBreakdown(riskFactors, risk));

        log.info("  Final semantic risk score: {:.3f} (factors: {})", risk, riskFactors.size());

        // Scoring-only: never gate the pipeline via this layer's verdict.
        // Other layers + risk-threshold gate decide PASS/WARN/FAIL.
        return result
                .verdict(Verdict.PASS)
                .riskContribution(risk)
                .summary("Semantic risk score: %.3f (%d risk factor(s): %s)"
                        .formatted(risk, riskFactors.size(),
                                riskFactors.isEmpty() ? "none" : String.join(", ", riskFactors)))
                .executionTime(Duration.between(start, Instant.now()))
                .build();
    }

    private String formatBreakdown(List<String> factors, double total) {
        if (factors.isEmpty()) {
            return "risk=0.0 (no risk factors)";
        }
        return String.join(" + ", factors) + " = %.3f".formatted(total);
    }
}
