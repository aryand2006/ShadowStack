package com.shadowstack.api.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shadowstack.api.persistence.JobRepository;
import com.shadowstack.api.persistence.PersistedJob;
import com.shadowstack.api.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Enqueues durable {@code ss_jobs} rows for asynchronous worker/API pollers.
 * Active only when JPA persistence is available ({@code !demo}).
 *
 * <p>VERIFY payloads include sources and patch metadata so the worker can run the
 * 7-layer pipeline and update {@code ss_patches} without calling back into the API.</p>
 */
@Service
@Profile("!demo")
public class JobEnqueueService {

    public static final String TYPE_VERIFY = "VERIFY";
    public static final String STATUS_PENDING = "PENDING";

    private static final Logger log = LoggerFactory.getLogger(JobEnqueueService.class);

    private final JobRepository jobRepository;
    private final ObjectMapper objectMapper;

    public JobEnqueueService(JobRepository jobRepository, ObjectMapper objectMapper) {
        this.jobRepository = jobRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public UUID enqueueVerify(UUID projectId, UUID patchId, Map<String, Object> payload) {
        Instant now = Instant.now();
        Map<String, Object> body = payload != null ? new LinkedHashMap<>(payload) : new LinkedHashMap<>();
        body.putIfAbsent("patchId", patchId != null ? patchId.toString() : null);
        body.putIfAbsent("projectId", projectId != null ? projectId.toString() : null);

        PersistedJob job = new PersistedJob();
        job.setId(UUID.randomUUID());
        job.setProjectId(projectId);
        job.setPatchId(patchId);
        job.setJobType(TYPE_VERIFY);
        job.setStatus(STATUS_PENDING);
        job.setPayloadJson(writePayload(body));
        job.setCreatedAt(now);
        job.setUpdatedAt(now);
        job.setOrgId(TenantContext.requireOrgIdOrDefault());
        jobRepository.save(job);
        log.info("Enqueued VERIFY job {} for patch {}", job.getId(), patchId);
        return job.getId();
    }

    @Transactional
    public UUID enqueueVerify(UUID projectId, UUID patchId) {
        Map<String, Object> minimal = new LinkedHashMap<>();
        minimal.put("patchId", patchId.toString());
        if (projectId != null) {
            minimal.put("projectId", projectId.toString());
        }
        return enqueueVerify(projectId, patchId, minimal);
    }

    private String writePayload(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize VERIFY job payload", e);
        }
    }
}
