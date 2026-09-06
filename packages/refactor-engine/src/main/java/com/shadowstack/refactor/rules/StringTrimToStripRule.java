package com.shadowstack.refactor.rules;

import com.shadowstack.refactor.RefactorRule;
import com.shadowstack.refactor.model.*;
import org.eclipse.jdt.core.dom.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * {@code s.trim()} → {@code s.strip()} (Java 11 Unicode-aware whitespace).
 */
public class StringTrimToStripRule implements RefactorRule {

    private static final String RULE_ID = "STRING_TRIM_TO_STRIP";

    @Override
    public String ruleId() {
        return RULE_ID;
    }

    @Override
    public String ruleName() {
        return "String.trim → strip";
    }

    @Override
    public String description() {
        return "Replaces String.trim() with strip() for Unicode-aware whitespace handling.";
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
        if (!"trim".equals(inv.getName().getIdentifier())) {
            return false;
        }
        if (inv.getExpression() == null) {
            return false;
        }
        @SuppressWarnings("unchecked")
        List<Expression> args = inv.arguments();
        return args == null || args.isEmpty();
    }

    @Override
    public RefactorCandidate analyze(ASTNode node, SemanticContext context) {
        MethodInvocation inv = (MethodInvocation) node;
        String receiver = inv.getExpression().toString();
        String original = context.getSourceRange(node.getStartPosition(), node.getLength());
        String proposed = receiver + ".strip()";
        int startLine = context.getLineNumber(node.getStartPosition());
        List<SafetyInvariant> invariants = new ArrayList<>();
        invariants.add(SafetyInvariant.verified(
                "strip_unicode_ws",
                "strip() removes Unicode whitespace; trim() only <= U+0020",
                proposed));
        return RefactorCandidate.builder(RULE_ID, context.getSourceFilePath())
                .startLine(startLine)
                .endLine(startLine)
                .startPosition(node.getStartPosition())
                .length(node.getLength())
                .originalSnippet(original)
                .proposedSnippet(proposed)
                .confidenceScore(0.87)
                .riskTier(RiskTier.LOW)
                .invariants(invariants)
                .analysisMetadata(Map.of("receiver", receiver))
                .rationale("Prefer String.strip() (Java 11+) over trim()")
                .astNode(node)
                .build();
    }

    @Override
    public PatchUnit apply(RefactorCandidate candidate) {
        if (!candidate.allInvariantsVerified()) {
            throw new IllegalArgumentException("Cannot apply trim→strip rule");
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
