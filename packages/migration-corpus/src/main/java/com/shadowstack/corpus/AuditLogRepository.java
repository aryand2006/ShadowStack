package com.shadowstack.corpus;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
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

    @Query("SELECT a FROM AuditLog a WHERE a.deletedAt IS NULL AND a.timestamp < :cutoff")
    List<AuditLog> findActiveOlderThan(@Param("cutoff") OffsetDateTime cutoff);

    @Modifying(clearAutomatically = true)
    @Query("UPDATE AuditLog a SET a.deletedAt = :now WHERE a.deletedAt IS NULL AND a.timestamp < :cutoff")
    int softDeleteOlderThan(@Param("cutoff") OffsetDateTime cutoff, @Param("now") OffsetDateTime now);
}
