package com.shadowstack.api.persistence;

import com.shadowstack.api.dto.PatchDetailResponse;
import com.shadowstack.api.dto.PatchDetailResponse.PatchStatus;
import com.shadowstack.api.tenant.TenantContext;
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
        if (entity.getOrgId() == null) {
            entity.setOrgId(TenantContext.requireOrgIdOrDefault());
        }
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
        UUID orgId = TenantContext.getOrgId();
        List<PersistedPatch> rows = orgId != null
                ? patchRepository.findByStatusAndOrgId(status.name(), orgId)
                : patchRepository.findByStatus(status.name());
        return rows.stream()
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
