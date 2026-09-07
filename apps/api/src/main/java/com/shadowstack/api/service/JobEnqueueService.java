package com.shadowstack.api.service;

import com.shadowstack.api.persistence.JobRepository;
import com.shadowstack.api.persistence.PersistedJob;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * Enqueues durable {@code ss_jobs} rows for asynchronous worker/API pollers.
 * Active only when JPA persistence is available ({@code !demo}).
 */
@Service
@Profile("!demo")
public class JobEnqueueService {

    public static final String TYPE_VERIFY = "VERIFY";
    public static final String STATUS_PENDING = "PENDING";

    private static final Logger log = LoggerFactory.getLogger(JobEnqueueService.class);

    private final JobRepository jobRepository;

    public JobEnqueueService(JobRepository jobRepository) {
        this.jobRepository = jobRepository;
    }

    @Transactional
    public UUID enqueueVerify(UUID projectId, UUID patchId) {
        Instant now = Instant.now();
        PersistedJob job = new PersistedJob();
        job.setId(UUID.randomUUID());
        job.setProjectId(projectId);
        job.setPatchId(patchId);
        job.setJobType(TYPE_VERIFY);
        job.setStatus(STATUS_PENDING);
        job.setPayloadJson("{\"patchId\":\"" + patchId + "\"}");
        job.setCreatedAt(now);
        job.setUpdatedAt(now);
        jobRepository.save(job);
        log.info("Enqueued VERIFY job {} for patch {}", job.getId(), patchId);
        return job.getId();
    }
}
