package com.shadowstack.api.controllers;

import com.shadowstack.api.dto.PatchDetailResponse;
import com.shadowstack.api.service.RefactorOrchestrationService;
import com.shadowstack.api.service.RefactorOrchestrationService.CandidateInfo;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * REST controller for the refactoring workflow.
 * Handles static analysis, candidate listing, patch generation, and patch retrieval.
 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}")
@Tag(name = "Refactoring", description = "Static analysis, candidate identification, and patch generation")
public class RefactorController {

    private final RefactorOrchestrationService refactorService;

    public RefactorController(RefactorOrchestrationService refactorService) {
        this.refactorService = refactorService;
    }

    @PostMapping("/analyze")
    @PreAuthorize("hasAnyRole('ADMIN', 'REVIEWER')")
    @Operation(summary = "Run static analysis", description = "Analyze the project to identify refactor candidates")
    public ResponseEntity<List<CandidateInfo>> runAnalysis(@PathVariable UUID projectId) {
        List<CandidateInfo> candidates = refactorService.runAnalysis(projectId);
        return ResponseEntity.accepted().body(candidates);
    }

    @GetMapping("/candidates")
    @PreAuthorize("hasAnyRole('ADMIN', 'REVIEWER', 'ANALYST', 'VIEWER')")
    @Operation(summary = "List refactor candidates", description = "Get all identified refactor candidates for the project")
    public ResponseEntity<List<CandidateInfo>> getCandidates(@PathVariable UUID projectId) {
        return ResponseEntity.ok(refactorService.getCandidates(projectId));
    }

    @PostMapping("/candidates/{candidateId}/generate-patch")
    @PreAuthorize("hasAnyRole('ADMIN', 'REVIEWER')")
    @Operation(summary = "Generate a patch", description = "Generate a refactoring patch for a specific candidate")
    public ResponseEntity<PatchDetailResponse> generatePatch(
            @PathVariable UUID projectId,
            @PathVariable UUID candidateId) {
        PatchDetailResponse patch = refactorService.generatePatch(projectId, candidateId);
        return ResponseEntity.status(HttpStatus.CREATED).body(patch);
    }

    @GetMapping("/patches")
    @PreAuthorize("hasAnyRole('ADMIN', 'REVIEWER', 'ANALYST', 'VIEWER')")
    @Operation(summary = "List patches", description = "Get all generated patches for the project")
    public ResponseEntity<List<PatchDetailResponse>> getPatches(@PathVariable UUID projectId) {
        return ResponseEntity.ok(refactorService.getPatches(projectId));
    }

    @GetMapping("/patches/{patchId}")
    @PreAuthorize("hasAnyRole('ADMIN', 'REVIEWER', 'ANALYST', 'VIEWER')")
    @Operation(
            summary = "Get patch detail",
            description = "Retrieve full patch detail including diff, rationale, safety invariants, and risk score"
    )
    public ResponseEntity<PatchDetailResponse> getPatchDetail(
            @PathVariable UUID projectId,
            @PathVariable UUID patchId) {
        return refactorService.getPatch(patchId)
                .filter(patch -> patch.projectId().equals(projectId))
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }
}
