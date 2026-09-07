package com.shadowstack.api.controllers;

import com.shadowstack.corpus.ControlEvidence;
import com.shadowstack.corpus.ControlEvidenceRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * ADMIN-only compliance evidence listing for SOC 2 control readiness.
 * Returns registered {@code ss_control_evidence} rows plus a static control catalog.
 * Demo profile (no JPA) still returns the static catalog.
 */
@RestController
@RequestMapping("/api/v1/compliance")
@Tag(name = "Compliance", description = "SOC 2 control readiness evidence — ADMIN only")
public class ControlEvidenceController {

    /**
     * Static catalog of Trust Service Criteria control IDs tracked in-repo.
     * Status values align with docs/soc2-controls.md checklist.
     */
    public static final List<ControlCatalogEntry> CONTROL_CATALOG = List.of(
            new ControlCatalogEntry("CC1.1", "Control Environment — governance & SoD", "Implemented",
                    "apps/api/src/main/java/com/shadowstack/api/service/ReviewService.java"),
            new ControlCatalogEntry("CC2.1", "Audit trail — durable audit_log", "Implemented",
                    "packages/migration-corpus/src/main/java/com/shadowstack/corpus/AuditService.java"),
            new ControlCatalogEntry("CC2.2", "Monitoring / health", "Partial",
                    "apps/api/src/main/resources/application.yml"),
            new ControlCatalogEntry("CC3.1", "Automated risk scoring", "Implemented",
                    "packages/verify-engine"),
            new ControlCatalogEntry("CC5.1", "RBAC / JWT authorization", "Implemented",
                    "apps/api/src/main/java/com/shadowstack/api/security/SecurityConfig.java"),
            new ControlCatalogEntry("CC5.2", "Patch immutability / certificates", "Partial",
                    "packages/verify-engine/src/main/java/com/shadowstack/verify/BehavioralEquivalenceCertificate.java"),
            new ControlCatalogEntry("CC5.3", "Multi-layer verification", "Implemented",
                    "packages/verify-engine"),
            new ControlCatalogEntry("CC6.1", "Authentication", "Implemented",
                    "apps/api/src/main/java/com/shadowstack/api/security/JwtAuthenticationFilter.java"),
            new ControlCatalogEntry("CC6.3", "Network policies", "Implemented",
                    "infra/k8s/networkpolicy.yaml"),
            new ControlCatalogEntry("CC7.1", "Observability", "Partial",
                    "apps/api/src/main/resources/application.yml"),
            new ControlCatalogEntry("CC8.1", "Verified transformation pipeline", "Implemented",
                    "packages/refactor-engine"),
            new ControlCatalogEntry("CC8.2", "Human approval workflow", "Implemented",
                    "apps/api/src/main/java/com/shadowstack/api/service/ReviewService.java"),
            new ControlCatalogEntry("CC9.2", "Data retention enforcement", "Implemented",
                    "apps/api/src/main/java/com/shadowstack/api/jobs/RetentionCleanupJob.java"),
            new ControlCatalogEntry("CC9.3", "Dependency vulnerability scanning", "Implemented",
                    ".github/workflows/ci.yml")
    );

    private final ObjectProvider<ControlEvidenceRepository> evidenceRepository;

    public ControlEvidenceController(ObjectProvider<ControlEvidenceRepository> evidenceRepository) {
        this.evidenceRepository = evidenceRepository;
    }

    @GetMapping("/evidence")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(
            summary = "List control evidence",
            description = "Registered evidence rows plus static SOC 2 control readiness catalog. "
                    + "Supports audit-ready claims only — not SOC 2 certification."
    )
    public ResponseEntity<EvidenceResponse> listEvidence() {
        List<EvidenceItem> registered = new ArrayList<>();
        ControlEvidenceRepository repo = evidenceRepository.getIfAvailable();
        if (repo != null) {
            for (ControlEvidence row : repo.findAllByOrderByControlIdAscCreatedAtDesc()) {
                registered.add(new EvidenceItem(
                        row.getId(),
                        row.getControlId(),
                        row.getEvidenceType(),
                        row.getPathOrHash(),
                        row.getCreatedAt() != null ? row.getCreatedAt().toInstant() : null,
                        row.getOrgId()
                ));
            }
        }

        Map<String, Object> banner = new LinkedHashMap<>();
        banner.put("claimAllowed", "SOC 2 control readiness / audit-ready controls");
        banner.put("claimForbidden", "SOC 2 certified");
        banner.put("doc", "docs/soc2-controls.md");

        return ResponseEntity.ok(new EvidenceResponse(banner, CONTROL_CATALOG, registered));
    }

    public record ControlCatalogEntry(
            String controlId,
            String title,
            String status,
            String evidencePath
    ) {}

    public record EvidenceItem(
            UUID id,
            String controlId,
            String evidenceType,
            String pathOrHash,
            Instant createdAt,
            UUID orgId
    ) {}

    public record EvidenceResponse(
            Map<String, Object> banner,
            List<ControlCatalogEntry> catalog,
            List<EvidenceItem> registeredEvidence
    ) {}
}
