package com.shadowstack.api.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ProjectRepository extends JpaRepository<PersistedProject, UUID> {

    List<PersistedProject> findByOrgId(UUID orgId);
}
