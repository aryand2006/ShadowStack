package com.shadowstack.refactor;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.shadowstack.refactor.model.*;
import org.eclipse.jdt.core.dom.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Core refactoring engine that orchestrates rule-based code transformations.
 *
 * <p>The RefactorEngine is the central coordinator of the ShadowStack refactoring pipeline.
 * It manages a registry of {@link RefactorRule} implementations, scans AST trees for matching
 * candidates, and produces independent, non-overlapping {@link PatchUnit} objects.</p>
 *
 * <h3>Pipeline Flow</h3>
 * <pre>
 *   Source Code → Parse AST → For each rule:
 *     → Walk AST nodes → appliesTo() filter → analyze() candidates
 *     → Filter by invariants → Resolve overlaps → apply() patches
 *     → Return ordered, independent PatchUnits
 * </pre>
 *
 * <h3>Thread Safety</h3>
 * <p>The engine is thread-safe for rule registration and scanning. Multiple scans can execute
 * concurrently on different compilation units. Rules themselves must be stateless.</p>
 */
public final class RefactorEngine {

    private static final Logger log = LoggerFactory.getLogger(RefactorEngine.class);

    private final CopyOnWriteArrayList<RefactorRule> rules = new CopyOnWriteArrayList<>();
    private final ConcurrentHashMap<String, RefactorRule> ruleIndex = new ConcurrentHashMap<>();
    private final double minimumConfidenceThreshold;
    private final RiskTier maximumRiskTier;

    /**
     * Creates a new RefactorEngine with default settings.
     */
    public RefactorEngine() {
        this(0.0, RiskTier.CRITICAL);
    }

    /**
     * Creates a new RefactorEngine with configurable thresholds.
     *
     * @param minimumConfidenceThreshold candidates below this confidence are excluded (0.0 to 1.0)
     * @param maximumRiskTier            candidates above this risk tier are excluded
     */
    public RefactorEngine(double minimumConfidenceThreshold, RiskTier maximumRiskTier) {
        this.minimumConfidenceThreshold = minimumConfidenceThreshold;
        this.maximumRiskTier = Objects.requireNonNull(maximumRiskTier, "maximumRiskTier must not be null");
        log.info("RefactorEngine initialized: minConfidence={}, maxRisk={}",
                minimumConfidenceThreshold, maximumRiskTier);
    }

    /**
     * Registers a refactoring rule with the engine.
     *
     * @param rule the rule to register
     * @throws IllegalArgumentException if a rule with the same ID is already registered
     */
    public void registerRule(RefactorRule rule) {
        Objects.requireNonNull(rule, "rule must not be null");
        String id = rule.ruleId();
        Preconditions.checkArgument(!ruleIndex.containsKey(id),
                "Rule with ID '%s' is already registered", id);

        ruleIndex.put(id, rule);
        rules.add(rule);
        log.info("Registered refactoring rule: id='{}', name='{}', defaultRisk={}",
                id, rule.ruleName(), rule.defaultRiskTier());
    }

    /**
     * Unregisters a refactoring rule by its ID.
     *
     * @param ruleId the ID of the rule to remove
     * @return true if the rule was found and removed
     */
    public boolean unregisterRule(String ruleId) {
        RefactorRule removed = ruleIndex.remove(ruleId);
        if (removed != null) {
            rules.remove(removed);
            log.info("Unregistered refactoring rule: id='{}'", ruleId);
            return true;
        }
        return false;
    }

    /**
     * Returns an immutable list of all registered rules.
     */
    public ImmutableList<RefactorRule> getRegisteredRules() {
        return ImmutableList.copyOf(rules);
    }

    /**
     * Scans a compilation unit for refactoring candidates across all registered rules.
     *
     * <p>This is the main entry point for the refactoring pipeline. It walks the entire AST,
     * tests each node against every registered rule, analyzes matches, resolves overlaps,
     * and returns a list of independent, non-overlapping patch units.</p>
     *
     * @param compilationUnit the parsed AST with type bindings resolved
     * @param context         the semantic context for the compilation unit
     * @return an immutable list of independent PatchUnit objects, ordered by source position
     */
    public List<PatchUnit> scan(CompilationUnit compilationUnit, SemanticContext context) {
        Objects.requireNonNull(compilationUnit, "compilationUnit must not be null");
        Objects.requireNonNull(context, "context must not be null");

        String sourceFile = context.getSourceFilePath();
        log.info("Starting scan of '{}' with {} registered rules", sourceFile, rules.size());

        // Phase 1: Collect all candidates from all rules
        List<RefactorCandidate> allCandidates = collectCandidates(compilationUnit, context);
        log.info("Phase 1 complete: found {} raw candidates in '{}'", allCandidates.size(), sourceFile);

        // Phase 2: Filter candidates by invariants, confidence, and risk
        List<RefactorCandidate> validCandidates = filterCandidates(allCandidates);
        log.info("Phase 2 complete: {} candidates passed filtering", validCandidates.size());

        // Phase 3: Resolve overlapping candidates (keep highest confidence)
        List<RefactorCandidate> resolvedCandidates = resolveOverlaps(validCandidates);
        log.info("Phase 3 complete: {} candidates after overlap resolution", resolvedCandidates.size());

        // Phase 4: Apply rules to generate patch units
        List<PatchUnit> patches = generatePatches(resolvedCandidates);
        log.info("Phase 4 complete: generated {} patches for '{}'", patches.size(), sourceFile);

        // Phase 5: Final validation — ensure no patches overlap
        validatePatchIndependence(patches);

        return ImmutableList.copyOf(patches);
    }

