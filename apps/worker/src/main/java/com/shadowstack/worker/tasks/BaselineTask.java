package com.shadowstack.worker.tasks;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shadowstack.analysis.BaselineCapture;
import com.shadowstack.analysis.BaselineSnapshot;
import com.shadowstack.corpus.AuditService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Executes Phase 0 baseline capture for a target project.
 *
 * <p>Captures the complete immutable snapshot of a project's observable
 * characteristics before any refactoring transformations are applied.
 * Results are persisted to the database and an audit trail entry is created.</p>
 *
 * <h3>Captured data includes</h3>
 * <ul>
 *   <li>Compilation status</li>
 *   <li>Test discovery and execution results</li>
 *   <li>API surface (public classes, methods, fields)</li>
 *   <li>Behavioral markers (reflection, concurrency, IO boundaries)</li>
 *   <li>Cyclomatic complexity metrics per method</li>
 *   <li>Semantic fingerprints for public methods</li>
 * </ul>
 */
@Component
public class BaselineTask {

    private static final Logger LOG = LoggerFactory.getLogger(BaselineTask.class);

    private final BaselineCapture baselineCapture;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;
    private final ThreadPoolTaskExecutor executor;

    public BaselineTask(
            AuditService auditService,
            ObjectMapper objectMapper,
            @Qualifier("workerTaskExecutor") ThreadPoolTaskExecutor executor) {
        this.baselineCapture = new BaselineCapture();
        this.auditService = auditService;
        this.objectMapper = objectMapper;
        this.executor = executor;
    }

    /**
     * Executes baseline capture asynchronously for the given project.
     *
     * @param projectId  unique project identifier
     * @param sourceRoot path to the project's source root directory
     * @param actorId    the user or system identity initiating the capture
     * @return a CompletableFuture containing the baseline snapshot
     */
    public CompletableFuture<BaselineSnapshot> execute(UUID projectId, Path sourceRoot, String actorId) {
        Objects.requireNonNull(projectId, "projectId must not be null");
        Objects.requireNonNull(sourceRoot, "sourceRoot must not be null");
        Objects.requireNonNull(actorId, "actorId must not be null");

        return CompletableFuture.supplyAsync(() -> {
            Instant start = Instant.now();
            UUID taskId = UUID.randomUUID();

            LOG.info("[task={}] Starting baseline capture for project {} at {}",
                    taskId, projectId, sourceRoot);

            auditService.logAction(
                    "BASELINE_CAPTURE_STARTED",
                    "project",
                    projectId.toString(),
                    actorId,
                    "worker",
                    Map.of(
                            "taskId", taskId.toString(),
                            "sourceRoot", sourceRoot.toString()
                    )
            );

            try {
                BaselineSnapshot snapshot = baselineCapture.captureBaseline(sourceRoot);
                Duration elapsed = Duration.between(start, Instant.now());

                LOG.info("[task={}] Baseline capture completed in {}ms — compile={}, tests={}/{}, " +
                                "apiSignatures={}, reflectionUsages={}, concurrencyPatterns={}, ioBoundaries={}",
                        taskId, elapsed.toMillis(),
                        snapshot.compileSuccess(),
                        snapshot.testResults().passed(), snapshot.testResults().totalTests(),
                        snapshot.apiSignatures().size(),
                        snapshot.reflectionUsages().size(),
                        snapshot.concurrencyPatterns().size(),
                        snapshot.ioBoundaries().size());

                auditService.logAction(
                        "BASELINE_CAPTURE_COMPLETED",
                        "project",
                        projectId.toString(),
                        actorId,
                        "worker",
                        Map.of(
                                "taskId", taskId.toString(),
                                "durationMs", elapsed.toMillis(),
                                "compileSuccess", snapshot.compileSuccess(),
                                "totalTests", snapshot.testResults().totalTests(),
                                "passedTests", snapshot.testResults().passed(),
                                "failedTests", snapshot.testResults().failed(),
                                "apiSignatureCount", snapshot.apiSignatures().size(),
                                "reflectionUsageCount", snapshot.reflectionUsages().size(),
                                "concurrencyPatternCount", snapshot.concurrencyPatterns().size(),
                                "ioBoundaryCount", snapshot.ioBoundaries().size(),
                                "complexityEntryCount", snapshot.complexityMetrics().size(),
                                "fingerprintCount", snapshot.semanticFingerprints().size()
                        )
                );

                return snapshot;

            } catch (IOException e) {
                Duration elapsed = Duration.between(start, Instant.now());
                LOG.error("[task={}] Baseline capture failed after {}ms: {}",
                        taskId, elapsed.toMillis(), e.getMessage(), e);

                auditService.logAction(
                        "BASELINE_CAPTURE_FAILED",
                        "project",
                        projectId.toString(),
                        actorId,
                        "worker",
                        Map.of(
                                "taskId", taskId.toString(),
                                "durationMs", elapsed.toMillis(),
                                "error", e.getMessage()
                        )
                );

                throw new RuntimeException("Baseline capture failed for project " + projectId, e);
            }
        }, executor);
    }
}
