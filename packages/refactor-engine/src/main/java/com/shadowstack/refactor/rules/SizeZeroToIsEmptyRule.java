package com.shadowstack.refactor.rules;

import com.shadowstack.refactor.RefactorRule;
import com.shadowstack.refactor.model.*;
import org.eclipse.jdt.core.dom.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * {@code collection.size() == 0} → {@code collection.isEmpty()} (Sonar S1155).
 */
public class SizeZeroToIsEmptyRule implements RefactorRule {

    private static final String RULE_ID = "SIZE_ZERO_TO_ISEMPTY";

    @Override
    public String ruleId() {
        return RULE_ID;
    }

    @Override
    public String ruleName() {
        return "size()==0 → isEmpty()";
    }

    @Override
    public String description() {
        return "Replaces collection.size() == 0 (or != 0) with isEmpty() / !isEmpty().";
    }

    @Override
    public RiskTier defaultRiskTier() {
        return RiskTier.COSMETIC;
    }

    @Override
    public boolean appliesTo(ASTNode node, SemanticContext context) {
        if (!(node instanceof InfixExpression infix)) {
            return false;
        }
        InfixExpression.Operator op = infix.getOperator();
        if (op != InfixExpression.Operator.EQUALS && op != InfixExpression.Operator.NOT_EQUALS) {
            return false;
        }
        return sizeZeroPair(infix.getLeftOperand(), infix.getRightOperand())
                || sizeZeroPair(infix.getRightOperand(), infix.getLeftOperand());
    }

    @Override
    public RefactorCandidate analyze(ASTNode node, SemanticContext context) {
        InfixExpression infix = (InfixExpression) node;
        boolean equals = infix.getOperator() == InfixExpression.Operator.EQUALS;
        MethodInvocation sizeCall = extractSizeCall(infix.getLeftOperand());
        if (sizeCall == null) {
            sizeCall = extractSizeCall(infix.getRightOperand());
        }
        String receiver = sizeCall.getExpression() != null
                ? sizeCall.getExpression().toString()
                : "this";
        String original = context.getSourceRange(node.getStartPosition(), node.getLength());
        String proposed = equals ? receiver + ".isEmpty()" : "!" + receiver + ".isEmpty()";
        int startLine = context.getLineNumber(node.getStartPosition());

        List<SafetyInvariant> invariants = new ArrayList<>();
        invariants.add(SafetyInvariant.verified(
                "isempty_equivalent",
                "For Collection/String/Map, isEmpty() is equivalent to size()==0",
                "Prefer isEmpty for readability"));

        return RefactorCandidate.builder(RULE_ID, context.getSourceFilePath())
                .startLine(startLine)
                .endLine(startLine)
                .startPosition(node.getStartPosition())
                .length(node.getLength())
                .originalSnippet(original)
                .proposedSnippet(proposed)
                .confidenceScore(0.9)
                .riskTier(RiskTier.COSMETIC)
                .invariants(invariants)
                .analysisMetadata(Map.of("receiver", receiver))
                .rationale("Prefer isEmpty() over size() == 0")
                .astNode(node)
                .build();
    }

    @Override
    public PatchUnit apply(RefactorCandidate candidate) {
        if (!candidate.allInvariantsVerified()) {
            throw new IllegalArgumentException("Cannot apply isEmpty rule");
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

    private static boolean sizeZeroPair(Expression maybeSize, Expression maybeZero) {
        return extractSizeCall(maybeSize) != null && isZeroLiteral(maybeZero);
    }

    private static MethodInvocation extractSizeCall(Expression expr) {
        if (!(expr instanceof MethodInvocation inv)) {
            return null;
        }
        if (!"size".equals(inv.getName().getIdentifier())) {
            return null;
        }
        @SuppressWarnings("unchecked")
        List<Expression> args = inv.arguments();
        if (args != null && !args.isEmpty()) {
            return null;
        }
        return inv;
    }

    private static boolean isZeroLiteral(Expression expr) {
        return expr instanceof NumberLiteral lit && "0".equals(lit.getToken());
    }
}
