package com.shadowstack.refactor.rules;

import com.shadowstack.refactor.RefactorRule;
import com.shadowstack.refactor.model.*;
import org.eclipse.jdt.core.dom.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/** Guava ImmutableSet.of → java.util.Set.of. */
public class GuavaImmutableSetOfRule implements RefactorRule {
    private static final String RULE_ID = "GUAVA_IMMUTABLESET_TO_SETOF";
    @Override public String ruleId() { return RULE_ID; }
    @Override public String ruleName() { return "ImmutableSet.of → Set.of"; }
    @Override public String description() {
        return "Replaces Guava ImmutableSet.of(...) with java.util.Set.of(...).";
    }
    @Override public RiskTier defaultRiskTier() { return RiskTier.LOW; }

    @Override
    public boolean appliesTo(ASTNode node, SemanticContext context) {
        if (!(node instanceof MethodInvocation inv)) return false;
        if (!"of".equals(inv.getName().getIdentifier())) return false;
        Expression expr = inv.getExpression();
        if (expr == null) return false;
        String recv = expr.toString();
        return "ImmutableSet".equals(recv)
                || recv.endsWith(".ImmutableSet")
                || ("com.google.common.collect.ImmutableSet".equals(recv));
    }

    @Override
    public RefactorCandidate analyze(ASTNode node, SemanticContext context) {
        MethodInvocation inv = (MethodInvocation) node;
        @SuppressWarnings("unchecked")
        List<Expression> args = inv.arguments();
        String joined = args == null ? "" : String.join(", ", args.stream().map(Object::toString).toList());
        String original = context.getSourceRange(node.getStartPosition(), node.getLength());
        String proposed = "java.util.Set.of(" + joined + ")";
        int startLine = context.getLineNumber(node.getStartPosition());
        List<SafetyInvariant> invariants = new ArrayList<>();
        invariants.add(SafetyInvariant.verified("guava_to_jdk",
                "ImmutableSet.of → Set.of for immutable literals", proposed));
        return RefactorCandidate.builder(RULE_ID, context.getSourceFilePath())
                .startLine(startLine).endLine(startLine)
                .startPosition(node.getStartPosition()).length(node.getLength())
                .originalSnippet(original).proposedSnippet(proposed)
                .confidenceScore(0.85).riskTier(RiskTier.LOW)
                .invariants(invariants)
                .analysisMetadata(Map.of("type", "ImmutableSet"))
                .rationale("Prefer JDK Set.of over Guava ImmutableSet.of")
                .astNode(node).build();
    }

    @Override
    public PatchUnit apply(RefactorCandidate candidate) {
        if (!candidate.allInvariantsVerified()) throw new IllegalArgumentException("Cannot apply " + RULE_ID);
        return PatchUnit.builder(RULE_ID, candidate.getSourceFile())
                .startLine(candidate.getStartLine()).endLine(candidate.getEndLine())
                .beforeSnippet(candidate.getOriginalSnippet())
                .afterSnippet(candidate.getProposedSnippet())
                .unifiedDiff(PatchUnit.computeUnifiedDiff(
                        Arrays.asList(candidate.getOriginalSnippet().split("\n", -1)),
                        Arrays.asList(candidate.getProposedSnippet().split("\n", -1)),
                        candidate.getSourceFile(), candidate.getStartLine()))
                .confidenceScore(candidate.getConfidenceScore())
                .riskTier(candidate.getRiskTier())
                .invariants(candidate.getInvariants())
                .metadata(candidate.getAnalysisMetadata())
                .rationale(candidate.getRationale())
                .build();
    }

}
