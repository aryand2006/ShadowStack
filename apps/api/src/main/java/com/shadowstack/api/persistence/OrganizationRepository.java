package com.shadowstack.api.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface OrganizationRepository extends JpaRepository<PersistedOrganization, UUID> {

    Optional<PersistedOrganization> findByNameIgnoreCase(String name);

    boolean existsByNameIgnoreCase(String name);
}
