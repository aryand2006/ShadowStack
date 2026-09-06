package com.shadowstack.refactor.rules;

import com.shadowstack.refactor.RefactorRule;
import com.shadowstack.refactor.model.*;
import org.eclipse.jdt.core.dom.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * {@code s.getBytes()} → {@code s.getBytes(StandardCharsets.UTF_8)} (Sonar S4719).
 */
public class StringGetBytesCharsetRule implements RefactorRule {

    private static final String RULE_ID = "STRING_GETBYTES_CHARSET";

    @Override
    public String ruleId() {
        return RULE_ID;
    }

    @Override
    public String ruleName() {
        return "String.getBytes() → getBytes(UTF_8)";
    }

    @Override
    public String description() {
        return "Passes StandardCharsets.UTF_8 to String.getBytes() to avoid platform default charset.";
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
        if (!"getBytes".equals(inv.getName().getIdentifier())) {
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
        String proposed = receiver + ".getBytes(java.nio.charset.StandardCharsets.UTF_8)";
        int startLine = context.getLineNumber(node.getStartPosition());
        List<SafetyInvariant> invariants = new ArrayList<>();
        invariants.add(SafetyInvariant.verified(
                "explicit_utf8",
                "Explicit UTF-8 avoids platform-default charset surprises",
                proposed));
        return RefactorCandidate.builder(RULE_ID, context.getSourceFilePath())
                .startLine(startLine)
                .endLine(startLine)
                .startPosition(node.getStartPosition())
                .length(node.getLength())
                .originalSnippet(original)
                .proposedSnippet(proposed)
                .confidenceScore(0.9)
                .riskTier(RiskTier.LOW)
                .invariants(invariants)
                .analysisMetadata(Map.of("receiver", receiver))
                .rationale("Prefer explicit UTF-8 charset for String.getBytes()")
                .astNode(node)
                .build();
    }

    @Override
    public PatchUnit apply(RefactorCandidate candidate) {
        if (!candidate.allInvariantsVerified()) {
            throw new IllegalArgumentException("Cannot apply getBytes charset rule");
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
