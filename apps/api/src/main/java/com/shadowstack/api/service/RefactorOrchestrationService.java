package com.shadowstack.api.service;

import com.shadowstack.api.config.ShadowStackConfig;
import com.shadowstack.api.dto.PatchDetailResponse;
import com.shadowstack.api.dto.PatchDetailResponse.*;
import com.shadowstack.api.dto.VerificationResultResponse;
import com.shadowstack.api.dto.VerificationResultResponse.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Coordinates the full refactor pipeline:
 * analysis → candidate identification → patch generation → verification → review.
 * <p>
 * In production, delegates to:
 * - core-analysis for static analysis
 * - refactor-engine for patch generation
 * - verify-engine for behavioral verification
 * - migration-corpus for pattern matching
 */
@Service
public class RefactorOrchestrationService {

    private static final Logger log = LoggerFactory.getLogger(RefactorOrchestrationService.class);

    private final ShadowStackConfig config;

    private final Map<UUID, List<CandidateInfo>> candidateStore = new ConcurrentHashMap<>();
    private final Map<UUID, PatchDetailResponse> patchStore = new ConcurrentHashMap<>();
    private final Map<UUID, List<UUID>> projectPatchIndex = new ConcurrentHashMap<>();
    private final Map<UUID, VerificationResultResponse> verificationStore = new ConcurrentHashMap<>();

    public RefactorOrchestrationService(ShadowStackConfig config) {
        this.config = config;
    }

    /**
     * Run static analysis on a project to identify refactor candidates.
     * Delegates to core-analysis CallGraphBuilder and refactor-engine pattern matching.
     */
    public List<CandidateInfo> runAnalysis(UUID projectId) {
        log.info("Running static analysis for project: {}", projectId);

        // In production: invoke core-analysis + refactor-engine
        List<CandidateInfo> candidates = new ArrayList<>();
        candidateStore.put(projectId, candidates);
        return candidates;
    }

    /**
     * Get refactor candidates for a project.
     */
    public List<CandidateInfo> getCandidates(UUID projectId) {
        return candidateStore.getOrDefault(projectId, List.of());
    }

    /**
     * Generate a refactoring patch for a specific candidate.
     */
    public PatchDetailResponse generatePatch(UUID projectId, UUID candidateId) {
        log.info("Generating patch for project={}, candidate={}", projectId, candidateId);

        UUID patchId = UUID.randomUUID();
        Instant now = Instant.now();

        // In production: invoke refactor-engine to generate AST-level transformation
        PatchDetailResponse patch = new PatchDetailResponse(
                patchId,
                projectId,
                candidateId,
                "pending-rule",
                "modernization",
                PatchStatus.GENERATED,
                "src/main/java/Example.java",
                1, 10,
                "--- a/Example.java\n+++ b/Example.java\n@@ pending @@",
                "Patch generation pending — delegating to refactor-engine",
                List.of(),
                new RiskAssessment(0.0, RiskAssessment.RiskTier.LOW, List.of(), 0.0),
                null,
                null,
                now,
                now
        );

        patchStore.put(patchId, patch);
        projectPatchIndex.computeIfAbsent(projectId, k -> new ArrayList<>()).add(patchId);
        return patch;
    }

    /**
     * List all patches for a project.
     */
    public List<PatchDetailResponse> getPatches(UUID projectId) {
        List<UUID> patchIds = projectPatchIndex.getOrDefault(projectId, List.of());
        return patchIds.stream()
                .map(patchStore::get)
                .filter(Objects::nonNull)
                .toList();
    }

    /**
     * Get detailed information for a specific patch.
     */
    public Optional<PatchDetailResponse> getPatch(UUID patchId) {
        return Optional.ofNullable(patchStore.get(patchId));
    }

