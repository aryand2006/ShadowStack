package com.shadowstack.corpus;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * JPA entity representing an audit log entry.
 * Tracks all actions performed on corpus entities for compliance and traceability.
 */
@Entity
@Table(name = "audit_log", indexes = {
        @Index(name = "idx_audit_log_action", columnList = "action"),
        @Index(name = "idx_audit_log_entity", columnList = "entityType, entityId"),
        @Index(name = "idx_audit_log_actor", columnList = "actorId"),
        @Index(name = "idx_audit_log_timestamp", columnList = "timestamp")
})
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "action", nullable = false, length = 128)
    private String action;

    @Column(name = "entity_type", nullable = false, length = 128)
    private String entityType;

    @Column(name = "entity_id", nullable = false, length = 256)
    private String entityId;

    @Column(name = "actor_id", nullable = false, length = 256)
    private String actorId;

    @Column(name = "actor_role", nullable = false, length = 64)
    private String actorRole;

    @Column(name = "timestamp", nullable = false, columnDefinition = "TIMESTAMP WITH TIME ZONE")
    private OffsetDateTime timestamp;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "details", columnDefinition = "jsonb")
    private JsonNode details;

    @Column(name = "ip_address", length = 45)
    private String ipAddress;

    @Column(name = "org_id")
    private UUID orgId;

    public AuditLog() {
    }

    @PrePersist
    protected void onCreate() {
        if (timestamp == null) {
            timestamp = OffsetDateTime.now();
        }
    }

    // --- Getters and Setters ---

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = action;
    }

    public String getEntityType() {
        return entityType;
    }

    public void setEntityType(String entityType) {
        this.entityType = entityType;
    }

    public String getEntityId() {
        return entityId;
    }

    public void setEntityId(String entityId) {
        this.entityId = entityId;
    }

    public String getActorId() {
        return actorId;
    }

    public void setActorId(String actorId) {
        this.actorId = actorId;
    }

    public String getActorRole() {
        return actorRole;
    }

    public void setActorRole(String actorRole) {
        this.actorRole = actorRole;
    }

    public OffsetDateTime getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(OffsetDateTime timestamp) {
        this.timestamp = timestamp;
    }

    public JsonNode getDetails() {
        return details;
    }

    public void setDetails(JsonNode details) {
        this.details = details;
    }

    public String getIpAddress() {
        return ipAddress;
    }

    public void setIpAddress(String ipAddress) {
        this.ipAddress = ipAddress;
    }

    public UUID getOrgId() {
        return orgId;
    }

    public void setOrgId(UUID orgId) {
        this.orgId = orgId;
    }

    @Override
    public String toString() {
        return "AuditLog{" +
                "id=" + id +
                ", action='" + action + '\'' +
                ", entityType='" + entityType + '\'' +
                ", entityId='" + entityId + '\'' +
                ", actorId='" + actorId + '\'' +
                ", orgId=" + orgId +
                ", timestamp=" + timestamp +
                '}';
    }
}
