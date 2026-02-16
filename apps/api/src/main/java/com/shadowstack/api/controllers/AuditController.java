package com.shadowstack.api.controllers;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

/**
 * REST controller for querying audit logs.
 * All audit log queries require ADMIN role.
 * <p>
 * In production, queries the audit_log table populated by the AuditInterceptor.
 */
@RestController
@RequestMapping("/api/v1/audit")
@Tag(name = "Audit", description = "Audit log queries — ADMIN only")
public class AuditController {

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

        // In production: query audit_log table with JPA Specifications
        // Filters: date range, actor, action, path prefix, pagination
        return ResponseEntity.ok(List.of());
    }

    /**
     * Audit log entry returned by query endpoints.
     * In production, this maps to the audit_log database entity.
     */
    public record AuditLogEntry(
            Long id,
            String action,
            String method,
            String path,
            String actor,
            int statusCode,
            long durationMs,
            String details,
            Instant timestamp
    ) {}
}
