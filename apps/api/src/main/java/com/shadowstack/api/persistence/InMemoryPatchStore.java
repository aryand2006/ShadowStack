package com.shadowstack.api.persistence;

import com.shadowstack.api.dto.PatchDetailResponse;
import com.shadowstack.api.dto.PatchDetailResponse.PatchStatus;
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

    @Override
    public void save(PatchDetailResponse patch) {
        patches.put(patch.patchId(), patch);
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
        return patches.values().stream()
                .filter(p -> p.status() == status)
                .toList();
    }
}
