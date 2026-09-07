package com.shadowstack.corpus;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * Persistence for {@link ControlEvidence} registry rows.
 */
@Repository
public interface ControlEvidenceRepository extends JpaRepository<ControlEvidence, UUID> {

    List<ControlEvidence> findByControlId(String controlId);

    List<ControlEvidence> findByOrgId(UUID orgId);

    List<ControlEvidence> findAllByOrderByControlIdAscCreatedAtDesc();
}