    /**
     * Phase 1: Walk the AST and collect candidates from all rules.
     */
    private List<RefactorCandidate> collectCandidates(CompilationUnit cu, SemanticContext context) {
        List<RefactorCandidate> candidates = new ArrayList<>();

        cu.accept(new ASTVisitor() {
            @Override
            public void preVisit(ASTNode node) {
                for (RefactorRule rule : rules) {
                    try {
                        if (rule.appliesTo(node, context)) {
                            log.debug("Rule '{}' matched node at position {} (type: {})",
                                    rule.ruleId(), node.getStartPosition(),
                                    ASTNode.nodeClassForType(node.getNodeType()).getSimpleName());

                            RefactorCandidate candidate = rule.analyze(node, context);
                            if (candidate != null) {
                                candidates.add(candidate);
                                log.debug("Rule '{}' produced candidate: confidence={}, invariantsOk={}",
                                        rule.ruleId(), candidate.getConfidenceScore(),
                                        candidate.allInvariantsVerified());
                            }
                        }
                    } catch (Exception e) {
                        log.warn("Rule '{}' threw exception analyzing node at position {}: {}",
                                rule.ruleId(), node.getStartPosition(), e.getMessage(), e);
                    }
                }
            }
        });

        return candidates;
    }

    /**
     * Phase 2: Filter candidates based on invariants, confidence threshold, and risk tier.
     */
    private List<RefactorCandidate> filterCandidates(List<RefactorCandidate> candidates) {
        List<RefactorCandidate> valid = new ArrayList<>();

        for (RefactorCandidate candidate : candidates) {
            // Check invariants
            if (!candidate.allInvariantsVerified()) {
                List<SafetyInvariant> violations = candidate.getViolatedInvariants();
                log.info("Rejecting candidate {} (rule '{}'): {} invariant(s) violated: {}",
                        candidate.getCandidateId(), candidate.getRuleId(),
                        violations.size(), violations);
                continue;
            }

            // Check confidence threshold
            if (candidate.getConfidenceScore() < minimumConfidenceThreshold) {
                log.info("Rejecting candidate {} (rule '{}'): confidence {:.3f} below threshold {:.3f}",
                        candidate.getCandidateId(), candidate.getRuleId(),
                        candidate.getConfidenceScore(), minimumConfidenceThreshold);
                continue;
            }

            // Check risk tier
            if (candidate.getRiskTier().isAtOrAbove(maximumRiskTier)
                    && candidate.getRiskTier() != maximumRiskTier) {
                log.info("Rejecting candidate {} (rule '{}'): risk tier {} exceeds maximum {}",
                        candidate.getCandidateId(), candidate.getRuleId(),
                        candidate.getRiskTier(), maximumRiskTier);
                continue;
            }

            valid.add(candidate);
        }

        return valid;
    }

    /**
     * Phase 3: Resolve overlapping candidates by keeping the one with highest confidence.
     *
     * <p>Uses a greedy algorithm: sort by confidence descending, then accept candidates
     * that don't overlap with any already-accepted candidate.</p>
     */
    private List<RefactorCandidate> resolveOverlaps(List<RefactorCandidate> candidates) {
        // Sort by confidence descending (higher confidence = higher priority)
        List<RefactorCandidate> sorted = new ArrayList<>(candidates);
        sorted.sort(Comparator.comparingDouble(RefactorCandidate::getConfidenceScore).reversed());

        List<RefactorCandidate> accepted = new ArrayList<>();
        for (RefactorCandidate candidate : sorted) {
            boolean overlaps = accepted.stream().anyMatch(a -> a.overlaps(candidate));
            if (overlaps) {
                log.debug("Discarding overlapping candidate {} (rule '{}', lines {}-{}, confidence={})",
                        candidate.getCandidateId(), candidate.getRuleId(),
                        candidate.getStartLine(), candidate.getEndLine(),
                        candidate.getConfidenceScore());
            } else {
                accepted.add(candidate);
            }
        }

        // Re-sort by source position for deterministic output ordering
        accepted.sort(Comparator.comparingInt(RefactorCandidate::getStartLine));
        return accepted;
    }

