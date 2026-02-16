package com.shadowstack.api.controllers;

import com.shadowstack.api.dto.ProjectCreateRequest;
import com.shadowstack.api.dto.ProjectResponse;
import com.shadowstack.api.dto.ProjectResponse.BaselineSummary;
import com.shadowstack.api.service.ProjectService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * REST controller for project lifecycle management.
 * Handles project creation (repository ingestion), listing,
 * detail retrieval, and baseline capture operations.
 */
@RestController
@RequestMapping("/api/v1/projects")
@Tag(name = "Projects", description = "Project lifecycle management — ingest, baseline, query")
public class ProjectController {

    private final ProjectService projectService;

    public ProjectController(ProjectService projectService) {
        this.projectService = projectService;
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'REVIEWER')")
    @Operation(summary = "Create a new project", description = "Ingest a Git repository as a new ShadowStack project")
    public ResponseEntity<ProjectResponse> createProject(@Valid @RequestBody ProjectCreateRequest request) {
        ProjectResponse project = projectService.createProject(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(project);
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'REVIEWER', 'ANALYST', 'VIEWER')")
    @Operation(summary = "List all projects")
    public ResponseEntity<List<ProjectResponse>> listProjects() {
        return ResponseEntity.ok(projectService.listProjects());
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'REVIEWER', 'ANALYST', 'VIEWER')")
    @Operation(summary = "Get project details", description = "Retrieve full project metadata including baseline and analysis summaries")
    public ResponseEntity<ProjectResponse> getProject(@PathVariable UUID id) {
        return projectService.getProject(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/{id}/baseline")
    @PreAuthorize("hasAnyRole('ADMIN', 'REVIEWER')")
    @Operation(summary = "Trigger baseline capture", description = "Run test suite and capture behavioral baseline snapshot")
    public ResponseEntity<ProjectResponse> triggerBaseline(@PathVariable UUID id) {
        ProjectResponse project = projectService.triggerBaseline(id);
        return ResponseEntity.accepted().body(project);
    }

    @GetMapping("/{id}/baseline")
    @PreAuthorize("hasAnyRole('ADMIN', 'REVIEWER', 'ANALYST', 'VIEWER')")
    @Operation(summary = "Get baseline snapshot", description = "Retrieve the captured behavioral baseline for the project")
    public ResponseEntity<BaselineSummary> getBaseline(@PathVariable UUID id) {
        return projectService.getBaseline(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }
}
