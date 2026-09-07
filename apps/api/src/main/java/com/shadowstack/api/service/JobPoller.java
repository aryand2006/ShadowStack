package com.shadowstack.api.service;

import com.shadowstack.api.persistence.JobRepository;
import com.shadowstack.api.persistence.PersistedJob;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Minimal {@code ss_jobs} poller: claims PENDING VERIFY rows and runs
 * {@link RefactorOrchestrationService#runVerification(UUID)}.
 *
 * <p>Active when JPA is available ({@code !demo}). A dedicated worker process
 * can later own this loop; until then the API hosts the claim/execute path.</p>
 */
@Component
@Profile("!demo")
public class JobPoller {

    private static final Logger log = LoggerFactory.getLogger(JobPoller.class);

    private final JobRepository jobRepository;
    private final RefactorOrchestrationService orchestrationService;
    private final String workerId;

    public JobPoller(
            JobRepository jobRepository,
            RefactorOrchestrationService orchestrationService,
            @Value("${shadowstack.worker.id:api-job-poller}") String workerId) {
        this.jobRepository = jobRepository;
        this.orchestrationService = orchestrationService;
        this.workerId = workerId;
    }

    @Scheduled(fixedDelayString = "${shadowstack.worker.poll-interval-ms:5000}")
    @Transactional
    public void poll() {
        List<PersistedJob> pending = jobRepository.findByStatus(JobEnqueueService.STATUS_PENDING);
        if (pending.isEmpty()) {
            return;
        }
        for (PersistedJob job : pending) {
            if (!JobEnqueueService.TYPE_VERIFY.equals(job.getJobType())) {
                continue;
            }
            claimAndRun(job);
        }
    }

    private void claimAndRun(PersistedJob job) {
        Instant now = Instant.now();
        job.setStatus("RUNNING");
        job.setClaimedAt(now);
        job.setClaimedBy(workerId);
        job.setUpdatedAt(now);
        jobRepository.save(job);

        try {
            UUID patchId = job.getPatchId();
            if (patchId == null) {
                throw new IllegalStateException("VERIFY job missing patchId");
            }
            orchestrationService.runVerification(patchId);
            job.setStatus("SUCCEEDED");
            job.setErrorMessage(null);
            job.setUpdatedAt(Instant.now());
            jobRepository.save(job);
            log.info("VERIFY job {} succeeded for patch {}", job.getId(), patchId);
        } catch (Exception e) {
            job.setStatus("FAILED");
            job.setErrorMessage(e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
            job.setUpdatedAt(Instant.now());
            jobRepository.save(job);
            log.warn("VERIFY job {} failed: {}", job.getId(), e.getMessage());
        }
    }
}
