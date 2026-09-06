package com.shadowstack.refactor.rules;

import com.shadowstack.refactor.RefactorRule;
import com.shadowstack.refactor.model.*;
import org.eclipse.jdt.core.dom.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/** Collections.unmodifiableList/Set/Map → List/Set/Map.copyOf (Java 10+). */
public class UnmodifiableToCopyOfRule implements RefactorRule {
    private static final String RULE_ID = "UNMODIFIABLE_TO_COPYOF";
    private static final Map<String, String> MAP = Map.of(
            "unmodifiableList", "java.util.List.copyOf",
            "unmodifiableSet", "java.util.Set.copyOf",
            "unmodifiableMap", "java.util.Map.copyOf");

    @Override public String ruleId() { return RULE_ID; }
    @Override public String ruleName() { return "Collections.unmodifiable* → *.copyOf"; }
    @Override public String description() {
        return "Replaces Collections.unmodifiableList/Set/Map with List/Set/Map.copyOf.";
    }
    @Override public RiskTier defaultRiskTier() { return RiskTier.LOW; }

    @Override
    public boolean appliesTo(ASTNode node, SemanticContext context) {
        if (!(node instanceof MethodInvocation inv)) return false;
        if (!MAP.containsKey(inv.getName().getIdentifier())) return false;
        Expression expr = inv.getExpression();
        if (expr == null) return false;
        String recv = expr.toString();
        if (!"Collections".equals(recv) && !"java.util.Collections".equals(recv)) return false;
        @SuppressWarnings("unchecked")
        List<Expression> args = inv.arguments();
        return args != null && args.size() == 1;
    }

    @Override
    public RefactorCandidate analyze(ASTNode node, SemanticContext context) {
        MethodInvocation inv = (MethodInvocation) node;
        @SuppressWarnings("unchecked")
        List<Expression> args = inv.arguments();
        String proposed = MAP.get(inv.getName().getIdentifier()) + "(" + args.get(0) + ")";
        String original = context.getSourceRange(node.getStartPosition(), node.getLength());
        int startLine = context.getLineNumber(node.getStartPosition());
        List<SafetyInvariant> invariants = new ArrayList<>();
        invariants.add(SafetyInvariant.verified("copy_of",
                "copyOf creates an unmodifiable defensive copy", proposed));
        return RefactorCandidate.builder(RULE_ID, context.getSourceFilePath())
                .startLine(startLine).endLine(startLine)
                .startPosition(node.getStartPosition()).length(node.getLength())
                .originalSnippet(original).proposedSnippet(proposed)
                .confidenceScore(0.9).riskTier(RiskTier.LOW)
                .invariants(invariants)
                .analysisMetadata(Map.of("method", inv.getName().getIdentifier()))
                .rationale("Prefer copyOf over Collections.unmodifiable*")
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
