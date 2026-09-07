package com.shadowstack.api.persistence;

import com.shadowstack.api.dto.PatchDetailResponse;
import com.shadowstack.api.dto.PatchDetailResponse.PatchStatus;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PatchStore {

    void save(PatchDetailResponse patch);

    Optional<PatchDetailResponse> findById(UUID id);

    List<PatchDetailResponse> findByProject(UUID projectId);

    List<PatchDetailResponse> findByStatus(PatchStatus status);
}
