package com.shadowstack.refactor.rules;

import com.shadowstack.refactor.RefactorRule;
import com.shadowstack.refactor.model.*;
import org.eclipse.jdt.core.dom.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/** Detect if (!map.containsKey(k)) map.put(k, v) → map.computeIfAbsent(...). */
public class ComputeIfAbsentDetectRule implements RefactorRule {
    private static final String RULE_ID = "COMPUTE_IF_ABSENT_DETECT";
    @Override public String ruleId() { return RULE_ID; }
    @Override public String ruleName() { return "containsKey+put → computeIfAbsent"; }
    @Override public String description() {
        return "Detects map.containsKey/put patterns that can become map.computeIfAbsent.";
    }
    @Override public RiskTier defaultRiskTier() { return RiskTier.MEDIUM; }

    @Override
    public boolean appliesTo(ASTNode node, SemanticContext context) {
        return parse(node) != null;
    }

    @Override
    public RefactorCandidate analyze(ASTNode node, SemanticContext context) {
        Parsed p = parse(node);
        String original = context.getSourceRange(node.getStartPosition(), node.getLength());
        String proposed = p.map + ".computeIfAbsent(" + p.key + ", __ -> " + p.value + ")";
        int startLine = context.getLineNumber(node.getStartPosition());
        int endLine = context.getLineNumber(node.getStartPosition() + Math.max(node.getLength() - 1, 0));
        List<SafetyInvariant> invariants = new ArrayList<>();
        invariants.add(SafetyInvariant.verified("compute_if_absent",
                "computeIfAbsent is atomic and avoids double lookup", proposed));
        return RefactorCandidate.builder(RULE_ID, context.getSourceFilePath())
                .startLine(startLine).endLine(endLine)
                .startPosition(node.getStartPosition()).length(node.getLength())
                .originalSnippet(original).proposedSnippet(proposed)
                .confidenceScore(0.7).riskTier(RiskTier.MEDIUM)
                .invariants(invariants)
                .analysisMetadata(Map.of("map", p.map, "key", p.key))
                .rationale("Prefer Map.computeIfAbsent over containsKey + put")
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

    private record Parsed(String map, String key, String value) {}

    private static Parsed parse(ASTNode node) {
        if (!(node instanceof IfStatement ifStmt)) return null;
        Expression cond = ifStmt.getExpression();
        // !map.containsKey(k)
        Expression negated = cond;
        if (cond instanceof PrefixExpression prefix
                && prefix.getOperator() == PrefixExpression.Operator.NOT) {
            negated = prefix.getOperand();
        } else {
            return null;
        }
        if (!(negated instanceof MethodInvocation contains)
                || !"containsKey".equals(contains.getName().getIdentifier())
                || contains.getExpression() == null) {
            return null;
        }
        @SuppressWarnings("unchecked")
        List<Expression> cArgs = contains.arguments();
        if (cArgs == null || cArgs.size() != 1) return null;

        Statement then = ifStmt.getThenStatement();
        MethodInvocation put = findPut(then);
        if (put == null || put.getExpression() == null) return null;
        @SuppressWarnings("unchecked")
        List<Expression> pArgs = put.arguments();
        if (pArgs == null || pArgs.size() != 2) return null;

        String map = contains.getExpression().toString();
        if (!map.equals(put.getExpression().toString())) return null;
        if (!cArgs.get(0).toString().equals(pArgs.get(0).toString())) return null;
        return new Parsed(map, cArgs.get(0).toString(), pArgs.get(1).toString());
    }

    private static MethodInvocation findPut(Statement stmt) {
        if (stmt instanceof ExpressionStatement es
                && es.getExpression() instanceof MethodInvocation inv
                && "put".equals(inv.getName().getIdentifier())) {
            return inv;
        }
        if (stmt instanceof Block block) {
            @SuppressWarnings("unchecked")
            List<Statement> statements = block.statements();
            if (statements != null && statements.size() == 1) {
                return findPut(statements.get(0));
            }
        }
        return null;
    }
}