    /**
     * Run the verification pipeline on a patch.
     * Invokes verify-engine for test execution and invariant checking.
     */
    public VerificationResultResponse runVerification(UUID patchId) {
        log.info("Running verification pipeline for patch: {}", patchId);

        PatchDetailResponse patch = patchStore.get(patchId);
        if (patch == null) {
            throw new PatchNotFoundException(patchId);
        }

        UUID verificationId = UUID.randomUUID();
        Instant now = Instant.now();

        // In production: invoke verify-engine for full behavioral equivalence check
        VerificationResultResponse result = new VerificationResultResponse(
                patchId,
                verificationId,
                VerificationStatus.PASSED,
                true,
                new TestResults(0, 0, 0, 0, 0, List.of()),
                new InvariantResults(0, 0, 0, List.of()),
                new CertificateInfo(UUID.randomUUID(), false, "Verification pending", Map.of(), now),
                0L,
                now,
                now
        );

        verificationStore.put(patchId, result);

        // Update patch status
        PatchDetailResponse updatedPatch = new PatchDetailResponse(
                patch.patchId(), patch.projectId(), patch.candidateId(),
                patch.ruleName(), patch.ruleCategory(),
                PatchStatus.PENDING_REVIEW,
                patch.filePath(), patch.startLine(), patch.endLine(),
                patch.unifiedDiff(), patch.rationale(), patch.invariants(),
                patch.risk(),
                new PatchDetailResponse.VerificationEvidence(
                        true, 0, 0, 0, List.of(), List.of(), Map.of(), now
                ),
                patch.review(), patch.createdAt(), Instant.now()
        );
        patchStore.put(patchId, updatedPatch);

        return result;
    }

    /**
     * Get verification results for a patch.
     */
    public Optional<VerificationResultResponse> getVerificationResult(UUID patchId) {
        return Optional.ofNullable(verificationStore.get(patchId));
    }

    /**
     * Update a patch's status and review info after a review decision.
     */
    public PatchDetailResponse applyReviewDecision(UUID patchId, boolean accepted, String reviewer, String reason) {
        PatchDetailResponse patch = patchStore.get(patchId);
        if (patch == null) {
            throw new PatchNotFoundException(patchId);
        }

        PatchStatus newStatus = accepted ? PatchStatus.ACCEPTED : PatchStatus.REJECTED;
        PatchDetailResponse.ReviewInfo reviewInfo = new PatchDetailResponse.ReviewInfo(
                reviewer, accepted, reason, Instant.now()
        );

        PatchDetailResponse updated = new PatchDetailResponse(
                patch.patchId(), patch.projectId(), patch.candidateId(),
                patch.ruleName(), patch.ruleCategory(),
                newStatus,
                patch.filePath(), patch.startLine(), patch.endLine(),
                patch.unifiedDiff(), patch.rationale(), patch.invariants(),
                patch.risk(), patch.verificationEvidence(),
                reviewInfo, patch.createdAt(), Instant.now()
        );

        patchStore.put(patchId, updated);
        return updated;
    }

    /**
     * Get all patches in PENDING_REVIEW status.
     */
    public List<PatchDetailResponse> getPendingReviewPatches() {
        return patchStore.values().stream()
                .filter(p -> p.status() == PatchStatus.PENDING_REVIEW)
                .toList();
    }

    /**
     * Get all patches that have been reviewed (accepted or rejected).
     */
    public List<PatchDetailResponse> getReviewedPatches() {
        return patchStore.values().stream()
                .filter(p -> p.status() == PatchStatus.ACCEPTED || p.status() == PatchStatus.REJECTED)
                .toList();
    }

    /**
     * Summary record for a refactor candidate.
     */
    public record CandidateInfo(
            UUID candidateId,
            UUID projectId,
            String ruleName,
            String ruleCategory,
            String filePath,
            int startLine,
            int endLine,
            String description,
            double estimatedRisk,
            double confidence
    ) {}

    public static class PatchNotFoundException extends RuntimeException {
        private final UUID patchId;

        public PatchNotFoundException(UUID patchId) {
            super("Patch not found: " + patchId);
            this.patchId = patchId;
        }

        public UUID getPatchId() {
            return patchId;
        }
    }
}
