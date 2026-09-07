package com.shadowstack.api.controllers;

import com.shadowstack.api.dto.CreateOrgUserRequest;
import com.shadowstack.api.dto.CreateOrganizationRequest;
import com.shadowstack.api.service.OrganizationService;
import com.shadowstack.api.tenant.TenantContext;
import com.shadowstack.api.tenant.TenantFilter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Organization / tenant administration.
 * <p>
 * Demo profile: returns the default org catalog via {@link TenantContext} without JPA.
 * Non-demo: delegates to {@link OrganizationService} ({@code ss_organizations} / {@code ss_users}).
 */
@RestController
@RequestMapping("/api/v1/orgs")
@Tag(name = "Organizations", description = "Tenant organization administration")
public class OrganizationController {

    private final ObjectProvider<OrganizationService> organizationService;

    public OrganizationController(ObjectProvider<OrganizationService> organizationService) {
        this.organizationService = organizationService;
    }

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
    @Operation(summary = "List organizations")
    public ResponseEntity<List<Map<String, Object>>> list() {
        OrganizationService service = organizationService.getIfAvailable();
        if (service == null) {
            return ResponseEntity.ok(List.of(defaultOrgMap()));
        }
        return ResponseEntity.ok(service.listOrganizations());
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Create organization")
    public ResponseEntity<Map<String, Object>> create(@Valid @RequestBody CreateOrganizationRequest request) {
        OrganizationService service = requireService();
        Map<String, Object> created = service.createOrganization(request.name());
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Get organization by id")
    public ResponseEntity<Map<String, Object>> get(@PathVariable UUID id) {
        OrganizationService service = organizationService.getIfAvailable();
        if (service == null) {
            if (TenantContext.DEFAULT_ORG_ID.equals(id)) {
                return ResponseEntity.ok(defaultOrgMap());
            }
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Organization not found: " + id);
        }
        return ResponseEntity.ok(service.getOrganization(id));
    }

    @GetMapping("/{id}/users")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "List users in an organization")
    public ResponseEntity<List<Map<String, Object>>> listUsers(@PathVariable UUID id) {
        OrganizationService service = requireService();
        return ResponseEntity.ok(service.listUsers(id));
    }

    @PostMapping("/{id}/users")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Create a user in an organization (password stored as BCrypt hash)")
    public ResponseEntity<Map<String, Object>> createUser(
            @PathVariable UUID id,
            @Valid @RequestBody CreateOrgUserRequest request) {
        OrganizationService service = requireService();
        Map<String, Object> created = service.createUser(
                id, request.username(), request.password(), request.roles());
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    private OrganizationService requireService() {
        OrganizationService service = organizationService.getIfAvailable();
        if (service == null) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_IMPLEMENTED,
                    "Organization administration requires a non-demo profile with JPA");
        }
        return service;
    }

    private static Map<String, Object> defaultOrgMap() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", TenantContext.DEFAULT_ORG_ID);
        body.put("name", "default");
        body.put("status", "ACTIVE");
        return body;
    }
}
