package com.shadowstack.refactor.rules;

import com.shadowstack.refactor.RefactorRule;
import com.shadowstack.refactor.model.*;
import org.eclipse.jdt.core.dom.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * {@code s.indexOf(x) &gt;= 0} / {@code != -1} → {@code s.contains(x)} (Sonar S1155-adjacent / Guava).
 */
public class IndexOfToContainsRule implements RefactorRule {

    private static final String RULE_ID = "INDEXOF_TO_CONTAINS";

    @Override
    public String ruleId() {
        return RULE_ID;
    }

    @Override
    public String ruleName() {
        return "indexOf compare → contains";
    }

    @Override
    public String description() {
        return "Replaces String/Collection indexOf comparisons against 0/-1 with contains().";
    }

    @Override
    public RiskTier defaultRiskTier() {
        return RiskTier.COSMETIC;
    }

    @Override
    public boolean appliesTo(ASTNode node, SemanticContext context) {
        return parse(node) != null;
    }

    @Override
    public RefactorCandidate analyze(ASTNode node, SemanticContext context) {
        Parsed p = parse(node);
        String original = context.getSourceRange(node.getStartPosition(), node.getLength());
        String receiver = p.indexOf.getExpression() != null
                ? p.indexOf.getExpression().toString()
                : "this";
        @SuppressWarnings("unchecked")
        List<Expression> args = p.indexOf.arguments();
        String arg = args.get(0).toString();
        String proposed = p.negated
                ? "!" + receiver + ".contains(" + arg + ")"
                : receiver + ".contains(" + arg + ")";
        int startLine = context.getLineNumber(node.getStartPosition());

        List<SafetyInvariant> invariants = new ArrayList<>();
        invariants.add(SafetyInvariant.verified(
                "contains_equivalent",
                "For String/Collection, indexOf >= 0 / != -1 is equivalent to contains",
                proposed));

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
                .analysisMetadata(Map.of("receiver", receiver, "negated", p.negated))
                .rationale("Prefer contains() over indexOf comparisons")
                .astNode(node)
                .build();
    }

    @Override
    public PatchUnit apply(RefactorCandidate candidate) {
        if (!candidate.allInvariantsVerified()) {
            throw new IllegalArgumentException("Cannot apply indexOf→contains rule");
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

    private record Parsed(MethodInvocation indexOf, boolean negated) {}

    private static Parsed parse(ASTNode node) {
        if (!(node instanceof InfixExpression infix)) {
            return null;
        }
        InfixExpression.Operator op = infix.getOperator();
        MethodInvocation left = asIndexOf(infix.getLeftOperand());
        Expression right = infix.getRightOperand();
        MethodInvocation rightCall = asIndexOf(right);
        Expression leftExpr = infix.getLeftOperand();

        if (left != null) {
            if ((op == InfixExpression.Operator.GREATER_EQUALS && isZero(right))
                    || (op == InfixExpression.Operator.NOT_EQUALS && isMinusOne(right))
                    || (op == InfixExpression.Operator.GREATER && isMinusOne(right))) {
                return new Parsed(left, false);
            }
            if ((op == InfixExpression.Operator.LESS && isZero(right))
                    || (op == InfixExpression.Operator.EQUALS && isMinusOne(right))) {
                return new Parsed(left, true);
            }
        }
        if (rightCall != null) {
            if ((op == InfixExpression.Operator.LESS_EQUALS && isZero(leftExpr))
                    || (op == InfixExpression.Operator.NOT_EQUALS && isMinusOne(leftExpr))) {
                return new Parsed(rightCall, false);
            }
            if (op == InfixExpression.Operator.EQUALS && isMinusOne(leftExpr)) {
                return new Parsed(rightCall, true);
            }
        }
        return null;
    }

    private static MethodInvocation asIndexOf(Expression expr) {
        if (!(expr instanceof MethodInvocation inv)) {
            return null;
        }
        if (!"indexOf".equals(inv.getName().getIdentifier())) {
            return null;
        }
        @SuppressWarnings("unchecked")
        List<Expression> args = inv.arguments();
        return args != null && args.size() == 1 ? inv : null;
    }

    private static boolean isZero(Expression expr) {
        return expr instanceof NumberLiteral lit && "0".equals(lit.getToken());
    }

    private static boolean isMinusOne(Expression expr) {
        if (expr instanceof PrefixExpression prefix
                && prefix.getOperator() == PrefixExpression.Operator.MINUS
                && prefix.getOperand() instanceof NumberLiteral lit) {
            return "1".equals(lit.getToken());
        }
        return expr instanceof NumberLiteral lit && "-1".equals(lit.getToken());
    }
}
