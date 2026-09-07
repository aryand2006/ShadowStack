package com.shadowstack.refactor.rules;

import com.shadowstack.refactor.RefactorRule;
import com.shadowstack.refactor.model.*;
import org.eclipse.jdt.core.dom.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/** org.junit.Assert → org.junit.jupiter.api.Assertions (JUnit 4 → 5). */
public class JUnit4AssertToJupiterRule implements RefactorRule {
    private static final String RULE_ID = "JUNIT4_ASSERT_TO_JUPITER";
    private static final String FROM = "org.junit.Assert";
    private static final String TO = "org.junit.jupiter.api.Assertions";

    @Override public String ruleId() { return RULE_ID; }
    @Override public String ruleName() { return "JUnit4 Assert → Jupiter Assertions"; }
    @Override public String description() {
        return "Migrates org.junit.Assert imports/receivers to org.junit.jupiter.api.Assertions.";
    }
    @Override public RiskTier defaultRiskTier() { return RiskTier.LOW; }

    @Override
    public boolean appliesTo(ASTNode node, SemanticContext context) {
        if (node instanceof ImportDeclaration imp) {
            String q = imp.getName().getFullyQualifiedName();
            return q.equals(FROM) || q.startsWith(FROM + ".");
        }
        if (node instanceof MethodInvocation inv) {
            Expression expr = inv.getExpression();
            if (expr == null) return false;
            String recv = expr.toString();
            return "Assert".equals(recv) || FROM.equals(recv);
        }
        if (node instanceof QualifiedName qn) {
            if (qn.getParent() instanceof QualifiedName || qn.getParent() instanceof ImportDeclaration) {
                return false;
            }
            String q = qn.getFullyQualifiedName();
            return q.equals(FROM) || q.startsWith(FROM + ".");
        }
        return false;
    }

    @Override
    public RefactorCandidate analyze(ASTNode node, SemanticContext context) {
        String original = context.getSourceRange(node.getStartPosition(), node.getLength());
        String proposed;
        if (node instanceof MethodInvocation inv) {
            Expression expr = inv.getExpression();
            String recv = expr.toString();
            String replacement = FROM.equals(recv) ? TO : "Assertions";
            proposed = original.replaceFirst(java.util.regex.Pattern.quote(recv), replacement);
        } else if (original.contains(FROM)) {
            proposed = original.replace(FROM, TO);
        } else {
            proposed = original.replace("Assert", "Assertions");
        }
        int startLine = context.getLineNumber(node.getStartPosition());
        List<SafetyInvariant> invariants = new ArrayList<>();
        invariants.add(SafetyInvariant.verified("junit5_assertions",
                "JUnit Jupiter Assertions replace JUnit 4 Assert", proposed));
        return RefactorCandidate.builder(RULE_ID, context.getSourceFilePath())
                .startLine(startLine).endLine(startLine)
                .startPosition(node.getStartPosition()).length(node.getLength())
                .originalSnippet(original).proposedSnippet(proposed)
                .confidenceScore(0.88).riskTier(RiskTier.LOW)
                .invariants(invariants)
                .analysisMetadata(Map.of("from", FROM, "to", TO))
                .rationale("Prefer JUnit Jupiter Assertions")
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
