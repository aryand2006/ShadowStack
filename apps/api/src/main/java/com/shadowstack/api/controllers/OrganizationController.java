package com.shadowstack.api.controllers;

import com.shadowstack.api.tenant.TenantContext;
import com.shadowstack.api.tenant.TenantFilter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Minimal organization / tenant surface for enterprise multi-tenancy.
 * Isolation is enforced via {@link TenantContext} + {@code org_id} columns.
 */
@RestController
@RequestMapping("/api/v1/orgs")
@Tag(name = "Organizations", description = "Tenant organization context")
public class OrganizationController {

    @GetMapping("/current")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Current organization context for this request")
    public ResponseEntity<Map<String, Object>> current() {
        UUID orgId = TenantContext.requireOrgIdOrDefault();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", orgId);
        body.put("default", TenantContext.DEFAULT_ORG_ID.equals(orgId));
        body.put("header", TenantFilter.ORG_HEADER);
        body.put("note", "Pass " + TenantFilter.ORG_HEADER
                + " or JWT claim org_id to select a tenant; data is filtered by org_id");
        return ResponseEntity.ok(body);
    }

    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "List known organizations (default catalog)")
    public ResponseEntity<List<Map<String, Object>>> list() {
        // Catalog expands when orgs are provisioned in ss_organizations; default is always present.
        return ResponseEntity.ok(List.of(Map.of(
                "id", TenantContext.DEFAULT_ORG_ID,
                "name", "default",
                "status", "ACTIVE"
        )));
    }
}
