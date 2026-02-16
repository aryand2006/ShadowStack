package com.shadowstack.worker.tasks;

import com.shadowstack.corpus.AuditService;
import com.shadowstack.refactor.model.PatchUnit;
import com.shadowstack.verify.BehavioralEquivalenceCertificate;
import com.shadowstack.verify.VerificationPipeline;
import com.shadowstack.verify.VerificationPipeline.PipelineResult;
import com.shadowstack.verify.layers.*;
import com.shadowstack.verify.model.VerificationContext;
import com.shadowstack.verify.model.Verdict;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * Executes Phase 3 multi-layer verification for generated patches.
 *
 * <p>Runs the full ShadowStack verification pipeline against each patch unit,
 * producing a {@link BehavioralEquivalenceCertificate} that attests to the
 * behavioral equivalence of the code transformation.</p>
 *
 * <h3>Verification layers (executed in order)</h3>
 * <ol>
 *   <li>{@link CompileVerifier} — Ensures the patched code compiles</li>
 *   <li>{@link TestExecutionVerifier} — Runs existing tests against the patched code</li>
 *   <li>{@link ASTStructuralComparator} — Compares AST structure before/after</li>
 *   <li>{@link BytecodeDescriptorComparator} — Compares bytecode method descriptors</li>
 *   <li>{@link APISignatureDiffVerifier} — Validates API surface compatibility</li>
 *   <li>{@link SemanticRiskScorer} — Computes semantic risk score for the transformation</li>
 *   <li>{@link GoldenMasterVerifier} — Validates against golden master outputs</li>
 * </ol>
 */
@Component
public class VerificationTask {

    private static final Logger LOG = LoggerFactory.getLogger(VerificationTask.class);

    private final AuditService auditService;
    private final ThreadPoolTaskExecutor executor;

    @Value("${shadowstack.verification.risk-threshold:0.7}")
    private double riskThreshold;

    @Value("${shadowstack.verification.fail-fast:false}")
    private boolean failFast;

    public VerificationTask(
            AuditService auditService,
            @Qualifier("workerTaskExecutor") ThreadPoolTaskExecutor executor) {
        this.auditService = auditService;
        this.executor = executor;
    }

    /**
     * Result container for verification.
     *
     * @param pipelineResult  the raw pipeline result
     * @param certificate     the behavioral equivalence certificate (null if verification failed)
     * @param passed          whether the patch passed verification
     */
    public record VerificationResult(
            PipelineResult pipelineResult,
            BehavioralEquivalenceCertificate certificate,
            boolean passed
    ) {}

