package com.shadowstack.api.persistence;

import com.shadowstack.api.dto.ProjectResponse;
import com.shadowstack.api.tenant.TenantContext;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Component
@Profile("demo")
public class InMemoryProjectStore implements ProjectStore {

    private final Map<UUID, ProjectResponse> projects = new ConcurrentHashMap<>();
    private final Map<UUID, Path> roots = new ConcurrentHashMap<>();
    private final Map<UUID, UUID> orgIds = new ConcurrentHashMap<>();

    @Override
    public ProjectResponse save(ProjectResponse p, Path root) {
        projects.put(p.id(), p);
        roots.put(p.id(), root.toAbsolutePath().normalize());
        orgIds.put(p.id(), TenantContext.requireOrgIdOrDefault());
        return p;
    }

    @Override
    public List<ProjectResponse> findAll() {
        UUID orgId = TenantContext.getOrgId();
        if (orgId == null) {
            return List.copyOf(projects.values());
        }
        return projects.values().stream()
                .filter(p -> orgId.equals(orgIds.get(p.id())))
                .toList();
    }

    @Override
    public Optional<ProjectResponse> findById(UUID id) {
        return Optional.ofNullable(projects.get(id));
    }

    @Override
    public Optional<Path> findRoot(UUID id) {
        return Optional.ofNullable(roots.get(id));
    }

    @Override
    public void update(ProjectResponse p) {
        if (!projects.containsKey(p.id())) {
            throw new IllegalArgumentException("Unknown project " + p.id());
        }
        projects.put(p.id(), p);
        orgIds.putIfAbsent(p.id(), TenantContext.requireOrgIdOrDefault());
    }
}
