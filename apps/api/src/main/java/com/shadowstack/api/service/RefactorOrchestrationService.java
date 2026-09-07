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
import com.shadowstack.api.persistence.PatchStore;
import com.shadowstack.adapters.LanguageAdapter;
import com.shadowstack.adapters.cobol.CobolAdapter;
import com.shadowstack.adapters.model.PatchResult;
import com.shadowstack.adapters.model.RefactorCandidate;
import com.shadowstack.adapters.model.SemanticModel;
import com.shadowstack.adapters.model.VerificationResult;
import com.shadowstack.analysis.ContextPriorEstimator;
import com.shadowstack.analysis.RiskPosterior;
import com.shadowstack.refactor.model.SafetyInvariant;
import com.shadowstack.refactor.RefactorEngine;
import com.shadowstack.refactor.RefactorRule;
import com.shadowstack.refactor.RuleCatalog;
import com.shadowstack.refactor.model.PatchUnit;
import com.shadowstack.refactor.model.RiskTier;
import com.shadowstack.refactor.model.SemanticContext;
import com.shadowstack.verify.VerificationPipeline;
import com.shadowstack.verify.layers.APISignatureDiffVerifier;
import com.shadowstack.verify.layers.ASTStructuralComparator;
import com.shadowstack.verify.layers.BytecodeDescriptorComparator;
import com.shadowstack.verify.layers.CompileVerifier;
import com.shadowstack.verify.layers.GoldenMasterVerifier;
import com.shadowstack.verify.layers.SemanticRiskScorer;
import com.shadowstack.verify.layers.TestExecutionVerifier;
import com.shadowstack.verify.model.VerificationContext;
import com.shadowstack.verify.model.VerificationLayerResult;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
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
 * Real conversion orchestration backed by {@link RefactorEngine} and the full
 * Java verification stack ({@link CompileVerifier}, {@link ASTStructuralComparator},
 * {@link BytecodeDescriptorComparator}, {@link APISignatureDiffVerifier},
 * {@link TestExecutionVerifier}, {@link GoldenMasterVerifier},
 * {@link SemanticRiskScorer}).
 */
@Service
public class RefactorOrchestrationService {

    private static final Logger log = LoggerFactory.getLogger(RefactorOrchestrationService.class);

    private final ShadowStackConfig config;
    private final ProjectService projectService;
    private final LanguageAdapterRegistry adapterRegistry;
    private final PatchStore patchStore;
    private final RulePriorCalibrationService rulePriorCalibration;

    private final Map<UUID, List<CandidateInfo>> candidateStore = new ConcurrentHashMap<>();
    private final Map<UUID, PatchUnit> candidateUnits = new ConcurrentHashMap<>();
    private final Map<UUID, PatchUnit> patchUnits = new ConcurrentHashMap<>();
    private final Map<UUID, VerificationResultResponse> verificationStore = new ConcurrentHashMap<>();
    private final Map<UUID, String> projectLanguage = new ConcurrentHashMap<>();
    private final Map<UUID, RefactorCandidate> adapterCandidates = new ConcurrentHashMap<>();
    /** File-relative path → context prior from RiskClassifier/complexity. */
    private final Map<UUID, Map<String, Double>> projectContextPriors = new ConcurrentHashMap<>();

