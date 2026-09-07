package com.shadowstack.api.service;

import com.shadowstack.api.config.ShadowStackConfig;
import com.shadowstack.api.dto.PatchDetailResponse;
import com.shadowstack.api.dto.PatchDetailResponse.Invariant;
import com.shadowstack.api.dto.PatchDetailResponse.PatchStatus;
import com.shadowstack.api.dto.PatchDetailResponse.ReviewInfo;
import com.shadowstack.api.dto.PatchDetailResponse.RiskAssessment;
import com.shadowstack.api.dto.PatchDetailResponse.VerificationEvidence;
import com.shadowstack.api.dto.ProjectResponse;
import com.shadowstack.api.dto.VerificationResultResponse;
import com.shadowstack.api.dto.VerificationResultResponse.CertificateInfo;
import com.shadowstack.api.dto.VerificationResultResponse.InvariantResults.InvariantCheck;
import com.shadowstack.api.dto.VerificationResultResponse.InvariantResults;
import com.shadowstack.api.dto.VerificationResultResponse.TestResults;
import com.shadowstack.api.dto.VerificationResultResponse.VerificationStatus;
import com.shadowstack.adapters.LanguageAdapter;
import com.shadowstack.adapters.cobol.CobolAdapter;
import com.shadowstack.adapters.model.PatchResult;
import com.shadowstack.adapters.model.RefactorCandidate;
import com.shadowstack.adapters.model.SemanticModel;
import com.shadowstack.adapters.model.VerificationResult;
import com.shadowstack.refactor.model.SafetyInvariant;
import com.shadowstack.refactor.RefactorEngine;
import com.shadowstack.refactor.RefactorRule;
import com.shadowstack.refactor.RuleCatalog;
import com.shadowstack.refactor.model.PatchUnit;
import com.shadowstack.refactor.model.RiskTier;
import com.shadowstack.refactor.model.SemanticContext;
import com.shadowstack.verify.VerificationPipeline;
import com.shadowstack.verify.model.Verdict;
import com.shadowstack.verify.layers.CompileVerifier;
import com.shadowstack.verify.model.VerificationContext;
import com.shadowstack.verify.model.VerificationLayerResult;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Real conversion orchestration backed by {@link RefactorEngine} + {@link CompileVerifier}.
 */
@Service
public class RefactorOrchestrationService {

    private static final Logger log = LoggerFactory.getLogger(RefactorOrchestrationService.class);

    private final ProjectService projectService;
    private final LanguageAdapterRegistry adapterRegistry;

    private final Map<UUID, List<CandidateInfo>> candidateStore = new ConcurrentHashMap<>();
    private final Map<UUID, PatchUnit> candidateUnits = new ConcurrentHashMap<>();
    private final Map<UUID, PatchDetailResponse> patchStore = new ConcurrentHashMap<>();
    private final Map<UUID, PatchUnit> patchUnits = new ConcurrentHashMap<>();
    private final Map<UUID, List<UUID>> projectPatchIndex = new ConcurrentHashMap<>();
    private final Map<UUID, VerificationResultResponse> verificationStore = new ConcurrentHashMap<>();
    private final Map<UUID, String> projectLanguage = new ConcurrentHashMap<>();
    private final Map<UUID, RefactorCandidate> adapterCandidates = new ConcurrentHashMap<>();

    public RefactorOrchestrationService(
            ShadowStackConfig config,
            ProjectService projectService,
            LanguageAdapterRegistry adapterRegistry) {
        this.projectService = projectService;
        this.adapterRegistry = adapterRegistry;
    }

