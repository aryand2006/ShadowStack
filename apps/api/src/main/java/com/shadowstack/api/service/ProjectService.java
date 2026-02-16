package com.shadowstack.api.service;

import com.shadowstack.api.config.ShadowStackConfig;
import com.shadowstack.api.dto.ProjectCreateRequest;
import com.shadowstack.api.dto.ProjectResponse;
import com.shadowstack.api.dto.ProjectResponse.AnalysisSummary;
import com.shadowstack.api.dto.ProjectResponse.BaselineSummary;
import com.shadowstack.api.dto.ProjectResponse.ProjectStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Orchestrates the project lifecycle: creation, ingestion, baseline capture,
 * and project metadata management.
 * <p>
 * In production, this delegates to JPA repositories and the core-analysis
 * module for actual repository ingestion and baseline capture.
 */
@Service
public class ProjectService {

    private static final Logger log = LoggerFactory.getLogger(ProjectService.class);

    private final ShadowStackConfig config;
    private final Map<UUID, ProjectResponse> projectStore = new ConcurrentHashMap<>();

    public ProjectService(ShadowStackConfig config) {
        this.config = config;
    }

    /**
     * Create a new project and begin repository ingestion.
     */
    public ProjectResponse createProject(ProjectCreateRequest request) {
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();

        ProjectResponse project = new ProjectResponse(
                id,
                request.name(),
                request.description(),
                request.repositoryUrl(),
                request.branch(),
                request.sourceLanguage(),
                request.targetLanguageVersion(),
                ProjectStatus.CREATED,
                now,
                now,
                null,
                null
        );

        projectStore.put(id, project);
        log.info("Created project: id={}, name={}, repo={}", id, request.name(), request.repositoryUrl());

        // In production: trigger async ingestion via core-analysis module
        return project;
    }

    /**
     * Retrieve all projects.
     */
    public List<ProjectResponse> listProjects() {
        return List.copyOf(projectStore.values());
    }

    /**
     * Retrieve a project by ID.
     */
    public Optional<ProjectResponse> getProject(UUID id) {
        return Optional.ofNullable(projectStore.get(id));
    }

    /**
     * Trigger baseline capture for a project.
     * Delegates to the core-analysis BaselineCapture module.
     */
    public ProjectResponse triggerBaseline(UUID projectId) {
        ProjectResponse existing = projectStore.get(projectId);
        if (existing == null) {
            throw new ProjectNotFoundException(projectId);
        }

        log.info("Triggering baseline capture for project: {}", projectId);

        // In production: invoke BaselineCapture from core-analysis
        BaselineSummary baseline = new BaselineSummary(0, 0, 0, 0, Instant.now());

        ProjectResponse updated = new ProjectResponse(
                existing.id(),
                existing.name(),
                existing.description(),
                existing.repositoryUrl(),
                existing.branch(),
                existing.sourceLanguage(),
                existing.targetLanguageVersion(),
                ProjectStatus.BASELINE_CAPTURED,
                existing.createdAt(),
                Instant.now(),
                baseline,
                existing.analysisSummary()
        );

        projectStore.put(projectId, updated);
        return updated;
    }

    /**
     * Get the baseline snapshot for a project.
     */
    public Optional<BaselineSummary> getBaseline(UUID projectId) {
        return getProject(projectId).map(ProjectResponse::baseline);
    }

    /**
     * Update project status and analysis summary after analysis completes.
     */
    public ProjectResponse updateAnalysisSummary(UUID projectId, AnalysisSummary summary) {
        ProjectResponse existing = projectStore.get(projectId);
        if (existing == null) {
            throw new ProjectNotFoundException(projectId);
        }

        ProjectResponse updated = new ProjectResponse(
                existing.id(),
                existing.name(),
                existing.description(),
                existing.repositoryUrl(),
                existing.branch(),
                existing.sourceLanguage(),
                existing.targetLanguageVersion(),
                ProjectStatus.READY,
                existing.createdAt(),
                Instant.now(),
                existing.baseline(),
                summary
        );

        projectStore.put(projectId, updated);
        return updated;
    }

    /**
     * Exception thrown when a project is not found.
     */
    public static class ProjectNotFoundException extends RuntimeException {
        private final UUID projectId;

        public ProjectNotFoundException(UUID projectId) {
            super("Project not found: " + projectId);
            this.projectId = projectId;
        }

        public UUID getProjectId() {
            return projectId;
        }
    }
}
