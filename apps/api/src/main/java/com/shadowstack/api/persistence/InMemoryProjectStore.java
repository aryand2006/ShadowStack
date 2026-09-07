package com.shadowstack.api.persistence;

import com.shadowstack.api.dto.ProjectResponse;
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

    @Override
    public ProjectResponse save(ProjectResponse p, Path root) {
        projects.put(p.id(), p);
        roots.put(p.id(), root.toAbsolutePath().normalize());
        return p;
    }

    @Override
    public List<ProjectResponse> findAll() {
        return List.copyOf(projects.values());
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
    }
}
