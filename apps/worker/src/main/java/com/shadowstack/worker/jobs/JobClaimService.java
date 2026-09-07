package com.shadowstack.worker.jobs;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Claims ss_jobs VERIFY rows with FOR UPDATE SKIP LOCKED and updates job/patch rows. */
@Service
public class JobClaimService {

    public static final String STATUS_SUCCEEDED = "SUCCEEDED";
    public static final String STATUS_FAILED = "FAILED";

    private static final Logger log = LoggerFactory.getLogger(JobClaimService.class);

    private static final String CLAIM_SQL = """
            UPDATE ss_jobs SET status = 'RUNNING', claimed_at = NOW(), claimed_by = ?, updated_at = NOW()
            WHERE id = (
              SELECT id FROM ss_jobs
              WHERE status = 'PENDING' AND job_type = 'VERIFY'
              ORDER BY created_at
              FOR UPDATE SKIP LOCKED
              LIMIT 1
            )
            RETURNING id, project_id, patch_id, job_type, status, payload_json,
                      error_message, created_at, updated_at, claimed_at, claimed_by
            """;

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;
    private final ObjectMapper objectMapper;

    public JobClaimService(JdbcTemplate jdbcTemplate, TransactionTemplate transactionTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = transactionTemplate;
        this.objectMapper = objectMapper;
    }

    public Optional<ClaimedJob> claimNextVerify(String workerId) {
        return transactionTemplate.execute(status -> {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(CLAIM_SQL, workerId);
            if (rows.isEmpty()) {
                return Optional.empty();
            }
            Map<String, Object> row = rows.getFirst();
            UUID id = toUuid(row.get("id"));
            UUID projectId = toUuid(row.get("project_id"));
            UUID patchId = toUuid(row.get("patch_id"));
            String payloadJson = row.get("payload_json") != null ? row.get("payload_json").toString() : null;
            Map<String, Object> payload = parsePayload(payloadJson);
            log.info("Claimed VERIFY job {} (patch={}, worker={})", id, patchId, workerId);
            return Optional.of(new ClaimedJob(id, projectId, patchId, payloadJson, payload));
        });
    }

    public void markSucceeded(UUID jobId) { updateJob(jobId, STATUS_SUCCEEDED, null); }
    public void markFailed(UUID jobId, String errorMessage) { updateJob(jobId, STATUS_FAILED, truncate(errorMessage, 4000)); }

    public void updatePatchVerification(UUID patchId, String status, String verificationJson) {
        transactionTemplate.executeWithoutResult(tx ->
                jdbcTemplate.update(
                        "UPDATE ss_patches SET status = ?, verification_json = ?, updated_at = NOW() WHERE id = ?",
                        status, verificationJson, patchId));
    }

    private void updateJob(UUID jobId, String status, String errorMessage) {
        transactionTemplate.executeWithoutResult(tx ->
                jdbcTemplate.update(
                        "UPDATE ss_jobs SET status = ?, error_message = ?, updated_at = NOW() WHERE id = ?",
                        status, errorMessage, jobId));
    }

    private Map<String, Object> parsePayload(String payloadJson) {
        if (payloadJson == null || payloadJson.isBlank()) return Map.of();
        try {
            return objectMapper.readValue(payloadJson, new TypeReference<LinkedHashMap<String, Object>>() {});
        } catch (Exception e) {
            log.warn("Could not parse job payload_json: {}", e.getMessage());
            return Map.of();
        }
    }

    public String toJson(Object value) {
        try { return objectMapper.writeValueAsString(value); }
        catch (Exception e) { throw new IllegalStateException("Failed to serialize JSON", e); }
    }

    private static UUID toUuid(Object value) {
        if (value == null) return null;
        if (value instanceof UUID uuid) return uuid;
        String text = value.toString();
        return text.isBlank() ? null : UUID.fromString(text);
    }

    private static String truncate(String message, int max) {
        if (message == null) return null;
        return message.length() <= max ? message : message.substring(0, max);
    }

    public record ClaimedJob(UUID id, UUID projectId, UUID patchId, String payloadJson, Map<String, Object> payload) {
        public String string(String key) {
            Object value = payload.get(key);
            return value != null ? value.toString() : null;
        }
        public int intValue(String key, int defaultValue) {
            Object value = payload.get(key);
            if (value instanceof Number number) return number.intValue();
            if (value != null) {
                try { return Integer.parseInt(value.toString()); }
                catch (NumberFormatException ignored) { return defaultValue; }
            }
            return defaultValue;
        }
    }
}
