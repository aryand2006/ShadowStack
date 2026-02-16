package com.shadowstack.api.service;

import com.shadowstack.api.dto.PatchDetailResponse;
import com.shadowstack.api.dto.ReviewDecisionRequest;
import com.shadowstack.api.dto.ReviewQueueItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Manages the human-in-the-loop review workflow.
 * <p>
 * Provides the review queue, review context retrieval,
 * and decision recording (accept/reject with reason).
 */
@Service
public class ReviewService {

    private static final Logger log = LoggerFactory.getLogger(ReviewService.class);

    private final RefactorOrchestrationService refactorService;
    private final ProjectService projectService;

    public ReviewService(RefactorOrchestrationService refactorService,
                         ProjectService projectService) {
        this.refactorService = refactorService;
        this.projectService = projectService;
    }

    /**
     * Get the queue of patches awaiting human review.
     */
    public List<ReviewQueueItem> getReviewQueue() {
        return refactorService.getPendingReviewPatches().stream()
                .map(this::toQueueItem)
                .toList();
    }

    /**
     * Get the full review context for a patch including diff,
     * rationale, invariants, risk score, and verification evidence.
     */
    public Optional<PatchDetailResponse> getReviewContext(UUID patchId) {
        return refactorService.getPatch(patchId);
    }

    /**
     * Record an accept decision for a patch.
     */
    public PatchDetailResponse acceptPatch(UUID patchId, String reviewer) {
        log.info("Patch accepted: patchId={}, reviewer={}", patchId, reviewer);
        return refactorService.applyReviewDecision(patchId, true, reviewer, null);
    }

    /**
     * Record a reject decision for a patch with reason.
     */
    public PatchDetailResponse rejectPatch(UUID patchId, ReviewDecisionRequest decision, String reviewer) {
        log.info("Patch rejected: patchId={}, reviewer={}, reason={}", patchId, reviewer, decision.reason());
        return refactorService.applyReviewDecision(patchId, false, reviewer, decision.reason());
    }

    /**
     * Get history of all reviewed patches.
     */
    public List<PatchDetailResponse> getReviewHistory() {
        return refactorService.getReviewedPatches();
    }

    private ReviewQueueItem toQueueItem(PatchDetailResponse patch) {
        String projectName = projectService.getProject(patch.projectId())
                .map(p -> p.name())
                .orElse("Unknown");

        int invariantsPreserved = 0;
        int invariantsTotal = 0;
        boolean verificationPassed = false;

        if (patch.verificationEvidence() != null) {
            verificationPassed = patch.verificationEvidence().behaviorallyEquivalent();
            invariantsPreserved = patch.verificationEvidence().invariantsVerified() != null
                    ? patch.verificationEvidence().invariantsVerified().size() : 0;
            invariantsTotal = invariantsPreserved +
                    (patch.verificationEvidence().invariantsViolated() != null
                            ? patch.verificationEvidence().invariantsViolated().size() : 0);
        }

        return new ReviewQueueItem(
                patch.patchId(),
                patch.projectId(),
                projectName,
                patch.ruleName(),
                patch.ruleCategory(),
                patch.filePath(),
                patch.startLine(),
                patch.endLine(),
                patch.risk() != null ? patch.risk().score() : 0.0,
                patch.risk() != null ? patch.risk().tier() : null,
                patch.rationale(),
                verificationPassed,
                invariantsPreserved,
                invariantsTotal,
                patch.createdAt(),
                patch.verificationEvidence() != null ? patch.verificationEvidence().verifiedAt() : null
        );
    }
}
