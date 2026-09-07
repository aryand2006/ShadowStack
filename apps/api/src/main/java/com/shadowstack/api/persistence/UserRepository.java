package com.shadowstack.api.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends JpaRepository<PersistedUser, UUID> {

    Optional<PersistedUser> findByUsernameIgnoreCase(String username);

    List<PersistedUser> findByOrgIdOrderByUsernameAsc(UUID orgId);

    boolean existsByUsernameIgnoreCase(String username);

    long count();
}
