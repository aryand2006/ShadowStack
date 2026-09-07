package com.shadowstack.api.service;

import com.shadowstack.api.dto.PatchDetailResponse;
import com.shadowstack.api.dto.ReviewDecisionRequest;
import com.shadowstack.api.dto.ReviewQueueItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Manages the human-in-the-loop review workflow.
 * <p>
 * Provides the review queue, review context retrieval,
 * and decision recording (accept/reject with reason).
 * Enforces separation of duties: a REVIEWER cannot accept/reject
 * their own patches; ADMIN may override.
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
    public PatchDetailResponse acceptPatch(UUID patchId, Authentication authentication) {
        String reviewer = authentication.getName();
        enforceSeparationOfDuties(patchId, reviewer, authentication.getAuthorities());
        log.info("Patch accepted: patchId={}, reviewer={}", patchId, reviewer);
        return refactorService.applyReviewDecision(patchId, true, reviewer, null);
    }

    /**
     * Record a reject decision for a patch with reason.
     */
    public PatchDetailResponse rejectPatch(
            UUID patchId, ReviewDecisionRequest decision, Authentication authentication) {
        String reviewer = authentication.getName();
        enforceSeparationOfDuties(patchId, reviewer, authentication.getAuthorities());
        log.info("Patch rejected: patchId={}, reviewer={}, reason={}", patchId, reviewer, decision.reason());
        return refactorService.applyReviewDecision(patchId, false, reviewer, decision.reason());
    }

    /**
     * Get history of all reviewed patches.
     */
    public List<PatchDetailResponse> getReviewHistory() {
        return refactorService.getReviewedPatches();
    }

    /**
     * REVIEWER cannot accept/reject patches they authored ({@code createdBy}).
     * ADMIN may override. Patches with null {@code createdBy} (e.g. demo seed) are allowed.
     */
    void enforceSeparationOfDuties(
            UUID patchId, String reviewer, Collection<? extends GrantedAuthority> authorities) {
        PatchDetailResponse patch = refactorService.getPatch(patchId)
                .orElseThrow(() -> new RefactorOrchestrationService.PatchNotFoundException(patchId));
        String author = patch.createdBy();
        if (author == null || author.isBlank() || reviewer == null) {
            return;
        }
        if (!author.equalsIgnoreCase(reviewer)) {
            return;
        }
        boolean isAdmin = authorities != null && authorities.stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(a -> "ROLE_ADMIN".equals(a));
        if (isAdmin) {
            log.info("SoD override by ADMIN for own patch: patchId={}, actor={}", patchId, reviewer);
            return;
        }
        throw new AccessDeniedException(
                "Separation of duties: reviewers cannot accept or reject their own patches");
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
                patch.risk() != null ? patch.risk().evidenceStrength() : 0.0,
                patch.rationale(),
                verificationPassed,
                invariantsPreserved,
                invariantsTotal,
                patch.createdAt(),
                patch.verificationEvidence() != null ? patch.verificationEvidence().verifiedAt() : null
        );
    }
}