    public RefactorOrchestrationService(
            ShadowStackConfig config,
            ProjectService projectService,
            LanguageAdapterRegistry adapterRegistry,
            PatchStore patchStore,
            RulePriorCalibrationService rulePriorCalibration) {
        this.config = config;
        this.projectService = projectService;
        this.adapterRegistry = adapterRegistry;
        this.patchStore = patchStore;
        this.rulePriorCalibration = rulePriorCalibration;
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

        double basePrior = riskScore(unit.getRiskTier());
        double calibrated = rulePriorCalibration != null
                ? rulePriorCalibration.calibratedPrior(unit.getRuleId(), basePrior)
                : basePrior;
        double contextPrior = ContextPriorEstimator.lookup(
                projectContextPriors.get(projectId),
                unit.getSourceFile(),
                calibrated);
        double cw = config.risk() != null ? config.risk().contextPriorWeight() : 0.25;
        double initialScore = RiskPosterior.clamp(calibrated * (1.0 - cw) + contextPrior * cw);
        List<RiskAssessment.RiskFactor> priorFactors = List.of(
                new RiskAssessment.RiskFactor("rule_prior", "Static rule tier prior", 1.0, basePrior),
                new RiskAssessment.RiskFactor("calibrated_prior", "Prior after accept/reject feedback", 1.0, calibrated),
                new RiskAssessment.RiskFactor("context_prior", "Module complexity / context risk", cw, contextPrior)
        );

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
                        initialScore,
                        tierFromScore(initialScore),
                        priorFactors,
                        unit.getConfidenceScore(),
                        0.0
                ),
                null,
                null,
                currentUsername(),
                now,
                now
        );
        patchStore.save(patch);
        patchUnits.put(patchId, unit);
        return patch;
    }

    public List<PatchDetailResponse> getPatches(UUID projectId) {
        return patchStore.findByProject(projectId);
    }

    public Optional<PatchDetailResponse> getPatch(UUID patchId) {
        return patchStore.findById(patchId);
    }

    /**
     * Builds a self-contained VERIFY job payload so a worker can run the 7-layer
     * pipeline and update {@code ss_patches} without API callback.
     */
    public Map<String, Object> buildVerifyJobPayload(UUID patchId) {
        PatchDetailResponse patch = patchStore.findById(patchId)
                .orElseThrow(() -> new PatchNotFoundException(patchId));
        PatchUnit unit = patchUnits.get(patchId);
        if (unit == null) {
            throw new IllegalStateException(
                    "No in-memory PatchUnit for " + patchId + " — re-run generate before verify enqueue");
        }
        Path root = projectService.requireProjectRoot(patch.projectId());
        String language = projectLanguage.getOrDefault(patch.projectId(), "java");
        try {
            Path sourceFile = root.resolve(patch.filePath()).normalize();
            String original = Files.readString(sourceFile, StandardCharsets.UTF_8);
            String transformed = applySnippet(original, unit.getBeforeSnippet(), unit.getAfterSnippet());

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("patchId", patch.patchId().toString());
            payload.put("projectId", patch.projectId().toString());
            if (patch.candidateId() != null) {
                payload.put("candidateId", patch.candidateId().toString());
            }
            payload.put("filePath", patch.filePath());
            payload.put("ruleName", patch.ruleName());
            payload.put("ruleCategory", patch.ruleCategory());
            payload.put("startLine", patch.startLine());
            payload.put("endLine", patch.endLine());
            payload.put("unifiedDiff", patch.unifiedDiff());
            payload.put("rationale", patch.rationale());
            payload.put("beforeSnippet", unit.getBeforeSnippet());
            payload.put("afterSnippet", unit.getAfterSnippet());
            payload.put("originalSource", original);
            payload.put("transformedSource", transformed);
            payload.put("projectRoot", root.toAbsolutePath().normalize().toString());
            payload.put("language", language);
            if (patch.risk() != null) {
                payload.put("riskScore", patch.risk().score());
                payload.put("riskTier", patch.risk().tier() != null ? patch.risk().tier().name() : null);
                payload.put("confidence", patch.risk().confidenceScore());
                payload.put("evidenceStrength", patch.risk().evidenceStrength());
                double contextPrior = patch.risk().score();
                if (patch.risk().factors() != null) {
                    for (RiskAssessment.RiskFactor f : patch.risk().factors()) {
                        if ("context_prior".equals(f.name())) {
                            contextPrior = f.contribution();
                            break;
                        }
                    }
                }
                payload.put("contextPrior", contextPrior);
            }
            if (patch.createdAt() != null) {
                payload.put("createdAt", patch.createdAt().toString());
            }
            return payload;
        } catch (IOException e) {
            throw new IllegalStateException("Could not build VERIFY payload: " + e.getMessage(), e);
        }
    }

    public VerificationResultResponse runVerification(UUID patchId) {
        PatchDetailResponse patch = patchStore.findById(patchId)
                .orElseThrow(() -> new PatchNotFoundException(patchId));
        PatchUnit unit = patchUnits.get(patchId);
        Path root = projectService.requireProjectRoot(patch.projectId());
        Instant started = Instant.now();
        String language = projectLanguage.getOrDefault(patch.projectId(), "java");

        try {
            Path sourceFile = root.resolve(patch.filePath()).normalize();
            String original = Files.readString(sourceFile, StandardCharsets.UTF_8);
            String transformed = applySnippet(original, unit.getBeforeSnippet(), unit.getAfterSnippet());

            boolean passed;
            RiskPosterior.VerifyOutcome outcome;
            List<InvariantCheck> checks;
            String summary;
            double verifyRisk = 0.0;
            List<RiskAssessment.RiskFactor> verifyFactors = new ArrayList<>();
            List<RiskPosterior.LayerSignal> layerSignals = new ArrayList<>();
            Map<String, Object> lastAppliedMetadata = null;

            if (adapterRegistry.isJavaEngineLanguage(language)) {
                RiskTier priorTier = unit != null && unit.getRiskTier() != null
                        ? unit.getRiskTier()
                        : RiskTier.MEDIUM;
                VerificationPipeline pipeline = createJavaVerificationPipeline(priorTier);
                VerificationContext context = VerificationContext.builder()
                        .projectRoot(root)
                        .sourceRoot(root)
                        .originalSource(original)
                        .transformedSource(transformed)
                        .build();
                VerificationPipeline.PipelineResult pipelineResult = pipeline.execute(unit, context);
                // Fail-closed: only hard PASS promotes to PENDING_REVIEW.
                outcome = RiskPosterior.VerifyOutcome.fromName(
                        pipelineResult.verdict() != null ? pipelineResult.verdict().name() : "FAIL");
                passed = outcome == RiskPosterior.VerifyOutcome.PASS;
                checks = pipelineResult.layerResults().stream()
                        .map(RefactorOrchestrationService::toCheck)
                        .toList();
                summary = pipelineResult.summary();
                verifyRisk = pipelineResult.riskScore();
                for (VerificationLayerResult layer : pipelineResult.layerResults()) {
                    layerSignals.add(new RiskPosterior.LayerSignal(
                            layer.getLayerId(), layer.getRiskContribution(), layer.failed()));
                    if (layer.getRiskContribution() > 0
                            && !"semantic_risk_scorer".equals(layer.getLayerId())) {
                        verifyFactors.add(new RiskAssessment.RiskFactor(
                                layer.getLayerId(),
                                layer.getSummary() != null ? layer.getSummary() : layer.getLayerId(),
                                1.0,
                                layer.getRiskContribution()));
                    }
                }
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
                    outcome = RiskPosterior.VerifyOutcome.fromName(
                            vr.verdict() != null ? vr.verdict().name() : "FAIL");
                    passed = outcome == RiskPosterior.VerifyOutcome.PASS;
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
                    verifyRisk = RiskPosterior.clamp(vr.semanticRiskScore());
                    if (applied.metadata() != null) {
                        lastAppliedMetadata = applied.metadata();
                    }
                    for (var layer : vr.layerResults()) {
                        double contrib = layer.passed() ? Math.max(0, 0.15 * (1.0 - layer.score()))
                                : Math.max(0.2, 1.0 - layer.score());
                        layerSignals.add(new RiskPosterior.LayerSignal(
                                layer.layerName(), contrib, !layer.passed()));
                        if (contrib > 0) {
                            verifyFactors.add(new RiskAssessment.RiskFactor(
                                    layer.layerName(),
                                    layer.details() != null ? layer.details() : layer.layerName(),
                                    1.0,
                                    contrib));
                        }
                    }
                } finally {
                    deleteRecursively(tempRoot);
                }
            }

            // Empty / identity diffs must never enter PENDING_REVIEW even if an
            // adapter previously soft-PASSed (e.g. pre-javac COBOL translate stubs).
            if (passed && isIdentityPatch(unit)) {
                log.warn("Forcing VERIFICATION_FAILED for patch {} ({}): identity/empty diff "
                                + "(unifiedDiff blank or beforeSnippet==afterSnippet)",
                        patchId, patch.ruleName());
                passed = false;
                outcome = RiskPosterior.VerifyOutcome.FAIL;
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

            int passedLayers = (int) checks.stream().filter(InvariantCheck::preserved).count();
            RiskAssessment blendedRisk = blendRiskAfterVerify(
                    patch.risk(),
                    verifyRisk,
                    verifyFactors,
                    layerSignals,
                    outcome,
                    passedLayers,
                    checks.size(),
                    patch.projectId(),
                    patch.filePath(),
                    patch.ruleName());

            PatchStatus nextStatus = passed ? PatchStatus.PENDING_REVIEW : PatchStatus.VERIFICATION_FAILED;
            if (passed && blendedRisk != null
                    && config.risk() != null
                    && config.risk().canAutoApply(blendedRisk.score(), blendedRisk.evidenceStrength())) {
                nextStatus = PatchStatus.ACCEPTED;
                log.info("Auto-applying patch {} (residual={}, evidence={})",
                        patchId, blendedRisk.score(), blendedRisk.evidenceStrength());
            }

            Map<String, Object> proofArtifacts = new LinkedHashMap<>();
            proofArtifacts.put("verifier", language);
            proofArtifacts.put("verifyRisk", verifyRisk);
            proofArtifacts.put("rulePriorScore", patch.risk() != null ? patch.risk().score() : 0.0);
            proofArtifacts.put("blendedRisk", blendedRisk.score());
            proofArtifacts.put("evidenceStrength", blendedRisk.evidenceStrength());
            proofArtifacts.put("outcome", outcome.name());
            // Phase 7: surface COBOL translate gaps / resolved CALLs on review UI.
            RefactorCandidate rehostCandidate = patch.candidateId() != null
                    ? adapterCandidates.get(patch.candidateId()) : null;
            copyCobolRehostProof(proofArtifacts, lastAppliedMetadata, rehostCandidate);

            PatchDetailResponse updated = new PatchDetailResponse(
                    patch.patchId(), patch.projectId(), patch.candidateId(),
                    patch.ruleName(), patch.ruleCategory(),
                    nextStatus,
                    patch.filePath(), patch.startLine(), patch.endLine(),
                    patch.unifiedDiff(), patch.rationale(), patch.invariants(),
                    blendedRisk,
                    new VerificationEvidence(
                            passed, 0, 0, 0, verified, failed,
                            proofArtifacts,
                            completed
                    ),
                    nextStatus == PatchStatus.ACCEPTED
                            ? new ReviewInfo("auto-apply", true, "auto-apply by residual risk gate", completed)
                            : patch.review(),
                    patch.createdBy(), patch.createdAt(), Instant.now()
            );
            patchStore.save(updated);
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
        PatchDetailResponse patch = patchStore.findById(patchId)
                .orElseThrow(() -> new PatchNotFoundException(patchId));
        if (rulePriorCalibration != null) {
            rulePriorCalibration.recordDecision(patch.ruleName(), accepted);
        }
        PatchDetailResponse updated = new PatchDetailResponse(
                patch.patchId(), patch.projectId(), patch.candidateId(),
                patch.ruleName(), patch.ruleCategory(),
                accepted ? PatchStatus.ACCEPTED : PatchStatus.REJECTED,
                patch.filePath(), patch.startLine(), patch.endLine(),
                patch.unifiedDiff(), patch.rationale(), patch.invariants(),
                patch.risk(), patch.verificationEvidence(),
                new ReviewInfo(reviewer, accepted, reason, Instant.now()),
                patch.createdBy(), patch.createdAt(), Instant.now()
        );
        patchStore.save(updated);
        return updated;
    }

    public List<PatchDetailResponse> getPendingReviewPatches() {
        return patchStore.findByStatus(PatchStatus.PENDING_REVIEW);
    }

    public List<PatchDetailResponse> getReviewedPatches() {
        List<PatchDetailResponse> reviewed = new ArrayList<>();
        reviewed.addAll(patchStore.findByStatus(PatchStatus.ACCEPTED));
        reviewed.addAll(patchStore.findByStatus(PatchStatus.REJECTED));
        return reviewed;
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
                PatchDetailResponse current = patchStore.findById(generated.patchId()).orElse(null);
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
                !layer.failed(),
                String.join("; ", layer.getDiagnostics() != null ? layer.getDiagnostics() : List.of())
        );
    }

    /**
     * Java verification pipeline. COSMETIC/LOW skip Maven test/golden for speed —
     * compile + AST + API + semantic aggregate still gate. MEDIUM+ run the full stack.
     * SemanticRiskScorer is always last and is the sole risk aggregator.
     */
    static VerificationPipeline createJavaVerificationPipeline() {
        return createJavaVerificationPipeline(RiskTier.MEDIUM);
    }

    static VerificationPipeline createJavaVerificationPipeline(RiskTier priorTier) {
        VerificationPipeline pipeline = new VerificationPipeline(0.7, false);
        pipeline.addLayer(new CompileVerifier());
        pipeline.addLayer(new ASTStructuralComparator());
        pipeline.addLayer(new BytecodeDescriptorComparator());
        pipeline.addLayer(new APISignatureDiffVerifier());
        boolean fullStack = priorTier == RiskTier.MEDIUM
                || priorTier == RiskTier.HIGH
                || priorTier == RiskTier.CRITICAL;
        if (fullStack) {
            pipeline.addLayer(new TestExecutionVerifier());
            pipeline.addLayer(new GoldenMasterVerifier());
        }
        pipeline.addLayer(new SemanticRiskScorer());
        return pipeline;
    }


    private List<CandidateInfo> analyzeWithJavaEngine(
            UUID projectId, Path root, Map<String, Integer> riskDistribution) {
        RefactorEngine engine = createEngine();
        List<CandidateInfo> candidates = new ArrayList<>();
        Map<String, Double> contextPriors = new ConcurrentHashMap<>();
        try {
            for (Path javaFile : discoverJavaFiles(root)) {
                String source = Files.readString(javaFile, StandardCharsets.UTF_8);
                String relative = root.relativize(javaFile).toString().replace('\\', '/');
                CompilationUnit cu = parseCompilationUnit(source, javaFile);
                try {
                    contextPriors.put(relative, ContextPriorEstimator.fromCompilationUnit(cu, javaFile));
                } catch (Exception e) {
                    log.debug("Context prior skipped for {}: {}", relative, e.getMessage());
                    contextPriors.put(relative, 0.45);
                }
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
        projectContextPriors.put(projectId, contextPriors);
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

    /**
     * Evidence-weighted residual risk after verify.
     * PASS can lower the prior; WARN uses mild floor; FAIL floors at highThreshold.
     */
    RiskAssessment blendRiskAfterVerify(
            RiskAssessment prior,
            double verifyRisk,
            List<RiskAssessment.RiskFactor> verifyFactors,
            boolean passed) {
        return blendRiskAfterVerify(
                prior,
                verifyRisk,
                verifyFactors,
                List.of(),
                RiskPosterior.VerifyOutcome.fromPassedFlag(passed),
                passed ? 1 : 0,
                1,
                null,
                null,
                null);
    }

    RiskAssessment blendRiskAfterVerify(
            RiskAssessment prior,
            double verifyRisk,
            List<RiskAssessment.RiskFactor> verifyFactors,
            List<RiskPosterior.LayerSignal> layerSignals,
            RiskPosterior.VerifyOutcome outcome,
            int passedLayers,
            int totalLayers,
            UUID projectId,
            String filePath,
            String ruleName) {
        double rulePrior = prior != null ? prior.score() : riskScore(RiskTier.MEDIUM);
        if (rulePriorCalibration != null && ruleName != null) {
            // Prefer calibrated rule base when we still have a raw tier-ish prior in factors
            double base = rulePrior;
            for (RiskAssessment.RiskFactor f : prior != null && prior.factors() != null ? prior.factors() : List.<RiskAssessment.RiskFactor>of()) {
                if ("calibrated_prior".equals(f.name()) || "rule_prior".equals(f.name())) {
                    base = f.contribution();
                    if ("calibrated_prior".equals(f.name())) {
                        break;
                    }
                }
            }
            rulePrior = rulePriorCalibration.calibratedPrior(ruleName, base);
        }
        double contextPrior = ContextPriorEstimator.lookup(
                projectId != null ? projectContextPriors.get(projectId) : null,
                filePath,
                rulePrior);
        double blast = RiskPosterior.blastRadius(layerSignals);
        double evidence = RiskPosterior.evidenceStrength(outcome, verifyRisk, passedLayers, totalLayers);
        RiskPosterior.BlendConfig blendConfig = config.risk() != null
                ? config.risk().toBlendConfig()
                : RiskPosterior.BlendConfig.DEFAULTS;

        List<RiskPosterior.Factor> extras = new ArrayList<>();
        if (verifyFactors != null) {
            for (RiskAssessment.RiskFactor f : verifyFactors) {
                extras.add(new RiskPosterior.Factor(f.name(), f.description(), f.weight(), f.contribution()));
            }
        }
        RiskPosterior.Result posterior = RiskPosterior.blend(
                rulePrior, contextPrior, verifyRisk, blast, evidence, outcome, blendConfig, extras);

        double confidence = prior != null ? prior.confidenceScore() : 0.0;
        // Evidence strength becomes the post-verify confidence signal for UI.
        double displayConfidence = Math.max(confidence * 0.35, evidence);

        List<RiskAssessment.RiskFactor> factors = posterior.factors().stream()
                .map(f -> new RiskAssessment.RiskFactor(f.name(), f.description(), f.weight(), f.contribution()))
                .toList();
        return new RiskAssessment(
                posterior.residualRisk(),
                RiskAssessment.RiskTier.valueOf(posterior.tier()),
                factors,
                displayConfidence,
                posterior.evidenceStrength());
    }

    private RiskAssessment.RiskTier tierFromScore(double score) {
        String name = config.risk() != null
                ? config.risk().tierFor(score)
                : (score <= 0.3 ? "LOW" : score <= 0.6 ? "MEDIUM" : score <= 0.85 ? "HIGH" : "CRITICAL");
        return RiskAssessment.RiskTier.valueOf(name);
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

    /**
     * Copies COBOL rehost gap/CALL metadata into verification proofArtifacts so the
     * review UI can show translate gaps without a separate patch-metadata field.
     */
    static void copyCobolRehostProof(
            Map<String, Object> proofArtifacts,
            Map<String, Object> appliedMetadata,
            RefactorCandidate candidate) {
        if (proofArtifacts == null) {
            return;
        }
        putProofString(proofArtifacts, "translateGaps", appliedMetadata, candidate, "translateGaps");
        putProofString(proofArtifacts, "resolvedCalls", appliedMetadata, candidate, "resolvedCallsJoined");
        if (!proofArtifacts.containsKey("resolvedCalls")) {
            putProofString(proofArtifacts, "resolvedCalls", appliedMetadata, candidate, "resolvedCalls");
        }
        if (appliedMetadata != null && appliedMetadata.get("translateGapsList") != null) {
            proofArtifacts.put("translateGapsList", appliedMetadata.get("translateGapsList"));
        }
        if (appliedMetadata != null && appliedMetadata.get("resolvedCalls") instanceof List<?> calls) {
            proofArtifacts.putIfAbsent("resolvedCalls",
                    calls.stream().map(String::valueOf).reduce((a, b) -> a + "," + b).orElse(""));
        }
    }

    private static void putProofString(
            Map<String, Object> proof,
            String proofKey,
            Map<String, Object> appliedMetadata,
            RefactorCandidate candidate,
            String sourceKey) {
        Object fromMeta = appliedMetadata != null ? appliedMetadata.get(sourceKey) : null;
        if (fromMeta instanceof List<?> list) {
            String joined = list.stream().map(String::valueOf).reduce((a, b) -> a + "," + b).orElse("");
            if (!joined.isBlank()) {
                proof.put(proofKey, joined);
                return;
            }
        } else if (fromMeta != null) {
            String s = String.valueOf(fromMeta);
            if (!s.isBlank() && !"[]".equals(s)) {
                proof.put(proofKey, s);
                return;
            }
        }
        if (candidate != null && candidate.astContext() != null) {
            String fromAst = candidate.astContext().get(sourceKey);
            if (fromAst != null && !fromAst.isBlank()) {
                proof.put(proofKey, fromAst);
            }
        }
    }

    private static String currentUsername() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth.getName() == null) {
            return null;
        }
        String name = auth.getName();
        return "anonymousUser".equals(name) ? null : name;
    }

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
