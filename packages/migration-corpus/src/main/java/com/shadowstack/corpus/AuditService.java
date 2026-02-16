package com.shadowstack.corpus;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Service responsible for recording all auditable actions in the system.
 * All corpus mutations (accept, reject, create, update) are logged with
 * actor information, timestamps, and contextual details.
 *
 * Audit writes are performed in a separate transaction to avoid
 * blocking the primary operation if audit persistence fails.
 */
@Service
public class AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);

    private final AuditLogRepository auditLogRepository;
    private final ObjectMapper objectMapper;

    public AuditService(AuditLogRepository auditLogRepository, ObjectMapper objectMapper) {
        this.auditLogRepository = auditLogRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * Log an auditable action. Runs in a new transaction so that a failure to
     * write the audit record does not roll back the primary business operation.
     *
     * @param action     the action name (e.g., TRANSFORMATION_ACCEPTED)
     * @param entityType the type of entity acted upon
     * @param entityId   the ID of the entity
     * @param actorId    who performed the action
     * @param actorRole  the role of the actor
     * @param details    additional contextual details (serialized as JSONB)
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void logAction(String action, String entityType, String entityId,
                          String actorId, String actorRole, Map<String, Object> details) {
        Objects.requireNonNull(action, "Action must not be null");
        Objects.requireNonNull(entityType, "Entity type must not be null");
        Objects.requireNonNull(entityId, "Entity ID must not be null");
        Objects.requireNonNull(actorId, "Actor ID must not be null");
        Objects.requireNonNull(actorRole, "Actor role must not be null");

        try {
            AuditLog entry = new AuditLog();
            entry.setAction(action);
            entry.setEntityType(entityType);
            entry.setEntityId(entityId);
            entry.setActorId(actorId);
            entry.setActorRole(actorRole);
            entry.setTimestamp(OffsetDateTime.now());

            if (details != null && !details.isEmpty()) {
                JsonNode detailsNode = objectMapper.valueToTree(details);
                entry.setDetails(detailsNode);
            }

            auditLogRepository.save(entry);

            log.debug("Audit logged: action={}, entityType={}, entityId={}, actor={}",
                    action, entityType, entityId, actorId);

        } catch (Exception e) {
            log.error("Failed to write audit log: action={}, entityType={}, entityId={}, actor={}",
                    action, entityType, entityId, actorId, e);
        }
    }

    /**
     * Log an action with an associated IP address (e.g., from an HTTP request).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void logActionWithIp(String action, String entityType, String entityId,
                                String actorId, String actorRole,
                                Map<String, Object> details, String ipAddress) {
        Objects.requireNonNull(action, "Action must not be null");

        try {
            AuditLog entry = new AuditLog();
            entry.setAction(action);
            entry.setEntityType(entityType);
            entry.setEntityId(entityId);
            entry.setActorId(actorId);
            entry.setActorRole(actorRole);
            entry.setTimestamp(OffsetDateTime.now());
            entry.setIpAddress(ipAddress);

            if (details != null && !details.isEmpty()) {
                JsonNode detailsNode = objectMapper.valueToTree(details);
                entry.setDetails(detailsNode);
            }

            auditLogRepository.save(entry);

            log.debug("Audit logged: action={}, entityType={}, entityId={}, actor={}, ip={}",
                    action, entityType, entityId, actorId, ipAddress);

        } catch (Exception e) {
            log.error("Failed to write audit log with IP: action={}, entityType={}, entityId={}",
                    action, entityType, entityId, e);
        }
    }

    /**
     * Retrieve audit trail for a specific entity.
     */
    @Transactional(readOnly = true)
    public List<AuditLog> getAuditTrail(String entityType, String entityId) {
        return auditLogRepository.findByEntityTypeAndEntityId(entityType, entityId);
    }

    /**
     * Retrieve all audit entries for a given actor.
     */
    @Transactional(readOnly = true)
    public List<AuditLog> getActorHistory(String actorId) {
        return auditLogRepository.findByActorId(actorId);
    }

    /**
     * Retrieve audit entries within a time range.
     */
    @Transactional(readOnly = true)
    public List<AuditLog> getAuditLogsBetween(OffsetDateTime start, OffsetDateTime end) {
        return auditLogRepository.findByTimestampBetween(start, end);
    }
}
