package com.shadowstack.refactor.rules;

import com.shadowstack.refactor.RefactorRule;
import com.shadowstack.refactor.model.*;
import org.eclipse.jdt.core.dom.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * {@code Collections.EMPTY_LIST} → {@code Collections.emptyList()} (and SET/MAP)
 * (Sonar S1596 / OpenRewrite).
 */
public class CollectionsEmptyConstantRule implements RefactorRule {

    private static final String RULE_ID = "COLLECTIONS_EMPTY_CONSTANT";
    private static final Map<String, String> REPLACEMENTS = Map.of(
            "EMPTY_LIST", "emptyList()",
            "EMPTY_SET", "emptySet()",
            "EMPTY_MAP", "emptyMap()");

    @Override
    public String ruleId() {
        return RULE_ID;
    }

    @Override
    public String ruleName() {
        return "Collections.EMPTY_* → empty*()";
    }

    @Override
    public String description() {
        return "Replaces raw Collections.EMPTY_LIST/SET/MAP fields with typed empty*() factories.";
    }

    @Override
    public RiskTier defaultRiskTier() {
        return RiskTier.LOW;
    }

    @Override
    public boolean appliesTo(ASTNode node, SemanticContext context) {
        return resolve(node) != null;
    }

    @Override
    public RefactorCandidate analyze(ASTNode node, SemanticContext context) {
        Resolved r = resolve(node);
        String original = context.getSourceRange(node.getStartPosition(), node.getLength());
        String proposed = r.qualifier + "." + r.replacement;
        int startLine = context.getLineNumber(node.getStartPosition());
        List<SafetyInvariant> invariants = new ArrayList<>();
        invariants.add(SafetyInvariant.verified(
                "empty_factory_typed",
                "empty*() returns a typed immutable empty collection",
                proposed));
        return RefactorCandidate.builder(RULE_ID, context.getSourceFilePath())
                .startLine(startLine)
                .endLine(startLine)
                .startPosition(node.getStartPosition())
                .length(node.getLength())
                .originalSnippet(original)
                .proposedSnippet(proposed)
                .confidenceScore(0.96)
                .riskTier(RiskTier.LOW)
                .invariants(invariants)
                .analysisMetadata(Map.of("field", r.field))
                .rationale("Prefer Collections.empty*() over raw EMPTY_* constants")
                .astNode(node)
                .build();
    }

    @Override
    public PatchUnit apply(RefactorCandidate candidate) {
        if (!candidate.allInvariantsVerified()) {
            throw new IllegalArgumentException("Cannot apply Collections.EMPTY_* rule");
        }
        return PatchUnit.builder(RULE_ID, candidate.getSourceFile())
                .startLine(candidate.getStartLine())
                .endLine(candidate.getEndLine())
                .beforeSnippet(candidate.getOriginalSnippet())
                .afterSnippet(candidate.getProposedSnippet())
                .unifiedDiff(PatchUnit.computeUnifiedDiff(
                        Arrays.asList(candidate.getOriginalSnippet().split("\n", -1)),
                        Arrays.asList(candidate.getProposedSnippet().split("\n", -1)),
                        candidate.getSourceFile(),
                        candidate.getStartLine()))
                .confidenceScore(candidate.getConfidenceScore())
                .riskTier(candidate.getRiskTier())
                .invariants(candidate.getInvariants())
                .metadata(candidate.getAnalysisMetadata())
                .rationale(candidate.getRationale())
                .build();
    }

    private record Resolved(String qualifier, String field, String replacement) {}

    private static Resolved resolve(ASTNode node) {
        if (!(node instanceof QualifiedName qn)) {
            return null;
        }
        String field = qn.getName().getIdentifier();
        String replacement = REPLACEMENTS.get(field);
        if (replacement == null) {
            return null;
        }
        String qualifier = qn.getQualifier().getFullyQualifiedName();
        if (!"Collections".equals(qualifier) && !"java.util.Collections".equals(qualifier)) {
            return null;
        }
        return new Resolved(qualifier.contains(".") ? "Collections" : qualifier, field, replacement);
    }
}
