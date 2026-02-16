package com.shadowstack.worker.tasks;

import com.shadowstack.analysis.RiskClassifier.RiskAssessment;
import com.shadowstack.corpus.AuditService;
import com.shadowstack.refactor.RefactorEngine;
import com.shadowstack.refactor.RefactorRule;
import com.shadowstack.refactor.model.PatchUnit;
import com.shadowstack.refactor.model.SemanticContext;
import com.shadowstack.refactor.rules.AnonymousClassToLambdaRule;
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
 * Executes Phase 2 atomic patch generation for refactoring candidates.
 *
 * <p>Scans source files using the {@link RefactorEngine} to discover
 * transformation candidates, generates independent non-overlapping patches,
 * and queues them for verification and review.</p>
 *
 * <h3>Pipeline</h3>
 * <ol>
 *   <li>Initialize the refactor engine with registered rules</li>
 *   <li>For each source file, parse and scan for candidates</li>
 *   <li>Filter candidates by risk assessment thresholds</li>
 *   <li>Generate atomic patch units</li>
 *   <li>Queue patches for verification</li>
 *   <li>Log audit trail</li>
 * </ol>
 */
@Component
public class PatchGenerationTask {

    private static final Logger LOG = LoggerFactory.getLogger(PatchGenerationTask.class);

    private final AuditService auditService;
    private final ThreadPoolTaskExecutor executor;

    public PatchGenerationTask(
            AuditService auditService,
            @Qualifier("workerTaskExecutor") ThreadPoolTaskExecutor executor) {
        this.auditService = auditService;
        this.executor = executor;
    }

    /**
     * Result container for patch generation.
     *
     * @param patches       generated patch units
     * @param filesScanned  number of source files scanned
     * @param candidatesFound total candidates discovered before filtering
     * @param statistics    engine scan statistics
     */
    public record PatchGenerationResult(
            List<PatchUnit> patches,
            int filesScanned,
            int candidatesFound,
            RefactorEngine.ScanStatistics statistics
    ) {}

    /**
     * Generates atomic patches for the given project.
     *
     * @param projectId       unique project identifier
     * @param sourceRoot      path to the project's source root
     * @param riskAssessments per-class risk assessments from the analysis phase
     * @param actorId         the initiating user or system identity
     * @return a CompletableFuture containing the generation result
     */
    public CompletableFuture<PatchGenerationResult> execute(
            UUID projectId,
            Path sourceRoot,
            Map<String, RiskAssessment> riskAssessments,
            String actorId) {

        Objects.requireNonNull(projectId, "projectId must not be null");
        Objects.requireNonNull(sourceRoot, "sourceRoot must not be null");
        Objects.requireNonNull(actorId, "actorId must not be null");

        return CompletableFuture.supplyAsync(() -> {
            Instant start = Instant.now();
            UUID taskId = UUID.randomUUID();

            LOG.info("[task={}] Starting patch generation for project {} at {}",
                    taskId, projectId, sourceRoot);

            auditService.logAction(
                    "PATCH_GENERATION_STARTED",
                    "project",
                    projectId.toString(),
                    actorId,
                    "worker",
                    Map.of("taskId", taskId.toString(), "sourceRoot", sourceRoot.toString())
            );

            try {
                RefactorEngine engine = createEngine();
                List<PatchUnit> allPatches = new ArrayList<>();
                int filesScanned = 0;
                int totalCandidates = 0;

                List<Path> javaFiles = discoverJavaFiles(sourceRoot);
                LOG.info("[task={}] Discovered {} Java source files", taskId, javaFiles.size());

                for (Path javaFile : javaFiles) {
                    try {
                        String source = Files.readString(javaFile, StandardCharsets.UTF_8);
                        CompilationUnit cu = parseCompilationUnit(source, javaFile);
                        String relativePath = sourceRoot.relativize(javaFile).toString();

                        SemanticContext context = new SemanticContext(relativePath, source);
                        List<PatchUnit> patches = engine.scan(cu, context);

                        allPatches.addAll(patches);
                        totalCandidates += patches.size();
                        filesScanned++;

                        if (!patches.isEmpty()) {
                            LOG.info("[task={}] Generated {} patches for {}",
                                    taskId, patches.size(), relativePath);
                        }

                    } catch (Exception e) {
                        LOG.warn("[task={}] Failed to process {}: {}",
                                taskId, javaFile, e.getMessage());
                    }
                }

                RefactorEngine.ScanStatistics statistics = engine.getStatistics(allPatches);
                Duration elapsed = Duration.between(start, Instant.now());

                LOG.info("[task={}] Patch generation complete in {}ms — {} files scanned, " +
                                "{} patches generated, avgConfidence={:.3f}",
                        taskId, elapsed.toMillis(), filesScanned,
                        allPatches.size(), statistics.averageConfidence());

                auditService.logAction(
                        "PATCH_GENERATION_COMPLETED",
                        "project",
                        projectId.toString(),
                        actorId,
                        "worker",
                        Map.of(
                                "taskId", taskId.toString(),
                                "durationMs", elapsed.toMillis(),
                                "filesScanned", filesScanned,
                                "patchesGenerated", allPatches.size(),
                                "averageConfidence", statistics.averageConfidence(),
                                "rulesTriggered", statistics.rulesTriggered()
                        )
                );

                return new PatchGenerationResult(
                        List.copyOf(allPatches), filesScanned, totalCandidates, statistics);

            } catch (Exception e) {
                Duration elapsed = Duration.between(start, Instant.now());
                LOG.error("[task={}] Patch generation failed after {}ms: {}",
                        taskId, elapsed.toMillis(), e.getMessage(), e);

                auditService.logAction(
                        "PATCH_GENERATION_FAILED",
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

                throw new RuntimeException("Patch generation failed for project " + projectId, e);
            }
        }, executor);
    }

    /**
     * Creates and configures the refactor engine with all registered rules.
     */
    private RefactorEngine createEngine() {
        RefactorEngine engine = new RefactorEngine(0.6, com.shadowstack.refactor.model.RiskTier.CRITICAL);
        engine.registerRule(new AnonymousClassToLambdaRule());
        LOG.debug("Refactor engine created with {} rule(s)", engine.getRegisteredRules().size());
        return engine;
    }

    private List<Path> discoverJavaFiles(Path root) throws Exception {
        List<Path> files = new ArrayList<>();
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (file.toString().endsWith(".java")) {
                    files.add(file);
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
        return files;
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
