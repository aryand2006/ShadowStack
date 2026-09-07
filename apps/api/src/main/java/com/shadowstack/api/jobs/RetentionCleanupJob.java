package com.shadowstack.api.jobs;

import com.shadowstack.api.config.ShadowStackConfig;
import com.shadowstack.api.persistence.PatchRepository;
import com.shadowstack.corpus.AuditLogRepository;
import com.shadowstack.corpus.AuditService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Daily retention cleanup for audit logs and verification evidence.
 * <p>
 * Reads cutoffs from {@code shadowstack.retention.*}. Soft-deletes expired
 * audit rows ({@code deleted_at}) so append-only {@code REVOKE DELETE} on the
 * app role remains valid. Archives old patch verification JSON by nulling the
 * payload. Each run writes an audit entry describing what was cleaned.
 * </p>
 * <p>Disabled under the {@code demo} profile (no durable JPA).</p>
 */
@Component
@Profile("!demo")
public class RetentionCleanupJob {

    private static final Logger log = LoggerFactory.getLogger(RetentionCleanupJob.class);

    private final ShadowStackConfig config;
    private final AuditLogRepository auditLogRepository;
    private final PatchRepository patchRepository;
    private final AuditService auditService;

    public RetentionCleanupJob(
            ShadowStackConfig config,
            AuditLogRepository auditLogRepository,
            PatchRepository patchRepository,
            AuditService auditService) {
        this.config = config;
        this.auditLogRepository = auditLogRepository;
        this.patchRepository = patchRepository;
        this.auditService = auditService;
    }

    /** Run once per day at 02:15 UTC. */
    @Scheduled(cron = "0 15 2 * * *", zone = "UTC")
    @Transactional
    public void runDailyCleanup() {
        ShadowStackConfig.RetentionConfig retention = config.retention();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        Instant nowInstant = now.toInstant();

        OffsetDateTime auditCutoff = now.minusDays(Math.max(1, retention.auditLogDays()));
        Instant evidenceCutoff = nowInstant.minusSeconds(
                Math.max(1L, retention.verificationEvidenceDays()) * 24L * 3600L);

        int softDeleted = auditLogRepository.softDeleteOlderThan(auditCutoff, now);
        int evidenceArchived = patchRepository.archiveVerificationEvidenceOlderThan(
                evidenceCutoff, nowInstant);

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("mode", "soft-delete");
        details.put("auditLogDays", retention.auditLogDays());
        details.put("verificationEvidenceDays", retention.verificationEvidenceDays());
        details.put("auditCutoff", auditCutoff.toString());
        details.put("evidenceCutoff", evidenceCutoff.toString());
        details.put("auditSoftDeleted", softDeleted);
        details.put("verificationEvidenceArchived", evidenceArchived);

        auditService.logAction(
                "RETENTION_CLEANUP",
                "SYSTEM",
                "retention-cleanup",
                "system",
                "ADMIN",
                details);

        log.info(
                "Retention cleanup complete: softDeleted={}, evidenceArchived={}",
                softDeleted, evidenceArchived);
    }
}
