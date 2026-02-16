package com.shadowstack.worker.tasks;

import com.shadowstack.analysis.BaselineSnapshot;
import com.shadowstack.analysis.ComplexityMetrics;
import com.shadowstack.analysis.ComplexityMetrics.ComplexityReport;
import com.shadowstack.analysis.RiskClassifier;
import com.shadowstack.analysis.RiskClassifier.RiskAssessment;
import com.shadowstack.corpus.AuditService;
import org.eclipse.jdt.core.dom.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * Executes Phase 1 static semantic modeling and risk classification.
 *
 * <p>Analyzes a project's source code to produce a comprehensive risk
 * assessment for every discovered class. This analysis determines which
 * classes are safe for automated refactoring and which require additional
 * review or manual verification.</p>
 *
 * <h3>Analysis pipeline</h3>
 * <ol>
 *   <li>Parse all Java source files into ASTs</li>
 *   <li>Compute cyclomatic complexity, nesting depth, and method count per file</li>
 *   <li>Cross-reference with the baseline snapshot for behavioral markers</li>
 *   <li>Produce per-class risk assessments with signal breakdowns</li>
 *   <li>Persist results and log audit trail</li>
 * </ol>
 */
@Component
public class AnalysisTask {

    private static final Logger LOG = LoggerFactory.getLogger(AnalysisTask.class);

    private final AuditService auditService;
    private final ThreadPoolTaskExecutor executor;

    public AnalysisTask(
            AuditService auditService,
            @Qualifier("workerTaskExecutor") ThreadPoolTaskExecutor executor) {
        this.auditService = auditService;
        this.executor = executor;
    }

    /**
     * Result container for the analysis task.
     *
     * @param riskAssessments per-class risk assessments
     * @param complexityReports per-file complexity reports
     * @param safeCount number of classes classified as SAFE
     * @param moderateCount number of classes classified as MODERATE_RISK
     * @param highRiskCount number of classes classified as HIGH_RISK
     */
    public record AnalysisResult(
            Map<String, RiskAssessment> riskAssessments,
            Map<String, ComplexityReport> complexityReports,
            int safeCount,
            int moderateCount,
            int highRiskCount
    ) {}

    /**
     * Executes static analysis and risk classification asynchronously.
     *
     * @param projectId  unique project identifier
     * @param sourceRoot path to the project's source root
     * @param snapshot   the baseline snapshot to cross-reference
     * @param actorId    the initiating user or system identity
     * @return a CompletableFuture containing the analysis result
     */
    public CompletableFuture<AnalysisResult> execute(
            UUID projectId, Path sourceRoot, BaselineSnapshot snapshot, String actorId) {

        Objects.requireNonNull(projectId, "projectId must not be null");
        Objects.requireNonNull(sourceRoot, "sourceRoot must not be null");
        Objects.requireNonNull(snapshot, "snapshot must not be null");
        Objects.requireNonNull(actorId, "actorId must not be null");

        return CompletableFuture.supplyAsync(() -> {
            Instant start = Instant.now();
            UUID taskId = UUID.randomUUID();

            LOG.info("[task={}] Starting static analysis for project {} at {}",
                    taskId, projectId, sourceRoot);

            auditService.logAction(
                    "ANALYSIS_STARTED",
                    "project",
                    projectId.toString(),
                    actorId,
                    "worker",
                    Map.of("taskId", taskId.toString(), "sourceRoot", sourceRoot.toString())
            );

            try {
                Map<String, ComplexityReport> complexityByFile = computeComplexityReports(sourceRoot);
                LOG.info("[task={}] Computed complexity for {} files", taskId, complexityByFile.size());

                Map<String, RiskAssessment> riskAssessments =
                        RiskClassifier.classifyAll(snapshot, complexityByFile);

                int safeCount = 0, moderateCount = 0, highRiskCount = 0;
                for (RiskAssessment assessment : riskAssessments.values()) {
                    switch (assessment.riskLevel()) {
                        case SAFE -> safeCount++;
                        case MODERATE_RISK -> moderateCount++;
                        case HIGH_RISK -> highRiskCount++;
                    }
                }

                Duration elapsed = Duration.between(start, Instant.now());

                LOG.info("[task={}] Analysis complete in {}ms — {} classes analyzed: " +
                                "{} SAFE, {} MODERATE, {} HIGH_RISK",
                        taskId, elapsed.toMillis(), riskAssessments.size(),
                        safeCount, moderateCount, highRiskCount);

                auditService.logAction(
                        "ANALYSIS_COMPLETED",
                        "project",
                        projectId.toString(),
                        actorId,
                        "worker",
                        Map.of(
                                "taskId", taskId.toString(),
                                "durationMs", elapsed.toMillis(),
                                "classesAnalyzed", riskAssessments.size(),
                                "safeCount", safeCount,
                                "moderateCount", moderateCount,
                                "highRiskCount", highRiskCount
                        )
                );

                return new AnalysisResult(riskAssessments, complexityByFile,
                        safeCount, moderateCount, highRiskCount);

            } catch (Exception e) {
                Duration elapsed = Duration.between(start, Instant.now());
                LOG.error("[task={}] Analysis failed after {}ms: {}",
                        taskId, elapsed.toMillis(), e.getMessage(), e);

                auditService.logAction(
                        "ANALYSIS_FAILED",
                        "project",
                        projectId.toString(),
                        actorId,
                        "worker",
                        Map.of(
                                "taskId", taskId.toString(),
                                "durationMs", elapsed.toMillis(),
                                "error", e.getMessage() != null ? e.getMessage() : "unknown"
                        )
                );

                throw new RuntimeException("Analysis failed for project " + projectId, e);
            }
        }, executor);
    }

    /**
     * Discovers all Java source files and computes complexity reports for each.
     */
    private Map<String, ComplexityReport> computeComplexityReports(Path sourceRoot) throws Exception {
        Map<String, ComplexityReport> reports = new LinkedHashMap<>();

        Files.walkFileTree(sourceRoot, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (file.toString().endsWith(".java")) {
                    try {
                        String source = Files.readString(file, StandardCharsets.UTF_8);
                        CompilationUnit cu = parseCompilationUnit(source, file);
                        ComplexityReport report = ComplexityMetrics.analyze(cu, file);
                        String relativePath = sourceRoot.relativize(file).toString();
                        reports.put(relativePath, report);
                    } catch (Exception e) {
                        LOG.warn("Failed to compute complexity for {}: {}", file, e.getMessage());
                    }
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                String dirName = dir.getFileName().toString();
                if (dirName.equals("target") || dirName.equals("build") ||
                        dirName.equals(".git") || dirName.equals("node_modules")) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }
        });

        return reports;
    }

    private CompilationUnit parseCompilationUnit(String source, Path filePath) {
        ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
        parser.setKind(ASTParser.K_COMPILATION_UNIT);
        parser.setSource(source.toCharArray());
        parser.setResolveBindings(false);

        Map<String, String> options = new HashMap<>();
        options.put("org.eclipse.jdt.core.compiler.source", "21");
        options.put("org.eclipse.jdt.core.compiler.compliance", "21");
        options.put("org.eclipse.jdt.core.compiler.codegen.targetPlatform", "21");
        parser.setCompilerOptions(options);
        parser.setUnitName(filePath.getFileName().toString());

        return (CompilationUnit) parser.createAST(null);
    }
}
