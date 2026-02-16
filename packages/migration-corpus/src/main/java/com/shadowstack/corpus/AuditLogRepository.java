package com.shadowstack.corpus;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Repository for audit log persistence.
 */
@Repository
public interface AuditLogRepository extends JpaRepository<AuditLog, UUID> {

    List<AuditLog> findByEntityTypeAndEntityId(String entityType, String entityId);

    List<AuditLog> findByActorId(String actorId);

    List<AuditLog> findByAction(String action);

    List<AuditLog> findByTimestampBetween(OffsetDateTime start, OffsetDateTime end);
}
