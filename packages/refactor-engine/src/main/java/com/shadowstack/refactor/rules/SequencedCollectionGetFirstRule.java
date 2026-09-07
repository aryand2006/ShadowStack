package com.shadowstack.refactor.rules;

import com.shadowstack.refactor.RefactorRule;
import com.shadowstack.refactor.model.*;
import org.eclipse.jdt.core.dom.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/** list.get(0) → list.getFirst() (Java 21 SequencedCollection). */
public class SequencedCollectionGetFirstRule implements RefactorRule {
    private static final String RULE_ID = "SEQUENCED_GET_FIRST";
    @Override public String ruleId() { return RULE_ID; }
    @Override public String ruleName() { return "list.get(0) → getFirst()"; }
    @Override public String description() {
        return "Replaces list.get(0) with list.getFirst() (Java 21 SequencedCollection).";
    }
    @Override public RiskTier defaultRiskTier() { return RiskTier.MEDIUM; }

    @Override
    public boolean appliesTo(ASTNode node, SemanticContext context) {
        if (!(node instanceof MethodInvocation inv)) return false;
        if (!"get".equals(inv.getName().getIdentifier())) return false;
        if (inv.getExpression() == null) return false;
        @SuppressWarnings("unchecked")
        List<Expression> args = inv.arguments();
        return args != null && args.size() == 1
                && args.get(0) instanceof NumberLiteral lit
                && "0".equals(lit.getToken());
    }

    @Override
    public RefactorCandidate analyze(ASTNode node, SemanticContext context) {
        MethodInvocation inv = (MethodInvocation) node;
        String receiver = inv.getExpression().toString();
        String original = context.getSourceRange(node.getStartPosition(), node.getLength());
        String proposed = receiver + ".getFirst()";
        int startLine = context.getLineNumber(node.getStartPosition());
        List<SafetyInvariant> invariants = new ArrayList<>();
        invariants.add(SafetyInvariant.verified("sequenced_get_first",
                "getFirst() requires SequencedCollection (Java 21); NoSuchElementException if empty", proposed));
        return RefactorCandidate.builder(RULE_ID, context.getSourceFilePath())
                .startLine(startLine).endLine(startLine)
                .startPosition(node.getStartPosition()).length(node.getLength())
                .originalSnippet(original).proposedSnippet(proposed)
                .confidenceScore(0.7).riskTier(RiskTier.MEDIUM)
                .invariants(invariants)
                .analysisMetadata(Map.of("receiver", receiver))
                .rationale("Prefer SequencedCollection.getFirst() (Java 21)")
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
