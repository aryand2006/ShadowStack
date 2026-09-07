package com.shadowstack.api.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface PatchRepository extends JpaRepository<PersistedPatch, UUID> {

    List<PersistedPatch> findByProjectId(UUID projectId);

    List<PersistedPatch> findByStatus(String status);

    List<PersistedPatch> findByStatusAndOrgId(String status, UUID orgId);

    List<PersistedPatch> findByOrgId(UUID orgId);

    /**
     * Archive verification evidence older than cutoff by clearing {@code verification_json}.
     * Patch metadata and review history remain; bulky evidence payload is removed.
     */
    @Modifying(clearAutomatically = true)
    @Query("""
            UPDATE PersistedPatch p
            SET p.verificationJson = NULL, p.updatedAt = :now
            WHERE p.verificationJson IS NOT NULL
              AND p.updatedAt < :cutoff
            """)
    int archiveVerificationEvidenceOlderThan(@Param("cutoff") Instant cutoff, @Param("now") Instant now);
}
