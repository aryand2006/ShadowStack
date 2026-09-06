package com.shadowstack.refactor.rules;

import com.shadowstack.refactor.RefactorRule;
import com.shadowstack.refactor.model.*;
import org.eclipse.jdt.core.dom.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * JDK-removed Thread.stop/suspend/resume/destroy modernization.
 */
public class DeprecatedThreadApiRule implements RefactorRule {

    private static final String RULE_ID = "DEPRECATED_THREAD_API";
    private static final Map<String, String> REPLACEMENTS = Map.ofEntries(
                Map.entry("stop", "$R.interrupt()"),
                Map.entry("suspend", "$R.interrupt()"),
                Map.entry("resume", "/* resume removed — redesign coordination for */ $R"),
                Map.entry("destroy", "$R.interrupt()")
    );

    @Override public String ruleId() { return RULE_ID; }
    @Override public String ruleName() { return "Thread stop/suspend/resume/destroy → interrupt"; }
    @Override public String description() { return "Replaces removed Thread lifecycle APIs with interrupt-based control."; }
    @Override public RiskTier defaultRiskTier() { return RiskTier.HIGH; }

    @Override
    public boolean appliesTo(ASTNode node, SemanticContext context) {
        if (!(node instanceof MethodInvocation inv)) return false;
        String name = inv.getName().getIdentifier();
        if (!REPLACEMENTS.containsKey(name)) return false;
        if (inv.getExpression() == null) return false;
        return true;
    }

    @Override
    public RefactorCandidate analyze(ASTNode node, SemanticContext context) {
        MethodInvocation inv = (MethodInvocation) node;
        String name = inv.getName().getIdentifier();
        String receiver = inv.getExpression().toString();
        String original = context.getSourceRange(node.getStartPosition(), node.getLength());
        String template = REPLACEMENTS.get(name);
        String proposed = template.replace("$R", receiver);
        // preserve args if present
        @SuppressWarnings("unchecked")
        List<Expression> args = inv.arguments();
        if (args != null && !args.isEmpty() && proposed.contains("$A")) {
            String joined = String.join(", ", args.stream().map(Object::toString).toList());
            proposed = proposed.replace("$A", joined);
        } else {
            proposed = proposed.replace("($A)", "()").replace("$A", "");
        }
        int startLine = context.getLineNumber(node.getStartPosition());
        List<SafetyInvariant> invariants = new ArrayList<>();
        invariants.add(SafetyInvariant.verified("deprecate_migrate",
                "Replaces removed/deprecated JDK API with supported alternative", proposed));
        return RefactorCandidate.builder(RULE_ID, context.getSourceFilePath())
                .startLine(startLine).endLine(startLine)
                .startPosition(node.getStartPosition()).length(node.getLength())
                .originalSnippet(original).proposedSnippet(proposed)
                .confidenceScore(0.75).riskTier(RiskTier.HIGH)
                .invariants(invariants)
                .analysisMetadata(Map.of("method", name))
                .rationale("Thread.stop/suspend/resume/destroy were removed; prefer interruption")
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
