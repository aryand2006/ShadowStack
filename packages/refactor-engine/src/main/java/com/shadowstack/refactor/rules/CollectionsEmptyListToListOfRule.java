package com.shadowstack.refactor.rules;

import com.shadowstack.refactor.RefactorRule;
import com.shadowstack.refactor.model.*;
import org.eclipse.jdt.core.dom.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Replaces Collections.emptyList() with List.of() (Java 9). */
public class CollectionsEmptyListToListOfRule implements RefactorRule {
    private static final String RULE_ID = "COLLECTIONS_EMPTYLIST_TO_LISTOF";
    @Override public String ruleId() { return RULE_ID; }
    @Override public String ruleName() { return "Collections.emptyList → List.of"; }
    @Override public String description() { return "Replaces Collections.emptyList() with List.of() (Java 9)."; }
    @Override public RiskTier defaultRiskTier() { return RiskTier.COSMETIC; }

    @Override
    public boolean appliesTo(ASTNode node, SemanticContext context) {
        if (!(node instanceof MethodInvocation inv)) return false;
        String name = inv.getName().getIdentifier();
        if (!Set.of("emptyList").contains(name)) return false;
        Expression expr = inv.getExpression();
        if (expr == null) return false;
        String recv = expr.toString();
        if (!("Collections".equals(recv) || "java.util.Collections".equals(recv))) return false;
        @SuppressWarnings("unchecked")
        List<Expression> args = inv.arguments();
        return args == null || args.isEmpty();
    }

    @Override
    public RefactorCandidate analyze(ASTNode node, SemanticContext context) {
        MethodInvocation inv = (MethodInvocation) node;
        String name = inv.getName().getIdentifier();
        String recv = inv.getExpression().toString();
        @SuppressWarnings("unchecked")
        List<Expression> args = inv.arguments();
        String joined = args == null ? "" : String.join(", ", args.stream().map(Object::toString).toList());
        String original = context.getSourceRange(node.getStartPosition(), node.getLength());
        String proposed = "java.util.List.of()";
        int startLine = context.getLineNumber(node.getStartPosition());
        List<SafetyInvariant> invariants = new ArrayList<>();
        invariants.add(SafetyInvariant.verified("list_of_empty", "List.of() is the modern empty immutable list", proposed));
        return RefactorCandidate.builder(RULE_ID, context.getSourceFilePath())
                .startLine(startLine).endLine(startLine)
                .startPosition(node.getStartPosition()).length(node.getLength())
                .originalSnippet(original).proposedSnippet(proposed)
                .confidenceScore(0.92).riskTier(RiskTier.COSMETIC)
                .invariants(invariants)
                .analysisMetadata(Map.of("method", name, "receiver", recv))
                .rationale("Prefer List.of() for empty immutable lists")
                .astNode(node).build();
    }

    @Override
    public PatchUnit apply(RefactorCandidate candidate) {
        if (!candidate.allInvariantsVerified()) {
            throw new IllegalArgumentException("Cannot apply " + RULE_ID);
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
}

