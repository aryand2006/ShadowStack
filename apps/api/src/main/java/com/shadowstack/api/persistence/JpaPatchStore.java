package com.shadowstack.api.persistence;

import com.shadowstack.api.dto.PatchDetailResponse;
import com.shadowstack.api.dto.PatchDetailResponse.PatchStatus;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Component
@Profile("!demo")
public class JpaPatchStore implements PatchStore {

    private final PatchRepository patchRepository;
    private final ProjectMapper mapper;

    public JpaPatchStore(PatchRepository patchRepository, ProjectMapper mapper) {
        this.patchRepository = patchRepository;
        this.mapper = mapper;
    }

    @Override
    @Transactional
    public void save(PatchDetailResponse patch) {
        PersistedPatch entity = patchRepository.findById(patch.patchId())
                .orElseGet(PersistedPatch::new);
        mapper.apply(entity, patch);
        patchRepository.save(entity);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<PatchDetailResponse> findById(UUID id) {
        return patchRepository.findById(id).map(mapper::toPatchDetailResponse);
    }

    @Override
    @Transactional(readOnly = true)
    public List<PatchDetailResponse> findByProject(UUID projectId) {
        return patchRepository.findByProjectId(projectId).stream()
                .map(mapper::toPatchDetailResponse)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<PatchDetailResponse> findByStatus(PatchStatus status) {
        return patchRepository.findByStatus(status.name()).stream()
                .map(mapper::toPatchDetailResponse)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<PatchDetailResponse> findAll() {
        return patchRepository.findAll().stream()
                .map(mapper::toPatchDetailResponse)
                .toList();
    }
}