    /**
     * Phase 4: Apply rules to candidates to generate PatchUnit objects.
     */
    private List<PatchUnit> generatePatches(List<RefactorCandidate> candidates) {
        List<PatchUnit> patches = new ArrayList<>();

        for (RefactorCandidate candidate : candidates) {
            RefactorRule rule = ruleIndex.get(candidate.getRuleId());
            if (rule == null) {
                log.error("No rule found for candidate {} with ruleId '{}'",
                        candidate.getCandidateId(), candidate.getRuleId());
                continue;
            }

            try {
                PatchUnit patch = rule.apply(candidate);
                if (patch != null) {
                    patches.add(patch);
                    log.info("Generated patch {}: rule='{}', file='{}', lines={}-{}, confidence={}",
                            patch.getPatchId(), patch.getRuleId(), patch.getSourceFile(),
                            patch.getStartLine(), patch.getEndLine(), patch.getConfidenceScore());
                }
            } catch (Exception e) {
                log.error("Failed to apply rule '{}' to candidate {}: {}",
                        rule.ruleId(), candidate.getCandidateId(), e.getMessage(), e);
            }
        }

        return patches;
    }

    /**
     * Phase 5: Validate that all generated patches are independent (non-overlapping).
     *
     * @throws IllegalStateException if overlapping patches are detected
     */
    private void validatePatchIndependence(List<PatchUnit> patches) {
        for (int i = 0; i < patches.size(); i++) {
            for (int j = i + 1; j < patches.size(); j++) {
                PatchUnit a = patches.get(i);
                PatchUnit b = patches.get(j);
                if (a.overlapsWith(b)) {
                    log.error("INVARIANT VIOLATION: Overlapping patches detected! " +
                                    "Patch {} (lines {}-{}) overlaps with Patch {} (lines {}-{}) in '{}'",
                            a.getPatchId(), a.getStartLine(), a.getEndLine(),
                            b.getPatchId(), b.getStartLine(), b.getEndLine(),
                            a.getSourceFile());
                    throw new IllegalStateException(
                            "Overlapping patches detected: %s and %s".formatted(a.getPatchId(), b.getPatchId()));
                }
            }
        }
        log.debug("Patch independence validation passed for {} patches", patches.size());
    }

    /**
     * Scans and returns only the candidates (without applying patches).
     * Useful for preview/dry-run mode.
     *
     * @param compilationUnit the parsed AST
     * @param context         the semantic context
     * @return list of analyzed candidates with their invariant results
     */
    public List<RefactorCandidate> preview(CompilationUnit compilationUnit, SemanticContext context) {
        Objects.requireNonNull(compilationUnit, "compilationUnit must not be null");
        Objects.requireNonNull(context, "context must not be null");

        log.info("Preview scan of '{}' with {} registered rules",
                context.getSourceFilePath(), rules.size());

        List<RefactorCandidate> candidates = collectCandidates(compilationUnit, context);
        List<RefactorCandidate> filtered = filterCandidates(candidates);
        List<RefactorCandidate> resolved = resolveOverlaps(filtered);

        log.info("Preview complete: {} candidates (raw={}, filtered={}, resolved={})",
                resolved.size(), candidates.size(), filtered.size(), resolved.size());
        return ImmutableList.copyOf(resolved);
    }

    /**
     * Returns statistics about the last scan operation.
     */
    public ScanStatistics getStatistics(List<PatchUnit> patches) {
        if (patches.isEmpty()) {
            return new ScanStatistics(0, 0, 0.0, RiskTier.COSMETIC, Map.of());
        }

        Map<String, Integer> byRule = new LinkedHashMap<>();
        double totalConfidence = 0.0;
        RiskTier maxRisk = RiskTier.COSMETIC;

        for (PatchUnit patch : patches) {
            byRule.merge(patch.getRuleId(), 1, Integer::sum);
            totalConfidence += patch.getConfidenceScore();
            maxRisk = RiskTier.max(maxRisk, patch.getRiskTier());
        }

        double avgConfidence = totalConfidence / patches.size();
        return new ScanStatistics(patches.size(), byRule.size(), avgConfidence, maxRisk, byRule);
    }

    /**
     * Summary statistics for a scan operation.
     */
    public record ScanStatistics(
            int totalPatches,
            int rulesTriggered,
            double averageConfidence,
            RiskTier highestRisk,
            Map<String, Integer> patchesByRule
    ) {}
}
