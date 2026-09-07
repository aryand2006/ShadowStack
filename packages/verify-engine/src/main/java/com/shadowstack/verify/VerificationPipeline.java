package com.shadowstack.verify;

import com.shadowstack.refactor.model.PatchUnit;
import com.shadowstack.verify.layers.SemanticRiskScorer;
import com.shadowstack.verify.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Orchestrates the multi-layered verification pipeline for patch validation.
 *
 * <p>The VerificationPipeline executes all registered {@link VerificationLayer} implementations
 * in sequence against a given {@link PatchUnit}, aggregates their results, computes an overall
 * risk score, and determines a final PASS/FAIL/WARN verdict.</p>
 *
 * <h3>Pipeline Flow</h3>
 * <pre>
 *   PatchUnit → [CompileVerifier] → [TestExecutionVerifier] → [ASTStructuralComparator]
 *            → [BytecodeDescriptorComparator] → [APISignatureDiffVerifier]
 *            → [SemanticRiskScorer] → [GoldenMasterVerifier]
 *            → Aggregate Results → Compute Risk → Determine Verdict
 *            → BehavioralEquivalenceCertificate
 * </pre>
 *
 * <h3>Risk Threshold</h3>
 * <p>The pipeline is configured with a maximum acceptable risk score. If the aggregated
 * risk exceeds this threshold, the pipeline refuses to pass the change regardless of
 * individual layer verdicts.</p>
 */
public final class VerificationPipeline {

    private static final Logger log = LoggerFactory.getLogger(VerificationPipeline.class);

    private final CopyOnWriteArrayList<VerificationLayer> layers = new CopyOnWriteArrayList<>();
    private final double riskThreshold;
    private final boolean failFast;

    /**
     * Creates a pipeline with default settings (risk threshold 0.7, no fail-fast).
     */
    public VerificationPipeline() {
        this(0.7, false);
    }

    /**
     * Creates a pipeline with configurable risk threshold and fail-fast behavior.
     *
     * @param riskThreshold maximum acceptable risk score (0.0 to 1.0); above this triggers FAIL
     * @param failFast      if true, stop executing layers after the first FAIL verdict
     */
    public VerificationPipeline(double riskThreshold, boolean failFast) {
        this.riskThreshold = Math.max(0.0, Math.min(1.0, riskThreshold));
        this.failFast = failFast;
        log.info("VerificationPipeline initialized: riskThreshold={}, failFast={}", riskThreshold, failFast);
    }

    /**
     * Registers a verification layer with the pipeline.
     * Layers are executed in registration order.
     */
    public void addLayer(VerificationLayer layer) {
        Objects.requireNonNull(layer, "layer must not be null");
        layers.add(layer);
        log.info("Registered verification layer: '{}'", layer.layerId());
    }

    /**
     * Returns an unmodifiable view of registered layers.
     */
    public List<VerificationLayer> getLayers() {
        return Collections.unmodifiableList(layers);
    }

    /**
     * Executes the full verification pipeline against a patch unit.
     *
     * <p>Runs all registered layers in sequence, aggregates results, computes
     * the overall risk score, and determines the final verdict.</p>
     *
     * @param patch   the patch unit to verify
     * @param context the verification context
     * @return the complete pipeline result
     */
    public PipelineResult execute(PatchUnit patch, VerificationContext context) {
        Objects.requireNonNull(patch, "patch must not be null");
        Objects.requireNonNull(context, "context must not be null");

        Instant pipelineStart = Instant.now();
        log.info("Starting verification pipeline for patch {} (rule='{}', file='{}', lines={}-{})",
                patch.getPatchId(), patch.getRuleId(), patch.getSourceFile(),
                patch.getStartLine(), patch.getEndLine());

        List<VerificationLayerResult> results = new ArrayList<>();
        boolean earlyTermination = false;

        // Execute each layer sequentially
        for (VerificationLayer layer : layers) {
            log.info("Executing verification layer: '{}'", layer.layerId());
            Instant layerStart = Instant.now();

            VerificationLayerResult result;
            try {
                result = layer.verify(patch, context);
            } catch (Exception e) {
                log.error("Verification layer '{}' threw exception: {}", layer.layerId(), e.getMessage(), e);
                result = VerificationLayerResult.builder(layer.layerId())
                        .verdict(Verdict.FAIL)
                        .riskContribution(0.40)
                        .summary("Layer threw exception: " + e.getMessage())
                        .addDiagnostic("Exception: " + e.getClass().getName() + ": " + e.getMessage())
                        .executionTime(Duration.between(layerStart, Instant.now()))
                        .build();
            }

            Duration layerDuration = Duration.between(layerStart, Instant.now());
            log.info("Layer '{}' completed in {}ms: verdict={}, riskContribution={}",
                    layer.layerId(), layerDuration.toMillis(), result.getVerdict(),
                    result.getRiskContribution());

            for (String diagnostic : result.getDiagnostics()) {
                log.debug("  [{}] {}", layer.layerId(), diagnostic);
            }

            results.add(result);
            publishLayerSignals(layer.layerId(), result, context);

            // Fail-fast: stop after first FAIL
            if (failFast && result.failed()) {
                log.warn("Fail-fast triggered by layer '{}' — aborting remaining layers", layer.layerId());
                earlyTermination = true;
                break;
            }
        }

        // Aggregate results
        Verdict overallVerdict = computeOverallVerdict(results);
        double overallRisk = computeOverallRisk(results);

        // Risk threshold gate: refuse to pass if risk is too high
        if (overallRisk > riskThreshold && overallVerdict != Verdict.FAIL) {
            log.warn("Risk score {:.3f} exceeds threshold {:.3f} — overriding verdict to FAIL",
                    overallRisk, riskThreshold);
            overallVerdict = Verdict.FAIL;
        }

        Duration totalDuration = Duration.between(pipelineStart, Instant.now());
        log.info("Pipeline complete for patch {}: verdict={}, risk={:.3f}, layers={}/{}, time={}ms",
                patch.getPatchId(), overallVerdict, overallRisk,
                results.size(), layers.size(), totalDuration.toMillis());

        return new PipelineResult(
                patch.getPatchId(),
                overallVerdict,
                overallRisk,
                riskThreshold,
                List.copyOf(results),
                earlyTermination,
                Instant.now(),
                totalDuration
        );
    }

