package com.shadowstack.api.persistence;

import com.shadowstack.api.dto.PatchDetailResponse;
import com.shadowstack.api.dto.PatchDetailResponse.PatchStatus;
import com.shadowstack.api.tenant.TenantContext;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Component
@Profile("demo")
public class InMemoryPatchStore implements PatchStore {

    private final Map<UUID, PatchDetailResponse> patches = new ConcurrentHashMap<>();
    private final Map<UUID, UUID> orgIds = new ConcurrentHashMap<>();

    @Override
    public void save(PatchDetailResponse patch) {
        patches.put(patch.patchId(), patch);
        orgIds.put(patch.patchId(), TenantContext.requireOrgIdOrDefault());
    }

    @Override
    public Optional<PatchDetailResponse> findById(UUID id) {
        return Optional.ofNullable(patches.get(id));
    }

    @Override
    public List<PatchDetailResponse> findByProject(UUID projectId) {
        return patches.values().stream()
                .filter(p -> projectId.equals(p.projectId()))
                .toList();
    }

    @Override
    public List<PatchDetailResponse> findByStatus(PatchStatus status) {
        UUID orgId = TenantContext.getOrgId();
        return patches.values().stream()
                .filter(p -> p.status() == status)
                .filter(p -> orgId == null || orgId.equals(orgIds.get(p.patchId())))
                .toList();
    }

    @Override
    public List<PatchDetailResponse> findAll() {
        return List.copyOf(patches.values());
    }
}
