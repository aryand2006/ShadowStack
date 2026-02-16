package com.shadowstack.api.controllers;

import com.shadowstack.api.dto.VerificationResultResponse;
import com.shadowstack.api.dto.VerificationResultResponse.CertificateInfo;
import com.shadowstack.api.service.RefactorOrchestrationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * REST controller for the verification pipeline.
 * Runs behavioral equivalence checks and issues certificates.
 */
@RestController
@RequestMapping("/api/v1/patches/{patchId}")
@Tag(name = "Verification", description = "Behavioral equivalence verification and certificate issuance")
public class VerificationController {

    private final RefactorOrchestrationService refactorService;

    public VerificationController(RefactorOrchestrationService refactorService) {
        this.refactorService = refactorService;
    }

    @PostMapping("/verify")
    @PreAuthorize("hasAnyRole('ADMIN', 'REVIEWER')")
    @Operation(
            summary = "Run verification pipeline",
            description = "Execute the full verification pipeline: test execution, invariant checking, and equivalence proof"
    )
    public ResponseEntity<VerificationResultResponse> runVerification(@PathVariable UUID patchId) {
        VerificationResultResponse result = refactorService.runVerification(patchId);
        return ResponseEntity.accepted().body(result);
    }

    @GetMapping("/verification")
    @PreAuthorize("hasAnyRole('ADMIN', 'REVIEWER', 'ANALYST', 'VIEWER')")
    @Operation(summary = "Get verification results", description = "Retrieve verification results for a patch")
    public ResponseEntity<VerificationResultResponse> getVerificationResults(@PathVariable UUID patchId) {
        return refactorService.getVerificationResult(patchId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/certificate")
    @PreAuthorize("hasAnyRole('ADMIN', 'REVIEWER', 'ANALYST', 'VIEWER')")
    @Operation(
            summary = "Get equivalence certificate",
            description = "Retrieve the behavioral equivalence certificate for a verified patch"
    )
    public ResponseEntity<CertificateInfo> getCertificate(@PathVariable UUID patchId) {
        return refactorService.getVerificationResult(patchId)
                .map(VerificationResultResponse::certificate)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }
}