    /**
     * Computes the overall verdict from all layer results.
     *
     * <p>Rules:</p>
     * <ul>
     *   <li>Any FAIL → overall FAIL</li>
     *   <li>Any WARN (no FAIL) → overall WARN</li>
     *   <li>All PASS → overall PASS</li>
     * </ul>
     */
    private Verdict computeOverallVerdict(List<VerificationLayerResult> results) {
        Verdict worst = Verdict.PASS;
        for (VerificationLayerResult result : results) {
            worst = Verdict.worst(worst, result.getVerdict());
        }
        return worst;
    }

    /**
     * Computes overall risk. When {@code semantic_risk_scorer} ran, it is the
     * sole aggregator (avoids double-counting per-layer contributions).
     * Otherwise falls back to additive clamped sum of non-aggregator layers.
     */
    private double computeOverallRisk(List<VerificationLayerResult> results) {
        for (VerificationLayerResult result : results) {
            if ("semantic_risk_scorer".equals(result.getLayerId())) {
                return Math.max(0.0, Math.min(1.0, result.getRiskContribution()));
            }
        }
        double totalRisk = 0.0;
        for (VerificationLayerResult result : results) {
            totalRisk += result.getRiskContribution();
        }
        return Math.max(0.0, Math.min(1.0, totalRisk));
    }

    /**
     * Write upstream layer outcomes into context for {@link SemanticRiskScorer}.
     */
    static void publishLayerSignals(
            String layerId, VerificationLayerResult result, VerificationContext context) {
        if (layerId == null || result == null || context == null) {
            return;
        }
        Map<String, Object> details = result.getDetails() != null ? result.getDetails() : Map.of();
        switch (layerId) {
            case "compile_verifier" -> {
                context.putConfig(SemanticRiskScorer.KEY_COMPILE_SUCCESS, !result.failed());
            }
            case "test_execution_verifier" -> {
                context.putConfig(SemanticRiskScorer.KEY_TEST_SUCCESS, !result.failed());
                context.putConfig(SemanticRiskScorer.KEY_TESTS_EXECUTED, true);
            }
            case "ast_structural_comparator" -> {
                Object delta = details.get("astDelta");
                if (delta instanceof Number n) {
                    context.putConfig(SemanticRiskScorer.KEY_AST_DELTA, n.doubleValue());
                } else if (result.getRiskContribution() > 0) {
                    context.putConfig(SemanticRiskScorer.KEY_AST_DELTA, 0.35);
                }
                Object thr = details.get("astDeltaThreshold");
                if (thr instanceof Number n) {
                    context.putConfig(SemanticRiskScorer.KEY_AST_THRESHOLD, n.doubleValue());
                }
            }
            case "bytecode_descriptor_comparator" ->
                    context.putConfig(SemanticRiskScorer.KEY_BYTECODE_MISMATCH,
                            result.failed() || result.getRiskContribution() >= 0.05);
            case "api_signature_diff_verifier" ->
                    context.putConfig(SemanticRiskScorer.KEY_API_SURFACE_CHANGED,
                            result.failed() || result.getRiskContribution() >= 0.05);
            default -> { /* other layers ignored for semantic aggregate */ }
        }
    }

    /**
     * The complete result of a verification pipeline execution.
     */
    public record PipelineResult(
            java.util.UUID patchId,
            Verdict verdict,
            double riskScore,
            double riskThreshold,
            List<VerificationLayerResult> layerResults,
            boolean earlyTermination,
            Instant completedAt,
            Duration totalDuration
    ) {
        /**
         * Returns true if the patch passed verification.
         */
        public boolean passed() {
            return verdict == Verdict.PASS;
        }

        /**
         * Returns true if the risk score is within the acceptable threshold.
         */
        public boolean withinRiskThreshold() {
            return riskScore <= riskThreshold;
        }

        /**
         * Returns the results that failed.
         */
        public List<VerificationLayerResult> failedLayers() {
            return layerResults.stream().filter(VerificationLayerResult::failed).toList();
        }

        /**
         * Returns a summary string for logging/display.
         */
        public String summary() {
            return "PipelineResult{patch=%s, verdict=%s, risk=%.3f/%.3f, layers=%d, failed=%d, time=%dms}"
                    .formatted(patchId, verdict, riskScore, riskThreshold,
                            layerResults.size(), failedLayers().size(), totalDuration.toMillis());
        }
    }
}
