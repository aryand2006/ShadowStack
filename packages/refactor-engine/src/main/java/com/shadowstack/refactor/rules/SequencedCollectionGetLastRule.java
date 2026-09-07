package com.shadowstack.refactor.rules;

import com.shadowstack.refactor.RefactorRule;
import com.shadowstack.refactor.model.*;
import org.eclipse.jdt.core.dom.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/** list.get(list.size()-1) → list.getLast() (Java 21 SequencedCollection). */
public class SequencedCollectionGetLastRule implements RefactorRule {
    private static final String RULE_ID = "SEQUENCED_GET_LAST";
    @Override public String ruleId() { return RULE_ID; }
    @Override public String ruleName() { return "list.get(size()-1) → getLast()"; }
    @Override public String description() {
        return "Replaces list.get(list.size()-1) with list.getLast() (Java 21 SequencedCollection).";
    }
    @Override public RiskTier defaultRiskTier() { return RiskTier.MEDIUM; }

    @Override
    public boolean appliesTo(ASTNode node, SemanticContext context) {
        return parse(node) != null;
    }

    @Override
    public RefactorCandidate analyze(ASTNode node, SemanticContext context) {
        String receiver = parse(node);
        String original = context.getSourceRange(node.getStartPosition(), node.getLength());
        String proposed = receiver + ".getLast()";
        int startLine = context.getLineNumber(node.getStartPosition());
        List<SafetyInvariant> invariants = new ArrayList<>();
        invariants.add(SafetyInvariant.verified("sequenced_get_last",
                "getLast() requires SequencedCollection (Java 21); NoSuchElementException if empty", proposed));
        return RefactorCandidate.builder(RULE_ID, context.getSourceFilePath())
                .startLine(startLine).endLine(startLine)
                .startPosition(node.getStartPosition()).length(node.getLength())
                .originalSnippet(original).proposedSnippet(proposed)
                .confidenceScore(0.72).riskTier(RiskTier.MEDIUM)
                .invariants(invariants)
                .analysisMetadata(Map.of("receiver", receiver))
                .rationale("Prefer SequencedCollection.getLast() (Java 21)")
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

    private static String parse(ASTNode node) {
        if (!(node instanceof MethodInvocation inv)) return null;
        if (!"get".equals(inv.getName().getIdentifier())) return null;
        if (inv.getExpression() == null) return null;
        @SuppressWarnings("unchecked")
        List<Expression> args = inv.arguments();
        if (args == null || args.size() != 1) return null;
        Expression arg = args.get(0);
        if (!(arg instanceof InfixExpression infix)) return null;
        if (infix.getOperator() != InfixExpression.Operator.MINUS) return null;
        if (!(infix.getRightOperand() instanceof NumberLiteral lit) || !"1".equals(lit.getToken())) {
            return null;
        }
        Expression left = infix.getLeftOperand();
        if (!(left instanceof MethodInvocation sizeCall)) return null;
        if (!"size".equals(sizeCall.getName().getIdentifier())) return null;
        @SuppressWarnings("unchecked")
        List<Expression> sizeArgs = sizeCall.arguments();
        if (sizeArgs != null && !sizeArgs.isEmpty()) return null;
        if (sizeCall.getExpression() == null) return null;
        String listRecv = inv.getExpression().toString();
        String sizeRecv = sizeCall.getExpression().toString();
        if (!listRecv.equals(sizeRecv)) return null;
        return listRecv;
    }
}