    /**
     * Executes the full verification pipeline for a single patch.
     *
     * @param projectId unique project identifier
     * @param patch     the patch unit to verify
     * @param context   the verification context
     * @param actorId   the initiating user or system identity
     * @return a CompletableFuture containing the verification result
     */
    public CompletableFuture<VerificationResult> execute(
            UUID projectId,
            PatchUnit patch,
            VerificationContext context,
            String actorId) {

        Objects.requireNonNull(projectId, "projectId must not be null");
        Objects.requireNonNull(patch, "patch must not be null");
        Objects.requireNonNull(context, "context must not be null");
        Objects.requireNonNull(actorId, "actorId must not be null");

        return CompletableFuture.supplyAsync(() -> {
            Instant start = Instant.now();
            UUID taskId = UUID.randomUUID();

            LOG.info("[task={}] Starting verification for patch {} (rule='{}', file='{}', lines={}-{})",
                    taskId, patch.getPatchId(), patch.getRuleId(),
                    patch.getSourceFile(), patch.getStartLine(), patch.getEndLine());

            auditService.logAction(
                    "VERIFICATION_STARTED",
                    "patch",
                    patch.getPatchId().toString(),
                    actorId,
                    "worker",
                    Map.of(
                            "taskId", taskId.toString(),
                            "projectId", projectId.toString(),
                            "ruleId", patch.getRuleId(),
                            "sourceFile", patch.getSourceFile(),
                            "startLine", patch.getStartLine(),
                            "endLine", patch.getEndLine()
                    )
            );

            try {
                VerificationPipeline pipeline = createPipeline();
                PipelineResult pipelineResult = pipeline.execute(patch, context);

                Duration elapsed = Duration.between(start, Instant.now());
                boolean passed = pipelineResult.passed();

                LOG.info("[task={}] Verification {} in {}ms — verdict={}, risk={:.3f}/{:.3f}, " +
                                "layers={}, failed={}",
                        taskId, passed ? "PASSED" : "FAILED",
                        elapsed.toMillis(), pipelineResult.verdict(),
                        pipelineResult.riskScore(), pipelineResult.riskThreshold(),
                        pipelineResult.layerResults().size(),
                        pipelineResult.failedLayers().size());

                BehavioralEquivalenceCertificate certificate = null;
                if (passed) {
                    certificate = BehavioralEquivalenceCertificate
                            .fromPipelineResult(pipelineResult)
                            .sourceFile(patch.getSourceFile())
                            .startLine(patch.getStartLine())
                            .endLine(patch.getEndLine())
                            .ruleId(patch.getRuleId())
                            .addMetadata("projectId", projectId.toString())
                            .addMetadata("taskId", taskId.toString())
                            .addMetadata("verificationDurationMs", elapsed.toMillis())
                            .build();

                    LOG.info("[task={}] Behavioral equivalence certificate issued: {}",
                            taskId, certificate.getCertificateId());
                }

                String auditAction = passed ? "VERIFICATION_PASSED" : "VERIFICATION_FAILED";
                Map<String, Object> auditDetails = new LinkedHashMap<>();
                auditDetails.put("taskId", taskId.toString());
                auditDetails.put("projectId", projectId.toString());
                auditDetails.put("verdict", pipelineResult.verdict().name());
                auditDetails.put("riskScore", pipelineResult.riskScore());
                auditDetails.put("riskThreshold", pipelineResult.riskThreshold());
                auditDetails.put("durationMs", elapsed.toMillis());
                auditDetails.put("layersExecuted", pipelineResult.layerResults().size());
                auditDetails.put("layersFailed", pipelineResult.failedLayers().size());
                if (certificate != null) {
                    auditDetails.put("certificateId", certificate.getCertificateId().toString());
                }

                auditService.logAction(
                        auditAction,
                        "patch",
                        patch.getPatchId().toString(),
                        actorId,
                        "worker",
                        auditDetails
                );

                return new VerificationResult(pipelineResult, certificate, passed);

            } catch (Exception e) {
                Duration elapsed = Duration.between(start, Instant.now());
                LOG.error("[task={}] Verification failed with exception after {}ms: {}",
                        taskId, elapsed.toMillis(), e.getMessage(), e);

                auditService.logAction(
                        "VERIFICATION_ERROR",
                        "patch",
                        patch.getPatchId().toString(),
                        actorId,
                        "worker",
                        Map.of(
                                "taskId", taskId.toString(),
                                "projectId", projectId.toString(),
                                "durationMs", elapsed.toMillis(),
                                "error", e.getMessage() != null ? e.getMessage() : "unknown"
                        )
                );

                throw new RuntimeException(
                        "Verification failed for patch " + patch.getPatchId(), e);
            }
        }, executor);
    }

    /**
     * Executes verification for a batch of patches, returning results for each.
     *
     * @param projectId unique project identifier
     * @param patches   the patches to verify
     * @param context   the verification context
     * @param actorId   the initiating user or system identity
     * @return a CompletableFuture containing results for all patches
     */
    public CompletableFuture<List<VerificationResult>> executeBatch(
            UUID projectId,
            List<PatchUnit> patches,
            VerificationContext context,
            String actorId) {

        LOG.info("Starting batch verification for {} patches in project {}", patches.size(), projectId);

        List<CompletableFuture<VerificationResult>> futures = patches.stream()
                .map(patch -> execute(projectId, patch, context, actorId))
                .toList();

        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .thenApply(v -> futures.stream()
                        .map(CompletableFuture::join)
                        .toList());
    }

    /**
     * Creates and configures the verification pipeline with all layers.
     */
    private VerificationPipeline createPipeline() {
        VerificationPipeline pipeline = new VerificationPipeline(riskThreshold, failFast);
        pipeline.addLayer(new CompileVerifier());
        pipeline.addLayer(new TestExecutionVerifier());
        pipeline.addLayer(new ASTStructuralComparator());
        pipeline.addLayer(new BytecodeDescriptorComparator());
        pipeline.addLayer(new APISignatureDiffVerifier());
        pipeline.addLayer(new SemanticRiskScorer());
        pipeline.addLayer(new GoldenMasterVerifier());

        LOG.debug("Verification pipeline created with {} layers, riskThreshold={}, failFast={}",
                pipeline.getLayers().size(), riskThreshold, failFast);

        return pipeline;
    }
}
