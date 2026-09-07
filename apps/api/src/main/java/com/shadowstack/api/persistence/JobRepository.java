package com.shadowstack.api.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface JobRepository extends JpaRepository<PersistedJob, UUID> {

    List<PersistedJob> findByStatus(String status);
}
