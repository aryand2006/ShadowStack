package com.shadowstack.api.persistence;

import com.shadowstack.api.dto.ProjectResponse;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProjectStore {

    ProjectResponse save(ProjectResponse p, Path root);

    List<ProjectResponse> findAll();

    Optional<ProjectResponse> findById(UUID id);

    Optional<Path> findRoot(UUID id);

    void update(ProjectResponse p);
}
