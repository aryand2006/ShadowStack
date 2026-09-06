package com.shadowstack.refactor.rules;

import com.shadowstack.refactor.RefactorRule;
import com.shadowstack.refactor.model.*;
import org.eclipse.jdt.core.dom.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code s.toUpperCase()} / {@code toLowerCase()} → {@code ...(Locale.ROOT)} (Sonar S1449).
 */
public class ToUpperLowerLocaleRootRule implements RefactorRule {

    private static final String RULE_ID = "TOUPPERLOWER_LOCALE_ROOT";
    private static final Set<String> METHODS = Set.of("toUpperCase", "toLowerCase");

    @Override
    public String ruleId() {
        return RULE_ID;
    }

    @Override
    public String ruleName() {
        return "toUpper/LowerCase → Locale.ROOT";
    }

    @Override
    public String description() {
        return "Passes Locale.ROOT to case-conversion methods to avoid locale-sensitive bugs.";
    }

    @Override
    public RiskTier defaultRiskTier() {
        return RiskTier.LOW;
    }

    @Override
    public boolean appliesTo(ASTNode node, SemanticContext context) {
        if (!(node instanceof MethodInvocation inv)) {
            return false;
        }
        if (!METHODS.contains(inv.getName().getIdentifier())) {
            return false;
        }
        @SuppressWarnings("unchecked")
        List<Expression> args = inv.arguments();
        return args == null || args.isEmpty();
    }

    @Override
    public RefactorCandidate analyze(ASTNode node, SemanticContext context) {
        MethodInvocation inv = (MethodInvocation) node;
        String method = inv.getName().getIdentifier();
        String receiver = inv.getExpression() != null ? inv.getExpression().toString() : "this";
        String original = context.getSourceRange(node.getStartPosition(), node.getLength());
        String proposed = receiver + "." + method + "(java.util.Locale.ROOT)";
        int startLine = context.getLineNumber(node.getStartPosition());

        List<SafetyInvariant> invariants = new ArrayList<>();
        invariants.add(SafetyInvariant.verified(
                "locale_root_deterministic",
                "Locale.ROOT makes case conversion locale-independent",
                proposed));

        return RefactorCandidate.builder(RULE_ID, context.getSourceFilePath())
                .startLine(startLine)
                .endLine(startLine)
                .startPosition(node.getStartPosition())
                .length(node.getLength())
                .originalSnippet(original)
                .proposedSnippet(proposed)
                .confidenceScore(0.91)
                .riskTier(RiskTier.LOW)
                .invariants(invariants)
                .analysisMetadata(Map.of("method", method))
                .rationale("Avoid locale-sensitive case conversion (Sonar S1449)")
                .astNode(node)
                .build();
    }

    @Override
    public PatchUnit apply(RefactorCandidate candidate) {
        if (!candidate.allInvariantsVerified()) {
            throw new IllegalArgumentException("Cannot apply Locale.ROOT case rule");
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
}
