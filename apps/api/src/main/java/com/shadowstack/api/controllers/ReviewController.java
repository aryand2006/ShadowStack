package com.shadowstack.api.controllers;

import com.shadowstack.api.dto.PatchDetailResponse;
import com.shadowstack.api.dto.ReviewDecisionRequest;
import com.shadowstack.api.dto.ReviewQueueItem;
import com.shadowstack.api.service.ReviewService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * REST controller for the human-in-the-loop review workflow.
 * Provides review queue, context retrieval, and decision recording.
 */
@RestController
@RequestMapping("/api/v1/reviews")
@Tag(name = "Reviews", description = "Human-in-the-loop review workflow for patch acceptance/rejection")
public class ReviewController {

    private final ReviewService reviewService;

    public ReviewController(ReviewService reviewService) {
        this.reviewService = reviewService;
    }

    @GetMapping("/queue")
    @PreAuthorize("hasAnyRole('ADMIN', 'REVIEWER')")
    @Operation(summary = "Get review queue", description = "List all patches awaiting human review")
    public ResponseEntity<List<ReviewQueueItem>> getReviewQueue() {
        return ResponseEntity.ok(reviewService.getReviewQueue());
    }

    @GetMapping("/{patchId}")
    @PreAuthorize("hasAnyRole('ADMIN', 'REVIEWER')")
    @Operation(
            summary = "Get review context",
            description = "Full review context: diff, rationale, invariants, risk score, verification evidence"
    )
    public ResponseEntity<PatchDetailResponse> getReviewContext(@PathVariable UUID patchId) {
        return reviewService.getReviewContext(patchId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/{patchId}/accept")
    @PreAuthorize("hasAnyRole('ADMIN', 'REVIEWER')")
    @Operation(summary = "Accept transformation", description = "Approve the patch for application")
    public ResponseEntity<PatchDetailResponse> acceptPatch(
            @PathVariable UUID patchId,
            Authentication authentication) {
        PatchDetailResponse result = reviewService.acceptPatch(patchId, authentication);
        return ResponseEntity.ok(result);
    }

    @PostMapping("/{patchId}/reject")
    @PreAuthorize("hasAnyRole('ADMIN', 'REVIEWER')")
    @Operation(summary = "Reject transformation", description = "Reject the patch with a reason")
    public ResponseEntity<PatchDetailResponse> rejectPatch(
            @PathVariable UUID patchId,
            @Valid @RequestBody ReviewDecisionRequest decision,
            Authentication authentication) {
        PatchDetailResponse result = reviewService.rejectPatch(patchId, decision, authentication);
        return ResponseEntity.ok(result);
    }

    @GetMapping("/history")
    @PreAuthorize("hasAnyRole('ADMIN', 'REVIEWER', 'ANALYST')")
    @Operation(summary = "Review history", description = "List all historically reviewed patches")
    public ResponseEntity<List<PatchDetailResponse>> getReviewHistory() {
        return ResponseEntity.ok(reviewService.getReviewHistory());
    }
}
