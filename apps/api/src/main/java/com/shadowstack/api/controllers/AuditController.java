package com.shadowstack.api.controllers;

import com.shadowstack.corpus.AuditLog;
import com.shadowstack.corpus.AuditLogRepository;
import com.shadowstack.corpus.AuditService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * REST controller for querying audit logs.
 * All audit log queries require ADMIN role.
 * <p>
 * Durable persistence is available when {@link AuditService} / {@link AuditLogRepository}
 * beans are present ({@code !demo}). Demo returns empty results without failing.
 */
@RestController
@RequestMapping("/api/v1/audit")
@Tag(name = "Audit", description = "Audit log queries — ADMIN only")
public class AuditController {

    private final ObjectProvider<AuditService> auditService;
    private final ObjectProvider<AuditLogRepository> auditLogRepository;

    public AuditController(
            ObjectProvider<AuditService> auditService,
            ObjectProvider<AuditLogRepository> auditLogRepository) {
        this.auditService = auditService;
        this.auditLogRepository = auditLogRepository;
    }

    @GetMapping("/logs")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(
            summary = "Query audit logs",
            description = "Search audit logs with optional filters for date range, actor, and action"
    )
    public ResponseEntity<List<AuditLogEntry>> queryAuditLogs(
            @Parameter(description = "Start of date range (ISO 8601)")
            @RequestParam(required = false) Instant from,

            @Parameter(description = "End of date range (ISO 8601)")
            @RequestParam(required = false) Instant to,

            @Parameter(description = "Filter by actor (username)")
            @RequestParam(required = false) String actor,

            @Parameter(description = "Filter by action (e.g., POST, GET, DELETE)")
            @RequestParam(required = false) String action,

            @Parameter(description = "Filter by resource path prefix")
            @RequestParam(required = false) String path,

            @Parameter(description = "Page number (0-based)")
            @RequestParam(defaultValue = "0") int page,

            @Parameter(description = "Page size")
            @RequestParam(defaultValue = "50") int size) {

        List<AuditLog> rows = loadLogs(from, to);
        List<AuditLogEntry> filtered = rows.stream()
                .filter(e -> actor == null || actor.isBlank() || actor.equalsIgnoreCase(e.getActorId()))
                .filter(e -> action == null || action.isBlank()
                        || (e.getAction() != null && e.getAction().toUpperCase().contains(action.toUpperCase())))
                .filter(e -> path == null || path.isBlank()
                        || (e.getEntityId() != null && e.getEntityId().startsWith(path)))
                .sorted(Comparator.comparing(AuditLog::getTimestamp).reversed())
                .skip((long) Math.max(0, page) * Math.max(1, size))
                .limit(Math.max(1, size))
                .map(this::toEntry)
                .toList();

        return ResponseEntity.ok(filtered);
    }

    @GetMapping(value = "/export", produces = "text/csv")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Export audit logs as CSV", description = "Returns text/csv of audit_log rows")
    public ResponseEntity<String> exportCsv(
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to) {

        List<AuditLog> rows = loadLogs(from, to).stream()
                .sorted(Comparator.comparing(AuditLog::getTimestamp).reversed())
                .toList();

        StringBuilder csv = new StringBuilder();
        csv.append("id,action,entity_type,entity_id,actor_id,actor_role,org_id,timestamp,ip_address\n");
        for (AuditLog e : rows) {
            csv.append(csvCell(e.getId()))
                    .append(',')
                    .append(csvCell(e.getAction()))
                    .append(',')
                    .append(csvCell(e.getEntityType()))
                    .append(',')
                    .append(csvCell(e.getEntityId()))
                    .append(',')
                    .append(csvCell(e.getActorId()))
                    .append(',')
                    .append(csvCell(e.getActorRole()))
                    .append(',')
                    .append(csvCell(e.getOrgId()))
                    .append(',')
                    .append(csvCell(e.getTimestamp()))
                    .append(',')
                    .append(csvCell(e.getIpAddress()))
                    .append('\n');
        }

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"audit-log.csv\"")
                .contentType(new MediaType("text", "csv"))
                .body(csv.toString());
    }

    private List<AuditLog> loadLogs(Instant from, Instant to) {
        AuditService service = auditService.getIfAvailable();
        AuditLogRepository repo = auditLogRepository.getIfAvailable();
        if (service == null && repo == null) {
            return List.of();
        }

        if (from != null && to != null && service != null) {
            return service.getAuditLogsBetween(
                    OffsetDateTime.ofInstant(from, ZoneOffset.UTC),
                    OffsetDateTime.ofInstant(to, ZoneOffset.UTC));
        }
        if (repo != null) {
            return repo.findAll().stream()
                    .filter(e -> e.getDeletedAt() == null)
                    .toList();
        }
        return service.findRecent(10_000);
    }

    private AuditLogEntry toEntry(AuditLog e) {
        String details = e.getDetails() != null ? e.getDetails().toString() : null;
        Instant ts = e.getTimestamp() != null ? e.getTimestamp().toInstant() : null;
        return new AuditLogEntry(
                e.getId(),
                e.getAction(),
                e.getEntityType(),
                e.getEntityId(),
                e.getActorId(),
                e.getActorRole(),
                e.getOrgId(),
                details,
                ts
        );
    }

    private static String csvCell(Object value) {
        if (value == null) {
            return "";
        }
        String s = value.toString();
        if (s.contains(",") || s.contains("\"") || s.contains("\n")) {
            return "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }

    /**
     * Audit log entry mapped from the durable {@link AuditLog} entity.
     */
    public record AuditLogEntry(
            UUID id,
            String action,
            String entityType,
            String entityId,
            String actor,
            String actorRole,
            UUID orgId,
            String details,
            Instant timestamp
    ) {}
}
