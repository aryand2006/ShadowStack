package com.shadowstack.refactor.rules;

import com.shadowstack.refactor.RefactorRule;
import com.shadowstack.refactor.model.*;
import org.eclipse.jdt.core.dom.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/** map.containsKey(k) ? map.get(k) : d → map.getOrDefault(k, d). */
public class MapGetOrDefaultRule implements RefactorRule {
    private static final String RULE_ID = "MAP_GET_OR_DEFAULT";
    @Override public String ruleId() { return RULE_ID; }
    @Override public String ruleName() { return "containsKey/get → getOrDefault"; }
    @Override public String description() {
        return "Replaces map.containsKey(k) ? map.get(k) : default with map.getOrDefault(k, default).";
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
        String proposed = p.map + ".getOrDefault(" + p.key + ", " + p.defaultValue + ")";
        int startLine = context.getLineNumber(node.getStartPosition());
        List<SafetyInvariant> invariants = new ArrayList<>();
        invariants.add(SafetyInvariant.verified("get_or_default",
                "getOrDefault is equivalent when mapped values are non-null", proposed));
        return RefactorCandidate.builder(RULE_ID, context.getSourceFilePath())
                .startLine(startLine).endLine(startLine)
                .startPosition(node.getStartPosition()).length(node.getLength())
                .originalSnippet(original).proposedSnippet(proposed)
                .confidenceScore(0.84).riskTier(RiskTier.LOW)
                .invariants(invariants)
                .analysisMetadata(Map.of("map", p.map, "key", p.key))
                .rationale("Prefer Map.getOrDefault over containsKey/get ternary")
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

    private record Parsed(String map, String key, String defaultValue) {}

    private static Parsed parse(ASTNode node) {
        if (!(node instanceof ConditionalExpression cond)) return null;
        Expression condition = cond.getExpression();
        MethodInvocation contains = asCall(condition, "containsKey");
        if (contains == null || contains.getExpression() == null) return null;
        @SuppressWarnings("unchecked")
        List<Expression> cArgs = contains.arguments();
        if (cArgs == null || cArgs.size() != 1) return null;

        MethodInvocation get = asCall(cond.getThenExpression(), "get");
        if (get == null || get.getExpression() == null) return null;
        @SuppressWarnings("unchecked")
        List<Expression> gArgs = get.arguments();
        if (gArgs == null || gArgs.size() != 1) return null;

        String map = contains.getExpression().toString();
        if (!map.equals(get.getExpression().toString())) return null;
        if (!cArgs.get(0).toString().equals(gArgs.get(0).toString())) return null;
        return new Parsed(map, cArgs.get(0).toString(), cond.getElseExpression().toString());
    }

    private static MethodInvocation asCall(Expression expr, String name) {
        if (!(expr instanceof MethodInvocation inv)) return null;
        return name.equals(inv.getName().getIdentifier()) ? inv : null;
    }
}
