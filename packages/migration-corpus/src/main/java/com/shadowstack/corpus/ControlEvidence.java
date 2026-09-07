package com.shadowstack.corpus;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Registry row pointing at SOC 2 control readiness evidence (file path or hash).
 * Supports audit-ready control catalogs — not a certification claim.
 */
@Entity
@Table(name = "ss_control_evidence", indexes = {
        @Index(name = "idx_ss_control_evidence_control", columnList = "controlId"),
        @Index(name = "idx_ss_control_evidence_org", columnList = "orgId"),
        @Index(name = "idx_ss_control_evidence_created", columnList = "createdAt")
})
public class ControlEvidence {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "control_id", nullable = false, length = 64)
    private String controlId;

    @Column(name = "evidence_type", nullable = false, length = 128)
    private String evidenceType;

    @Column(name = "path_or_hash", nullable = false, length = 2048)
    private String pathOrHash;

    @Column(name = "created_at", nullable = false, columnDefinition = "TIMESTAMP WITH TIME ZONE")
    private OffsetDateTime createdAt;

    @Column(name = "org_id")
    private UUID orgId;

    public ControlEvidence() {
    }

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = OffsetDateTime.now();
        }
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public String getControlId() {
        return controlId;
    }

    public void setControlId(String controlId) {
        this.controlId = controlId;
    }

    public String getEvidenceType() {
        return evidenceType;
    }

    public void setEvidenceType(String evidenceType) {
        this.evidenceType = evidenceType;
    }

    public String getPathOrHash() {
        return pathOrHash;
    }

    public void setPathOrHash(String pathOrHash) {
        this.pathOrHash = pathOrHash;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(OffsetDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public UUID getOrgId() {
        return orgId;
    }

    public void setOrgId(UUID orgId) {
        this.orgId = orgId;
    }
}
