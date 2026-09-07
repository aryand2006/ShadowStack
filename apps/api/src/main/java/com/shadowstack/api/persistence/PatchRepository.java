package com.shadowstack.api.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface PatchRepository extends JpaRepository<PersistedPatch, UUID> {

    List<PersistedPatch> findByProjectId(UUID projectId);

    List<PersistedPatch> findByStatus(String status);

    List<PersistedPatch> findByStatusAndOrgId(String status, UUID orgId);

    List<PersistedPatch> findByOrgId(UUID orgId);
}
