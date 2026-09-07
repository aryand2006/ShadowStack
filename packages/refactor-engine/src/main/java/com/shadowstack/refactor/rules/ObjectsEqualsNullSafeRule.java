package com.shadowstack.refactor.rules;

import com.shadowstack.refactor.RefactorRule;
import com.shadowstack.refactor.model.*;
import org.eclipse.jdt.core.dom.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/** x.equals(y) → Objects.equals(x, y) when left is a simple name (null-safe). */
public class ObjectsEqualsNullSafeRule implements RefactorRule {
    private static final String RULE_ID = "OBJECTS_EQUALS_NULL_SAFE";
    @Override public String ruleId() { return RULE_ID; }
    @Override public String ruleName() { return "equals → Objects.equals"; }
    @Override public String description() {
        return "Replaces receiver.equals(arg) with Objects.equals when receiver is a simple name.";
    }
    @Override public RiskTier defaultRiskTier() { return RiskTier.LOW; }

    @Override
    public boolean appliesTo(ASTNode node, SemanticContext context) {
        if (!(node instanceof MethodInvocation inv)) return false;
        if (!"equals".equals(inv.getName().getIdentifier())) return false;
        Expression expr = inv.getExpression();
        if (!(expr instanceof SimpleName)) return false;
        String recv = ((SimpleName) expr).getIdentifier();
        if ("Objects".equals(recv)) return false;
        @SuppressWarnings("unchecked")
        List<Expression> args = inv.arguments();
        // Prefer STRING_EQUALS_LITERAL_FIRST when the argument is a string literal.
        return args != null && args.size() == 1 && !(args.get(0) instanceof StringLiteral);
    }

    @Override
    public RefactorCandidate analyze(ASTNode node, SemanticContext context) {
        MethodInvocation inv = (MethodInvocation) node;
        @SuppressWarnings("unchecked")
        List<Expression> args = inv.arguments();
        String left = inv.getExpression().toString();
        String right = args.get(0).toString();
        String original = context.getSourceRange(node.getStartPosition(), node.getLength());
        String proposed = "java.util.Objects.equals(" + left + ", " + right + ")";
        int startLine = context.getLineNumber(node.getStartPosition());
        List<SafetyInvariant> invariants = new ArrayList<>();
        invariants.add(SafetyInvariant.verified("null_safe_objects_equals",
                "Objects.equals is null-safe for both sides", proposed));
        return RefactorCandidate.builder(RULE_ID, context.getSourceFilePath())
                .startLine(startLine).endLine(startLine)
                .startPosition(node.getStartPosition()).length(node.getLength())
                .originalSnippet(original).proposedSnippet(proposed)
                .confidenceScore(0.78).riskTier(RiskTier.LOW)
                .invariants(invariants)
                .analysisMetadata(Map.of("left", left))
                .rationale("Prefer Objects.equals for null-safe equality")
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