    public List<CandidateInfo> runAnalysis(UUID projectId) {
        Path root = projectService.requireProjectRoot(projectId);
        ProjectResponse project = projectService.getProject(projectId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown project " + projectId));
        String language = project.sourceLanguage() == null ? "java" : project.sourceLanguage();
        projectLanguage.put(projectId, language);
        log.info("Analyzing project {} at {} (language={})", projectId, root, language);

        List<CandidateInfo> candidates;
        Map<String, Integer> riskDistribution = new LinkedHashMap<>();

        if (adapterRegistry.isJavaEngineLanguage(language)) {
            candidates = analyzeWithJavaEngine(projectId, root, riskDistribution);
        } else {
            candidates = analyzeWithLanguageAdapter(projectId, root, language, riskDistribution);
        }

        candidateStore.put(projectId, candidates);
        projectService.updateAnalysisSummary(projectId, new ProjectResponse.AnalysisSummary(
                candidates.size(), 0, 0, 0, riskDistribution
        ));
        log.info("Analysis found {} candidates for {} ({})", candidates.size(), projectId, language);
        return candidates;
    }

    public List<CandidateInfo> getCandidates(UUID projectId) {
        return candidateStore.getOrDefault(projectId, List.of());
    }

    public PatchDetailResponse generatePatch(UUID projectId, UUID candidateId) {
        PatchUnit unit = candidateUnits.get(candidateId);
        if (unit == null) {
            throw new IllegalArgumentException("Unknown candidate " + candidateId + " — run /analyze first");
        }

        Instant now = Instant.now();
        UUID patchId = UUID.randomUUID();
        List<Invariant> invariants = Optional.ofNullable(unit.getInvariants()).orElse(List.of()).stream()
                .map(inv -> new Invariant(
                        inv.getInvariantId(),
                        inv.getDescription(),
                        inv.getEvidence() != null ? inv.getEvidence() : "",
                        inv.isVerified()))
                .toList();

        PatchDetailResponse patch = new PatchDetailResponse(
                patchId,
                projectId,
                candidateId,
                unit.getRuleId(),
                "modernization",
                PatchStatus.GENERATED,
                unit.getSourceFile(),
                unit.getStartLine(),
                unit.getEndLine(),
                unit.getUnifiedDiff(),
                unit.getRationale(),
                invariants,
                new RiskAssessment(
                        riskScore(unit.getRiskTier()),
                        mapRisk(unit.getRiskTier()),
                        List.of(),
                        unit.getConfidenceScore()
                ),
                null,
                null,
                now,
                now
        );
        patchStore.put(patchId, patch);
        patchUnits.put(patchId, unit);
        projectPatchIndex.computeIfAbsent(projectId, k -> new ArrayList<>()).add(patchId);
        return patch;
    }

    public List<PatchDetailResponse> getPatches(UUID projectId) {
        return projectPatchIndex.getOrDefault(projectId, List.of()).stream()
                .map(patchStore::get)
                .filter(Objects::nonNull)
                .toList();
    }

    public Optional<PatchDetailResponse> getPatch(UUID patchId) {
        return Optional.ofNullable(patchStore.get(patchId));
    }

    public VerificationResultResponse runVerification(UUID patchId) {
        PatchDetailResponse patch = patchStore.get(patchId);
        if (patch == null) {
            throw new PatchNotFoundException(patchId);
        }
        PatchUnit unit = patchUnits.get(patchId);
        Path root = projectService.requireProjectRoot(patch.projectId());
        Instant started = Instant.now();
        String language = projectLanguage.getOrDefault(patch.projectId(), "java");

        try {
            Path sourceFile = root.resolve(patch.filePath()).normalize();
            String original = Files.readString(sourceFile, StandardCharsets.UTF_8);
            String transformed = applySnippet(original, unit.getBeforeSnippet(), unit.getAfterSnippet());

            boolean passed;
            List<InvariantCheck> checks;
            String summary;

            if (adapterRegistry.isJavaEngineLanguage(language)) {
                VerificationPipeline pipeline = new VerificationPipeline(0.7, false);
                pipeline.addLayer(new CompileVerifier());
                VerificationContext context = VerificationContext.builder()
                        .projectRoot(root)
                        .sourceRoot(root)
                        .originalSource(original)
                        .transformedSource(transformed)
                        .build();
                VerificationPipeline.PipelineResult pipelineResult = pipeline.execute(unit, context);
                passed = pipelineResult.verdict() == Verdict.PASS;
                checks = pipelineResult.layerResults().stream()
                        .map(RefactorOrchestrationService::toCheck)
                        .toList();
                summary = pipelineResult.summary();
            } else {
                // Prefer adapter.applyRefactor on a full project temp copy so sibling
                // sources / .csproj / cobc deps exist; fall back to snippet rewrite.
                Path tempRoot = Files.createTempDirectory("shadowstack-verify-");
                try {
                    LanguageAdapter adapter = adapterRegistry.require(language);
                    copyProjectTree(root, tempRoot);
                    Path tempFile = tempRoot.resolve(patch.filePath());
                    Files.createDirectories(tempFile.getParent());

                    PatchResult applied;
                    RefactorCandidate adapterCandidate = adapterCandidates.get(patch.candidateId());
                    if (adapterCandidate != null) {
                        applied = adapter.applyRefactor(adapterCandidate, tempRoot);
                        if (!applied.success()) {
                            // Fall back to snippet application when line apply fails.
                            Files.writeString(tempFile, transformed, StandardCharsets.UTF_8);
                            applied = PatchResult.builder()
                                    .patchId(patchId)
                                    .unifiedDiff(unit.getUnifiedDiff() != null ? unit.getUnifiedDiff() : "")
                                    .beforeAstHash("before")
                                    .afterAstHash("after")
                                    .addAffectedFile(patch.filePath())
                                    .success(true)
                                    .putMetadata("applyFallback", "snippet")
                                    .build();
                        }
                    } else {
                        Files.writeString(tempFile, transformed, StandardCharsets.UTF_8);
                        applied = PatchResult.builder()
                                .patchId(patchId)
                                .unifiedDiff(unit.getUnifiedDiff() != null ? unit.getUnifiedDiff() : "")
                                .beforeAstHash("before")
                                .afterAstHash("after")
                                .addAffectedFile(patch.filePath())
                                .success(true)
                                .putMetadata("applyFallback", "snippet-no-candidate")
                                .build();
                    }

                    VerificationResult vr = adapter.verifyPatch(
                            applied, tempRoot, LanguageAdapter.VerificationConfig.defaults());
                    // Company-demo bar: WARN / structural-only must NOT enter PENDING_REVIEW.
                    passed = vr.verdict() == VerificationResult.Verdict.PASS;
                    checks = vr.layerResults().stream()
                            .map(layer -> new InvariantCheck(
                                    layer.layerName(),
                                    layer.details() != null ? layer.details() : layer.layerName(),
                                    layer.passed(),
                                    "score=" + layer.score()))
                            .toList();
                    Object applyMode = applied.metadata() != null
                            ? applied.metadata().getOrDefault("applyFallback", "refactor")
                            : "refactor";
                    summary = "adapter:" + language + " verdict=" + vr.verdict()
                            + " apply=" + applyMode;
                } finally {
                    deleteRecursively(tempRoot);
                }
            }

            // Empty / identity diffs must never enter PENDING_REVIEW even if the
            // adapter soft-PASSed compilation (e.g. detect-only COBOL rules).
            if (passed && isIdentityPatch(unit)) {
                log.warn("Forcing VERIFICATION_FAILED for patch {} ({}): identity/empty diff "
                                + "(unifiedDiff blank or beforeSnippet==afterSnippet)",
                        patchId, patch.ruleName());
                passed = false;
                summary = (summary != null ? summary + "; " : "")
                        + "identity-patch-rejected";
            }

            Instant completed = Instant.now();
            int preserved = (int) checks.stream().filter(InvariantCheck::preserved).count();
            int violated = checks.size() - preserved;

            VerificationResultResponse result = new VerificationResultResponse(
                    patchId,
                    UUID.randomUUID(),
                    passed ? VerificationStatus.PASSED : VerificationStatus.FAILED,
                    passed,
                    new TestResults(0, 0, 0, 0, 0, List.of()),
                    new InvariantResults(checks.size(), preserved, violated, checks),
                    new CertificateInfo(
                            UUID.randomUUID(),
                            passed,
                            passed
                                    ? "Verification passed for " + patch.ruleName()
                                    : "Verification failed for " + patch.ruleName(),
                            Map.of("pipeline", summary),
                            completed
                    ),
                    Duration.between(started, completed).toMillis(),
                    started,
                    completed
            );
            verificationStore.put(patchId, result);

            List<String> verified = checks.stream().filter(InvariantCheck::preserved)
                    .map(InvariantCheck::description).toList();
            List<String> failed = checks.stream().filter(c -> !c.preserved())
                    .map(InvariantCheck::description).toList();

            PatchDetailResponse updated = new PatchDetailResponse(
                    patch.patchId(), patch.projectId(), patch.candidateId(),
                    patch.ruleName(), patch.ruleCategory(),
                    passed ? PatchStatus.PENDING_REVIEW : PatchStatus.VERIFICATION_FAILED,
                    patch.filePath(), patch.startLine(), patch.endLine(),
                    patch.unifiedDiff(), patch.rationale(), patch.invariants(),
                    patch.risk(),
                    new VerificationEvidence(
                            passed, 0, 0, 0, verified, failed,
                            Map.of("verifier", language), completed
                    ),
                    patch.review(), patch.createdAt(), Instant.now()
            );
            patchStore.put(patchId, updated);
            return result;
        } catch (IOException e) {
            throw new IllegalStateException("Verification could not read sources: " + e.getMessage(), e);
        }
    }

    public Optional<VerificationResultResponse> getVerificationResult(UUID patchId) {
        return Optional.ofNullable(verificationStore.get(patchId));
    }

    public PatchDetailResponse applyReviewDecision(
            UUID patchId, boolean accepted, String reviewer, String reason) {
        PatchDetailResponse patch = patchStore.get(patchId);
        if (patch == null) {
            throw new PatchNotFoundException(patchId);
        }
        PatchDetailResponse updated = new PatchDetailResponse(
                patch.patchId(), patch.projectId(), patch.candidateId(),
                patch.ruleName(), patch.ruleCategory(),
                accepted ? PatchStatus.ACCEPTED : PatchStatus.REJECTED,
                patch.filePath(), patch.startLine(), patch.endLine(),
                patch.unifiedDiff(), patch.rationale(), patch.invariants(),
                patch.risk(), patch.verificationEvidence(),
                new ReviewInfo(reviewer, accepted, reason, Instant.now()),
                patch.createdAt(), Instant.now()
        );
        patchStore.put(patchId, updated);
        return updated;
    }

    public List<PatchDetailResponse> getPendingReviewPatches() {
        return patchStore.values().stream()
                .filter(p -> p.status() == PatchStatus.PENDING_REVIEW)
                .toList();
    }

    public List<PatchDetailResponse> getReviewedPatches() {
        return patchStore.values().stream()
                .filter(p -> p.status() == PatchStatus.ACCEPTED || p.status() == PatchStatus.REJECTED)
                .toList();
    }

    public List<PatchDetailResponse> runFullPipeline(UUID projectId) {
        List<CandidateInfo> candidates = runAnalysis(projectId);
        List<PatchDetailResponse> out = new ArrayList<>();
        String language = projectLanguage.getOrDefault(projectId, "java");
        for (CandidateInfo candidate : candidates) {
            try {
                if (!isAutoApplicable(language, candidate.ruleName())) {
                    log.debug("Skipping detect-only rule {} for demo auto-apply", candidate.ruleName());
                    continue;
                }
                PatchDetailResponse generated = generatePatch(projectId, candidate.candidateId());
                try {
                    runVerification(generated.patchId());
                } catch (Exception verifyError) {
                    log.warn("Verification failed for patch {} ({}): {}",
                            generated.patchId(), candidate.ruleName(), verifyError.getMessage());
                }
                PatchDetailResponse current = patchStore.get(generated.patchId());
                if (current != null) {
                    if (current.status() == PatchStatus.VERIFICATION_FAILED
                            && isIdentityPatch(current)) {
                        log.debug("Omitting identity/empty VERIFICATION_FAILED patch {} ({})",
                                current.patchId(), current.ruleName());
                        continue;
                    }
                    out.add(current);
                }
            } catch (Exception generateError) {
                log.warn("Skipping candidate {} ({}): {}",
                        candidate.candidateId(), candidate.ruleName(), generateError.getMessage());
            }
        }
        return out;
    }

    private static InvariantCheck toCheck(VerificationLayerResult layer) {
        return new InvariantCheck(
                layer.getLayerId(),
                layer.getSummary() != null ? layer.getSummary() : layer.getLayerId(),
                layer.passed(),
                String.join("; ", layer.getDiagnostics() != null ? layer.getDiagnostics() : List.of())
        );
    }


    private List<CandidateInfo> analyzeWithJavaEngine(
            UUID projectId, Path root, Map<String, Integer> riskDistribution) {
        RefactorEngine engine = createEngine();
        List<CandidateInfo> candidates = new ArrayList<>();
        try {
            for (Path javaFile : discoverJavaFiles(root)) {
                String source = Files.readString(javaFile, StandardCharsets.UTF_8);
                String relative = root.relativize(javaFile).toString().replace('\\', '/');
                CompilationUnit cu = parseCompilationUnit(source, javaFile);
                SemanticContext context = SemanticContext.builder()
                        .compilationUnit(cu)
                        .sourceFilePath(relative)
                        .sourceCode(source)
                        .build();

                for (PatchUnit unit : engine.scan(cu, context)) {
                    candidates.add(registerCandidate(projectId, unit, riskDistribution));
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to analyze " + root + ": " + e.getMessage(), e);
        }
        return candidates;
    }

    private List<CandidateInfo> analyzeWithLanguageAdapter(
            UUID projectId, Path root, String language, Map<String, Integer> riskDistribution) {
        LanguageAdapter adapter = adapterRegistry.require(language);
        SemanticModel model = adapter.buildSemanticModel(root);
        List<RefactorCandidate> found = adapter.listRefactorCandidates(
                model, LanguageAdapter.RefactorRuleSet.empty());
        List<CandidateInfo> candidates = new ArrayList<>();
        for (RefactorCandidate candidate : found) {
            PatchUnit unit = toPatchUnit(candidate);
            CandidateInfo info = registerCandidate(projectId, unit, riskDistribution);
            adapterCandidates.put(info.candidateId(), candidate);
            candidates.add(info);
        }
        return candidates;
    }

    private CandidateInfo registerCandidate(
            UUID projectId, PatchUnit unit, Map<String, Integer> riskDistribution) {
        UUID candidateId = UUID.randomUUID();
        candidateUnits.put(candidateId, unit);
        riskDistribution.merge(unit.getRiskTier().name(), 1, Integer::sum);
        return new CandidateInfo(
                candidateId,
                projectId,
                unit.getRuleId(),
                "modernization",
                unit.getSourceFile(),
                unit.getStartLine(),
                unit.getEndLine(),
                unit.getRationale() != null ? unit.getRationale() : unit.getRuleId(),
                riskScore(unit.getRiskTier()),
                unit.getConfidenceScore()
        );
    }

    private static PatchUnit toPatchUnit(RefactorCandidate candidate) {
        String before = candidate.beforeSnippet() != null ? candidate.beforeSnippet() : "";
        String after = candidate.proposedAfterSnippet() != null ? candidate.proposedAfterSnippet() : "";
        List<String> beforeLines = List.of(before.split("\n", -1));
        List<String> afterLines = List.of(after.split("\n", -1));
        String diff = PatchUnit.computeUnifiedDiff(
                beforeLines, afterLines, candidate.sourceFile(), candidate.startLine());
        PatchUnit.Builder builder = PatchUnit.builder(candidate.ruleId(), candidate.sourceFile())
                .startLine(candidate.startLine())
                .endLine(candidate.endLine())
                .beforeSnippet(before)
                .afterSnippet(after)
                .unifiedDiff(diff)
                .confidenceScore(candidate.confidenceScore())
                .riskTier(mapAdapterRisk(candidate.riskTier()))
                .rationale(candidate.ruleName() + ": modernization candidate");
        if (candidate.safetyInvariants() != null) {
            for (var inv : candidate.safetyInvariants()) {
                builder.addInvariant(SafetyInvariant.verified(
                        inv.invariantId(),
                        inv.description(),
                        inv.evidence()));
            }
        }
        return builder.build();
    }

    private static RiskTier mapAdapterRisk(com.shadowstack.adapters.model.RiskTier tier) {
        if (tier == null) {
            return RiskTier.MEDIUM;
        }
        return switch (tier) {
            case LOW -> RiskTier.LOW;
            case MODERATE -> RiskTier.MEDIUM;
            case HIGH -> RiskTier.HIGH;
            case CRITICAL -> RiskTier.CRITICAL;
        };
    }

    private RefactorEngine createEngine() {
        RefactorEngine engine = new RefactorEngine(0.55, RiskTier.CRITICAL);
        for (RefactorRule rule : RuleCatalog.javaRules()) {
            engine.registerRule(rule);
        }
        return engine;
    }

    private static List<Path> discoverJavaFiles(Path root) throws IOException {
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
                String name = dir.getFileName() != null ? dir.getFileName().toString() : "";
                if (Set.of("target", "build", ".git", "node_modules").contains(name)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }
        });
        return files;
    }

    private static CompilationUnit parseCompilationUnit(String source, Path filePath) {
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

    private static String applySnippet(String original, String before, String after) {
        if (before == null || before.isEmpty()) {
            return original;
        }
        String normalized = original.replace("\r\n", "\n");
        String beforeNorm = before.replace("\r\n", "\n");
        String afterNorm = after != null ? after.replace("\r\n", "\n") : "";
        int idx = normalized.indexOf(beforeNorm);
        if (idx < 0) {
            throw new IllegalStateException("Could not locate before-snippet in source");
        }
        return normalized.substring(0, idx) + afterNorm + normalized.substring(idx + beforeNorm.length());
    }

    private static double riskScore(RiskTier tier) {
        return switch (tier) {
            case COSMETIC, LOW -> 0.15;
            case MEDIUM -> 0.45;
            case HIGH -> 0.70;
            case CRITICAL -> 0.90;
        };
    }

    private static RiskAssessment.RiskTier mapRisk(RiskTier tier) {
        return switch (tier) {
            case COSMETIC, LOW -> RiskAssessment.RiskTier.LOW;
            case MEDIUM -> RiskAssessment.RiskTier.MEDIUM;
            case HIGH -> RiskAssessment.RiskTier.HIGH;
            case CRITICAL -> RiskAssessment.RiskTier.CRITICAL;
        };
    }


    /**
     * Copy a project tree into {@code to} for out-of-place verification, skipping
     * build/VCS/cache directories that are never needed by language adapters.
     */
    static void copyProjectTree(Path from, Path to) throws IOException {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        Set<String> skipDirs = Set.of("target", ".git", "bin", "obj", "__pycache__", "node_modules");
        Files.walkFileTree(from, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs)
                    throws IOException {
                String name = dir.getFileName() != null ? dir.getFileName().toString() : "";
                if (skipDirs.contains(name)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                Path dest = to.resolve(from.relativize(dir).toString());
                Files.createDirectories(dest);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Path dest = to.resolve(from.relativize(file).toString());
                Files.createDirectories(dest.getParent());
                Files.copy(file, dest, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    /** True when the patch has no meaningful change (blank diff or identical snippets). */
    static boolean isIdentityPatch(PatchUnit unit) {
        if (unit == null) {
            return true;
        }
        String diff = unit.getUnifiedDiff();
        if (diff == null || diff.isBlank()) {
            return true;
        }
        String before = unit.getBeforeSnippet() != null ? unit.getBeforeSnippet() : "";
        String after = unit.getAfterSnippet() != null ? unit.getAfterSnippet() : "";
        return Objects.equals(before, after);
    }

    /** True when the stored patch detail has no meaningful change (blank/null diff). */
    static boolean isIdentityPatch(PatchDetailResponse patch) {
        if (patch == null) {
            return true;
        }
        String diff = patch.unifiedDiff();
        return diff == null || diff.isBlank();
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (root == null || !Files.exists(root)) {
            return;
        }
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.deleteIfExists(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                Files.deleteIfExists(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    public record CandidateInfo(
            UUID candidateId,
            UUID projectId,
            String ruleName,
            String ruleCategory,
            String filePath,
            int startLine,
            int endLine,
            String description,
            double estimatedRisk,
            double confidence
    ) {}

    public static class PatchNotFoundException extends RuntimeException {
        private final UUID patchId;

        public PatchNotFoundException(UUID patchId) {
            super("Patch not found: " + patchId);
            this.patchId = patchId;
        }

        public UUID getPatchId() {
            return patchId;
        }
    }

    /**
     * Rules safe to auto-apply into the company-demo review queue.
     * Detect-only / migration-hint rules stay visible as candidates but are not
     * promoted to PENDING_REVIEW (they inject cross-language stubs or leave files broken).
     */
    static boolean isAutoApplicable(String language, String ruleId) {
        if (ruleId == null || language == null) {
            return false;
        }
        String lang = language.toLowerCase(java.util.Locale.ROOT);
        return switch (lang) {
            case "java" -> true;
            case "python", "python3", "py" -> ruleId.startsWith("py.");
            case "cobol", "cbl", "cob" -> CobolAdapter.isAutoApplicablePreserving(ruleId);
            case "javascript", "js", "typescript", "ts" -> Set.of(
                    "js.var_to_let",
                    "js.prefer_const",
                    "js.==_to_===",
                    "js.!=_to_!==",
                    "js.substr_to_substring",
                    "js.indexof_to_includes",
                    "js.indexof_zero_to_startswith",
                    "js.charat0_to_at",
                    "js.object_assign_to_spread",
                    "js.escape_to_encodeuri",
                    "js.unescape_to_decodeuri",
                    "js.string_concat_plus",
                    "js.optional_catch_binding"
            ).contains(ruleId);
            case "csharp", "cs", "c#" -> Set.of(
                    "cs.arraylist_to_list",
                    "cs.hashtable_to_dictionary",
                    "cs.string_format_to_interpolation",
                    "cs.string_concat_interpolate",
                    "cs.stringbuilder_appendformat",
                    "cs.string_isempty",
                    "cs.nameof_for_literals",
                    "cs.nullable_enable",
                    "cs.readonlycollection_to_ilist",
                    "cs.concurrentdict_tryadd",
                    "cs.using_declaration",
                    "cs.file_scoped_namespace"
            ).contains(ruleId);
            default -> false;
        };
    }

}
