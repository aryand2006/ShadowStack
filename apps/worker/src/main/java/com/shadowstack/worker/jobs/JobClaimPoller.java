package com.shadowstack.worker.jobs;

import com.shadowstack.refactor.model.PatchUnit;
import com.shadowstack.refactor.model.RiskTier;
import com.shadowstack.verify.VerificationPipeline;
import com.shadowstack.verify.model.Verdict;
import com.shadowstack.verify.model.VerificationContext;
import com.shadowstack.verify.model.VerificationLayerResult;
import com.shadowstack.worker.tasks.VerificationTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Worker-owned VERIFY dequeue loop for prod/docker.
 *
 * <p>Claims {@code ss_jobs} with {@code FOR UPDATE SKIP LOCKED}, runs the 7-layer
 * {@link VerificationTask} pipeline from the job payload, then updates
 * {@code ss_patches} status / {@code verification_json} to mirror API
 * {@code RefactorOrchestrationService#runVerification} transitions.</p>
 */
@Component
public class JobClaimPoller {

    private static final Logger log = LoggerFactory.getLogger(JobClaimPoller.class);

    private final JobClaimService jobClaimService;
    private final VerificationTask verificationTask;
    private final String workerId;

    public JobClaimPoller(
            JobClaimService jobClaimService,
            VerificationTask verificationTask,
            @Value("${shadowstack.worker.id:shadowstack-worker}") String workerId) {
        this.jobClaimService = jobClaimService;
        this.verificationTask = verificationTask;
        this.workerId = workerId;
    }

    @Scheduled(fixedDelayString = "${shadowstack.worker.poll-interval-ms:5000}")
    public void poll() {
        Optional<JobClaimService.ClaimedJob> claimed = jobClaimService.claimNextVerify(workerId);
        if (claimed.isEmpty()) {
            return;
        }
        JobClaimService.ClaimedJob job = claimed.get();
        try {
            processVerify(job);
        } catch (Exception e) {
            String message = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            log.warn("VERIFY job {} failed: {}", job.id(), message, e);
            failPatchAndJob(job, message);
        }
    }

    private void processVerify(JobClaimService.ClaimedJob job) throws Exception {
        Map<String, Object> payload = job.payload();
        String original = job.string("originalSource");
        String transformed = job.string("transformedSource");
        if (original == null || original.isBlank() || transformed == null || transformed.isBlank()) {
            throw new IllegalStateException(
                    "VERIFY payload missing originalSource/transformedSource — re-enqueue from API with rich payload");
        }

        String language = job.string("language");
        if (language != null && !language.isBlank() && !isJavaEngineLanguage(language)) {
            throw new IllegalStateException(
                    "Worker VERIFY currently supports Java engine languages only (got '" + language
                            + "'). Enable API poller with SHADOWSTACK_JOBS_POLLER_ENABLED=true for non-Java.");
        }

        UUID patchId = job.patchId() != null
                ? job.patchId()
                : UUID.fromString(Objects.requireNonNull(job.string("patchId"), "patchId"));
        UUID projectId = job.projectId() != null
                ? job.projectId()
                : parseUuid(job.string("projectId"));

        PatchUnit unit = buildPatchUnit(patchId, payload, job);
        if (isIdentityPatch(unit)) {
            Instant now = Instant.now();
            Map<String, Object> evidence = verificationEvidence(
                    false, List.of(), List.of("identity-patch-rejected"),
                    Map.of("pipeline", "identity-patch-rejected", "verifier", "java"), now);
            jobClaimService.updatePatchVerification(
                    patchId, "VERIFICATION_FAILED", jobClaimService.toJson(evidence));
            jobClaimService.markSucceeded(job.id());
            log.info("VERIFY job {} marked identity patch {} as VERIFICATION_FAILED", job.id(), patchId);
            return;
        }

        Path projectRoot = Path.of(Objects.requireNonNullElse(
                job.string("projectRoot"), ".")).toAbsolutePath().normalize();

        VerificationContext context = VerificationContext.builder()
                .projectRoot(projectRoot)
                .sourceRoot(projectRoot)
                .originalSource(original)
                .transformedSource(transformed)
                .build();

        VerificationTask.VerificationResult result = verificationTask
                .execute(projectId != null ? projectId : patchId, unit, context, workerId)
                .join();

        VerificationPipeline.PipelineResult pipelineResult = result.pipelineResult();
        boolean passed = pipelineResult.verdict() == Verdict.PASS;

        List<String> verified = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        for (VerificationLayerResult layer : pipelineResult.layerResults()) {
            String summary = layer.getSummary() != null ? layer.getSummary() : layer.getLayerId();
            if (layer.failed()) {
                failed.add(summary);
            } else {
                verified.add(summary);
            }
        }

        Instant completed = Instant.now();
        Map<String, Object> evidence = verificationEvidence(
                passed, verified, failed,
                Map.of(
                        "verifier", "java",
                        "pipeline", pipelineResult.summary(),
                        "verdict", pipelineResult.verdict().name(),
                        "riskScore", pipelineResult.riskScore()),
                completed);

        String patchStatus = passed ? "PENDING_REVIEW" : "VERIFICATION_FAILED";
        double prior = 0.45;
        Object priorObj = payload.get("riskScore");
        if (priorObj instanceof Number n) {
            prior = n.doubleValue();
        }
        double verifyRisk = pipelineResult.riskScore();
        double blended = Math.max(prior, verifyRisk);
        if (!passed) {
            blended = Math.max(blended, 0.85);
        }
        String tier = blended <= 0.3 ? "LOW" : blended <= 0.6 ? "MEDIUM" : blended <= 0.85 ? "HIGH" : "CRITICAL";
        jobClaimService.updatePatchVerification(
                patchId, patchStatus, jobClaimService.toJson(evidence), blended, tier);
        jobClaimService.markSucceeded(job.id());
        log.info("VERIFY job {} completed for patch {} → {} (verdict={}, blendedRisk={})",
                job.id(), patchId, patchStatus, pipelineResult.verdict(), blended);
    }

    private void failPatchAndJob(JobClaimService.ClaimedJob job, String message) {
        UUID patchId = job.patchId();
        if (patchId == null && job.string("patchId") != null) {
            try { patchId = UUID.fromString(job.string("patchId")); }
            catch (Exception ignored) { patchId = null; }
        }
        if (patchId != null) {
            Instant now = Instant.now();
            Map<String, Object> evidence = verificationEvidence(
                    false, List.of(), List.of(message),
                    Map.of("pipeline", "worker-error", "error", message), now);
            try {
                jobClaimService.updatePatchVerification(
                        patchId, "VERIFICATION_FAILED", jobClaimService.toJson(evidence));
            } catch (Exception e) {
                log.warn("Could not update patch {} after job failure: {}", patchId, e.getMessage());
            }
        }
        jobClaimService.markFailed(job.id(), message);
    }

    private static PatchUnit buildPatchUnit(UUID patchId, Map<String, Object> payload, JobClaimService.ClaimedJob job) {
        String ruleName = Objects.requireNonNullElse(job.string("ruleName"), "unknown-rule");
        String filePath = Objects.requireNonNullElse(job.string("filePath"), "Unknown.java");
        String before = Objects.requireNonNullElse(job.string("beforeSnippet"), "");
        String after = Objects.requireNonNullElse(job.string("afterSnippet"), "");
        String diff = job.string("unifiedDiff");
        if (diff == null || diff.isBlank()) {
            diff = PatchUnit.computeUnifiedDiff(
                    List.of(before.split("\n", -1)),
                    List.of(after.split("\n", -1)),
                    filePath,
                    job.intValue("startLine", 1));
        }
        RiskTier riskTier = RiskTier.MEDIUM;
        String tierName = job.string("riskTier");
        if (tierName != null && !tierName.isBlank()) {
            try { riskTier = RiskTier.valueOf(tierName); }
            catch (IllegalArgumentException ignored) { riskTier = RiskTier.MEDIUM; }
        }
        double confidence = 0.8;
        Object confidenceObj = payload.get("confidence");
        if (confidenceObj instanceof Number number) {
            confidence = number.doubleValue();
        }
        return PatchUnit.builder(ruleName, filePath)
                .patchId(patchId)
                .startLine(job.intValue("startLine", 1))
                .endLine(job.intValue("endLine", 1))
                .beforeSnippet(before)
                .afterSnippet(after)
                .unifiedDiff(diff)
                .confidenceScore(confidence)
                .riskTier(riskTier)
                .rationale(job.string("rationale"))
                .build();
    }

    private static Map<String, Object> verificationEvidence(
            boolean behaviorallyEquivalent,
            List<String> verified,
            List<String> violated,
            Map<String, Object> proofArtifacts,
            Instant verifiedAt) {
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("behaviorallyEquivalent", behaviorallyEquivalent);
        evidence.put("testsPassed", 0);
        evidence.put("testsFailed", 0);
        evidence.put("testsSkipped", 0);
        evidence.put("invariantsVerified", verified);
        evidence.put("invariantsViolated", violated);
        evidence.put("proofArtifacts", proofArtifacts);
        evidence.put("verifiedAt", verifiedAt.toString());
        return evidence;
    }

    private static boolean isIdentityPatch(PatchUnit unit) {
        if (unit == null) return true;
        String diff = unit.getUnifiedDiff();
        if (diff == null || diff.isBlank()) return true;
        String before = unit.getBeforeSnippet() != null ? unit.getBeforeSnippet() : "";
        String after = unit.getAfterSnippet() != null ? unit.getAfterSnippet() : "";
        return Objects.equals(before, after);
    }

    private static boolean isJavaEngineLanguage(String languageId) {
        String key = languageId.trim().toLowerCase(Locale.ROOT);
        return key.isEmpty() || key.equals("java");
    }

    private static UUID parseUuid(String value) {
        if (value == null || value.isBlank()) return null;
        return UUID.fromString(value);
    }
}
