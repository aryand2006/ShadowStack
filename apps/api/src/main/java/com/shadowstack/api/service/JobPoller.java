package com.shadowstack.api.service;

import com.shadowstack.api.persistence.JobRepository;
import com.shadowstack.api.persistence.PersistedJob;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Optional {@code ss_jobs} poller for single-process deploys: claims PENDING VERIFY
 * rows with {@code FOR UPDATE SKIP LOCKED} and runs
 * {@link RefactorOrchestrationService#runVerification(UUID)}.
 *
 * <p>Disabled by default in {@code prod}/{@code docker} so the dedicated worker owns
 * dequeue ({@code shadowstack.jobs.poller-enabled=false}). Re-enable via
 * {@code SHADOWSTACK_JOBS_POLLER_ENABLED=true} for API-only deployments. Inactive
 * under the {@code demo} profile (in-memory path).</p>
 */
@Component
@Profile("!demo")
@ConditionalOnProperty(name = "shadowstack.jobs.poller-enabled", havingValue = "true", matchIfMissing = true)
public class JobPoller {

    private static final Logger log = LoggerFactory.getLogger(JobPoller.class);

    private static final String CLAIM_SQL = """
            UPDATE ss_jobs SET status = 'RUNNING', claimed_at = NOW(), claimed_by = ?, updated_at = NOW()
            WHERE id = (
              SELECT id FROM ss_jobs
              WHERE status = 'PENDING' AND job_type = 'VERIFY'
              ORDER BY created_at
              FOR UPDATE SKIP LOCKED
              LIMIT 1
            )
            RETURNING id, patch_id
            """;

    private final JobRepository jobRepository;
    private final RefactorOrchestrationService orchestrationService;
    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;
    private final String workerId;

    public JobPoller(
            JobRepository jobRepository,
            RefactorOrchestrationService orchestrationService,
            JdbcTemplate jdbcTemplate,
            TransactionTemplate transactionTemplate,
            @Value("${shadowstack.worker.id:api-job-poller}") String workerId) {
        this.jobRepository = jobRepository;
        this.orchestrationService = orchestrationService;
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = transactionTemplate;
        this.workerId = workerId;
    }

    @Scheduled(fixedDelayString = "${shadowstack.worker.poll-interval-ms:5000}")
    public void poll() {
        Optional<ClaimedVerifyJob> claimed = claimNext();
        if (claimed.isEmpty()) {
            return;
        }
        ClaimedVerifyJob job = claimed.get();
        try {
            if (job.patchId() == null) {
                throw new IllegalStateException("VERIFY job missing patchId");
            }
            orchestrationService.runVerification(job.patchId());
            finish(job.id(), "SUCCEEDED", null);
            log.info("VERIFY job {} succeeded for patch {}", job.id(), job.patchId());
        } catch (Exception e) {
            String message = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            finish(job.id(), "FAILED", message);
            log.warn("VERIFY job {} failed: {}", job.id(), message);
        }
    }

    private Optional<ClaimedVerifyJob> claimNext() {
        return transactionTemplate.execute(status -> {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(CLAIM_SQL, workerId);
            if (rows.isEmpty()) {
                return Optional.empty();
            }
            Map<String, Object> row = rows.getFirst();
            return Optional.of(new ClaimedVerifyJob(toUuid(row.get("id")), toUuid(row.get("patch_id"))));
        });
    }

    private void finish(UUID jobId, String status, String errorMessage) {
        transactionTemplate.executeWithoutResult(tx -> {
            PersistedJob job = jobRepository.findById(jobId)
                    .orElseThrow(() -> new IllegalStateException("Claimed job missing: " + jobId));
            job.setStatus(status);
            job.setErrorMessage(errorMessage);
            job.setUpdatedAt(Instant.now());
            jobRepository.save(job);
        });
    }

    private static UUID toUuid(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof UUID uuid) {
            return uuid;
        }
        return UUID.fromString(value.toString());
    }

    private record ClaimedVerifyJob(UUID id, UUID patchId) {}
}
