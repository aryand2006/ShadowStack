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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * Project lifecycle with local-path resolution for company demos.
 */
@Service
public class ProjectService {

    private static final Logger log = LoggerFactory.getLogger(ProjectService.class);

    private final ShadowStackConfig config;
    private final Map<UUID, ProjectResponse> projectStore = new ConcurrentHashMap<>();
    private final Map<UUID, Path> projectRoots = new ConcurrentHashMap<>();

    public ProjectService(ShadowStackConfig config) {
        this.config = config;
    }

    public ProjectResponse createProject(ProjectCreateRequest request) {
        Path root = resolveRoot(request.repositoryUrl());
        if (!Files.isDirectory(root)) {
            throw new IllegalArgumentException("Project root does not exist or is not a directory: " + root);
        }

        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        ProjectResponse project = new ProjectResponse(
                id,
                request.name(),
                request.description(),
                root.toAbsolutePath().normalize().toString(),
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
        projectRoots.put(id, root.toAbsolutePath().normalize());
        log.info("Created project id={} name={} root={}", id, request.name(), root);
        return project;
    }

    public List<ProjectResponse> listProjects() {
        return List.copyOf(projectStore.values());
    }

    public Optional<ProjectResponse> getProject(UUID id) {
        return Optional.ofNullable(projectStore.get(id));
    }

    public Path requireProjectRoot(UUID projectId) {
        Path root = projectRoots.get(projectId);
        if (root == null) {
            throw new ProjectNotFoundException(projectId);
        }
        return root;
    }

    public ProjectResponse triggerBaseline(UUID projectId) {
        ProjectResponse existing = projectStore.get(projectId);
        if (existing == null) {
            throw new ProjectNotFoundException(projectId);
        }
        Path root = requireProjectRoot(projectId);
        int javaFiles = countJavaFiles(root);
        BaselineSummary baseline = new BaselineSummary(javaFiles, 0, 0, 0, Instant.now());
        ProjectResponse updated = new ProjectResponse(
                existing.id(), existing.name(), existing.description(), existing.repositoryUrl(),
                existing.branch(), existing.sourceLanguage(), existing.targetLanguageVersion(),
                ProjectStatus.BASELINE_CAPTURED, existing.createdAt(), Instant.now(),
                baseline, existing.analysisSummary()
        );
        projectStore.put(projectId, updated);
        return updated;
    }

    public Optional<BaselineSummary> getBaseline(UUID projectId) {
        return getProject(projectId).map(ProjectResponse::baseline);
    }

    public ProjectResponse updateAnalysisSummary(UUID projectId, AnalysisSummary summary) {
        ProjectResponse existing = projectStore.get(projectId);
        if (existing == null) {
            throw new ProjectNotFoundException(projectId);
        }
        ProjectResponse updated = new ProjectResponse(
                existing.id(), existing.name(), existing.description(), existing.repositoryUrl(),
                existing.branch(), existing.sourceLanguage(), existing.targetLanguageVersion(),
                ProjectStatus.READY, existing.createdAt(), Instant.now(),
                existing.baseline(), summary
        );
        projectStore.put(projectId, updated);
        return updated;
    }

    private static Path resolveRoot(String repositoryUrl) {
        String raw = repositoryUrl.trim();
        if (raw.startsWith("file://")) {
            raw = raw.substring("file://".length());
        }
        Path path = Path.of(raw);
        if (!path.isAbsolute()) {
            path = Path.of(System.getProperty("user.dir")).resolve(path);
        }
        return path.toAbsolutePath().normalize();
    }

    private static int countJavaFiles(Path root) {
        try (Stream<Path> stream = Files.walk(root)) {
            return (int) stream
                    .filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> {
                        String s = p.toString();
                        return !s.contains("/target/") && !s.contains("/build/");
                    })
                    .count();
        } catch (IOException e) {
            return 0;
        }
    }

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
