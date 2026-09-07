package com.shadowstack.refactor.rules;

import com.shadowstack.refactor.RefactorRule;
import com.shadowstack.refactor.model.*;
import org.eclipse.jdt.core.dom.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/** "".equals(s) → s != null && s.isEmpty(); s.length()==0 → s.isEmpty(). */
public class StringIsEmptyRule implements RefactorRule {
    private static final String RULE_ID = "STRING_ISEMPTY";
    @Override public String ruleId() { return RULE_ID; }
    @Override public String ruleName() { return "Empty string check → isEmpty()"; }
    @Override public String description() {
        return "Modernizes empty-string checks to String.isEmpty() (with null guard for literal-first equals).";
    }
    @Override public RiskTier defaultRiskTier() { return RiskTier.LOW; }

    @Override
    public boolean appliesTo(ASTNode node, SemanticContext context) {
        return parse(node) != null;
    }

    @Override
    public RefactorCandidate analyze(ASTNode node, SemanticContext context) {
        Parsed p = parse(node);
        String original = context.getSourceRange(node.getStartPosition(), node.getLength());
        int startLine = context.getLineNumber(node.getStartPosition());
        List<SafetyInvariant> invariants = new ArrayList<>();
        invariants.add(SafetyInvariant.verified("string_isempty",
                "isEmpty() is the preferred empty-string check", p.proposed));
        return RefactorCandidate.builder(RULE_ID, context.getSourceFilePath())
                .startLine(startLine).endLine(startLine)
                .startPosition(node.getStartPosition()).length(node.getLength())
                .originalSnippet(original).proposedSnippet(p.proposed)
                .confidenceScore(0.88).riskTier(RiskTier.LOW)
                .invariants(invariants)
                .analysisMetadata(Map.of("kind", p.kind))
                .rationale("Prefer String.isEmpty() over length/empty-literal equals")
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

    private record Parsed(String proposed, String kind) {}

    private static Parsed parse(ASTNode node) {
        if (node instanceof MethodInvocation inv) {
            if (!"equals".equals(inv.getName().getIdentifier())) return null;
            Expression expr = inv.getExpression();
            if (!(expr instanceof StringLiteral lit) || !lit.getLiteralValue().isEmpty()) return null;
            @SuppressWarnings("unchecked")
            List<Expression> args = inv.arguments();
            if (args == null || args.size() != 1) return null;
            String arg = args.get(0).toString();
            return new Parsed(arg + " != null && " + arg + ".isEmpty()", "empty_literal_equals");
        }
        if (node instanceof InfixExpression infix) {
            InfixExpression.Operator op = infix.getOperator();
            if (op != InfixExpression.Operator.EQUALS && op != InfixExpression.Operator.NOT_EQUALS) {
                return null;
            }
            MethodInvocation length = extractLength(infix.getLeftOperand());
            Expression other = infix.getRightOperand();
            if (length == null) {
                length = extractLength(infix.getRightOperand());
                other = infix.getLeftOperand();
            }
            if (length == null || !(other instanceof NumberLiteral nl) || !"0".equals(nl.getToken())) {
                return null;
            }
            String receiver = length.getExpression() != null ? length.getExpression().toString() : "this";
            boolean equals = op == InfixExpression.Operator.EQUALS;
            String proposed = equals ? receiver + ".isEmpty()" : "!" + receiver + ".isEmpty()";
            return new Parsed(proposed, "length_zero");
        }
        return null;
    }

    private static MethodInvocation extractLength(Expression expr) {
        if (!(expr instanceof MethodInvocation inv)) return null;
        if (!"length".equals(inv.getName().getIdentifier())) return null;
        @SuppressWarnings("unchecked")
        List<Expression> args = inv.arguments();
        if (args != null && !args.isEmpty()) return null;
        return inv;
    }
}
