package com.shadowstack.api.persistence;

import com.shadowstack.api.dto.ProjectResponse;
import com.shadowstack.api.dto.ProjectResponse.AnalysisSummary;
import com.shadowstack.api.dto.ProjectResponse.BaselineSummary;
import com.shadowstack.api.tenant.TenantContext;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Component
@Profile("!demo")
public class JpaProjectStore implements ProjectStore {

    private final ProjectRepository projectRepository;
    private final ProjectMapper mapper;

    /** Ephemeral overlays — not yet columns on ss_projects. */
    private final Map<UUID, BaselineSummary> baselines = new ConcurrentHashMap<>();
    private final Map<UUID, AnalysisSummary> analyses = new ConcurrentHashMap<>();

    public JpaProjectStore(ProjectRepository projectRepository, ProjectMapper mapper) {
        this.projectRepository = projectRepository;
        this.mapper = mapper;
    }

    @Override
    @Transactional
    public ProjectResponse save(ProjectResponse p, Path root) {
        stashOverlays(p);
        PersistedProject entity = mapper.toEntity(p, root);
        if (entity.getOrgId() == null) {
            entity.setOrgId(TenantContext.requireOrgIdOrDefault());
        }
        projectRepository.save(entity);
        return withOverlays(mapper.toProjectResponse(entity));
    }

    @Override
    @Transactional(readOnly = true)
    public List<ProjectResponse> findAll() {
        UUID orgId = TenantContext.getOrgId();
        List<PersistedProject> rows = orgId != null
                ? projectRepository.findByOrgId(orgId)
                : projectRepository.findAll();
        return rows.stream()
                .map(mapper::toProjectResponse)
                .map(this::withOverlays)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ProjectResponse> findById(UUID id) {
        return projectRepository.findById(id)
                .map(mapper::toProjectResponse)
                .map(this::withOverlays);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Path> findRoot(UUID id) {
        return projectRepository.findById(id)
                .map(PersistedProject::getRootPath)
                .map(Path::of);
    }

    @Override
    @Transactional
    public void update(ProjectResponse p) {
        stashOverlays(p);
        PersistedProject entity = projectRepository.findById(p.id())
                .orElseThrow(() -> new IllegalArgumentException("Unknown project " + p.id()));
        mapper.apply(entity, p);
        projectRepository.save(entity);
    }

    private void stashOverlays(ProjectResponse p) {
        if (p.baseline() != null) {
            baselines.put(p.id(), p.baseline());
        }
        if (p.analysisSummary() != null) {
            analyses.put(p.id(), p.analysisSummary());
        }
    }

    private ProjectResponse withOverlays(ProjectResponse base) {
        BaselineSummary baseline = baselines.getOrDefault(base.id(), base.baseline());
        AnalysisSummary analysis = analyses.getOrDefault(base.id(), base.analysisSummary());
        if (baseline == base.baseline() && analysis == base.analysisSummary()) {
            return base;
        }
        return new ProjectResponse(
                base.id(), base.name(), base.description(), base.repositoryUrl(),
                base.branch(), base.sourceLanguage(), base.targetLanguageVersion(),
                base.status(), base.createdAt(), base.updatedAt(),
                baseline, analysis
        );
    }
}
