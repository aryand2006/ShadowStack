package com.shadowstack.refactor.rules;

import com.shadowstack.refactor.RefactorRule;
import com.shadowstack.refactor.model.*;
import org.eclipse.jdt.core.dom.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/** String.format(fmt, args) → fmt.formatted(args) (Java 15+). */
public class StringFormattedRule implements RefactorRule {
    private static final String RULE_ID = "STRING_FORMATTED";
    @Override public String ruleId() { return RULE_ID; }
    @Override public String ruleName() { return "String.format → formatted"; }
    @Override public String description() {
        return "Replaces String.format(fmt, args...) with fmt.formatted(args...) (Java 15+).";
    }
    @Override public RiskTier defaultRiskTier() { return RiskTier.LOW; }

    @Override
    public boolean appliesTo(ASTNode node, SemanticContext context) {
        if (!(node instanceof MethodInvocation inv)) return false;
        if (!"format".equals(inv.getName().getIdentifier())) return false;
        Expression expr = inv.getExpression();
        if (expr == null) return false;
        String recv = expr.toString();
        if (!"String".equals(recv) && !"java.lang.String".equals(recv)) return false;
        @SuppressWarnings("unchecked")
        List<Expression> args = inv.arguments();
        return args != null && args.size() >= 1;
    }

    @Override
    public RefactorCandidate analyze(ASTNode node, SemanticContext context) {
        MethodInvocation inv = (MethodInvocation) node;
        @SuppressWarnings("unchecked")
        List<Expression> args = inv.arguments();
        String fmt = args.get(0).toString();
        String rest = args.size() == 1 ? "" :
                String.join(", ", args.subList(1, args.size()).stream().map(Object::toString).toList());
        String original = context.getSourceRange(node.getStartPosition(), node.getLength());
        String proposed = rest.isEmpty() ? fmt + ".formatted()" : fmt + ".formatted(" + rest + ")";
        int startLine = context.getLineNumber(node.getStartPosition());
        List<SafetyInvariant> invariants = new ArrayList<>();
        invariants.add(SafetyInvariant.verified("string_formatted",
                "String.formatted is equivalent to String.format for the format receiver", proposed));
        return RefactorCandidate.builder(RULE_ID, context.getSourceFilePath())
                .startLine(startLine).endLine(startLine)
                .startPosition(node.getStartPosition()).length(node.getLength())
                .originalSnippet(original).proposedSnippet(proposed)
                .confidenceScore(0.9).riskTier(RiskTier.LOW)
                .invariants(invariants)
                .analysisMetadata(Map.of("argCount", String.valueOf(args.size())))
                .rationale("Prefer String.formatted (Java 15+)")
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
