package com.shadowstack.refactor.rules;

import com.shadowstack.refactor.RefactorRule;
import com.shadowstack.refactor.model.*;
import org.eclipse.jdt.core.dom.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/** javax.inject → jakarta.inject package migration (Jakarta EE). */
public class JavaxToJakartaInjectRule implements RefactorRule {
    private static final String RULE_ID = "JAVAX_INJECT_TO_JAKARTA";
    private static final String FROM = "javax.inject";
    private static final String TO = "jakarta.inject";

    @Override public String ruleId() { return RULE_ID; }
    @Override public String ruleName() { return "javax.inject → jakarta.inject"; }
    @Override public String description() {
        return "Migrates imports/types from javax.inject to jakarta.inject.";
    }
    @Override public RiskTier defaultRiskTier() { return RiskTier.MEDIUM; }

    @Override
    public boolean appliesTo(ASTNode node, SemanticContext context) {
        if (node instanceof ImportDeclaration imp) {
            String q = imp.getName().getFullyQualifiedName();
            return q.equals(FROM) || q.startsWith(FROM + ".");
        }
        if (node instanceof QualifiedName qn) {
            ASTNode parent = qn.getParent();
            if (parent instanceof QualifiedName || parent instanceof ImportDeclaration) return false;
            String q = qn.getFullyQualifiedName();
            return q.equals(FROM) || q.startsWith(FROM + ".");
        }
        return false;
    }

    @Override
    public RefactorCandidate analyze(ASTNode node, SemanticContext context) {
        String original = context.getSourceRange(node.getStartPosition(), node.getLength());
        String proposed = original.replace(FROM, TO);
        int startLine = context.getLineNumber(node.getStartPosition());
        List<SafetyInvariant> invariants = new ArrayList<>();
        invariants.add(SafetyInvariant.verified("jakarta_namespace",
                FROM + " → " + TO + " namespace migration", proposed));
        if (proposed.equals(original)) {
            invariants.add(SafetyInvariant.violated("rewrite_possible", "Could not rewrite package", original));
        }
        return RefactorCandidate.builder(RULE_ID, context.getSourceFilePath())
                .startLine(startLine).endLine(startLine)
                .startPosition(node.getStartPosition()).length(node.getLength())
                .originalSnippet(original).proposedSnippet(proposed)
                .confidenceScore(0.9).riskTier(RiskTier.MEDIUM)
                .invariants(invariants)
                .analysisMetadata(Map.of("from", FROM, "to", TO))
                .rationale("Prefer Jakarta EE namespace over javax")
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
