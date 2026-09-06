package com.shadowstack.refactor.rules;

import com.shadowstack.refactor.RefactorRule;
import com.shadowstack.refactor.model.*;
import org.eclipse.jdt.core.dom.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * {@code URLEncoder.encode(s)} → {@code URLEncoder.encode(s, StandardCharsets.UTF_8)}
 * (Java 10+ Charset overload / Sonar).
 */
public class UrlEncoderCharsetRule implements RefactorRule {

    private static final String RULE_ID = "URLENCODER_CHARSET";

    @Override
    public String ruleId() {
        return RULE_ID;
    }

    @Override
    public String ruleName() {
        return "URLEncoder.encode → UTF-8 Charset";
    }

    @Override
    public String description() {
        return "Replaces single-arg URLEncoder.encode with the Charset UTF-8 overload.";
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
        if (!"encode".equals(inv.getName().getIdentifier())) {
            return false;
        }
        Expression expr = inv.getExpression();
        if (!(expr instanceof Name name)) {
            return false;
        }
        String q = name.getFullyQualifiedName();
        if (!"URLEncoder".equals(q) && !"java.net.URLEncoder".equals(q)) {
            return false;
        }
        @SuppressWarnings("unchecked")
        List<Expression> args = inv.arguments();
        return args != null && args.size() == 1;
    }

    @Override
    public RefactorCandidate analyze(ASTNode node, SemanticContext context) {
        MethodInvocation inv = (MethodInvocation) node;
        @SuppressWarnings("unchecked")
        List<Expression> args = inv.arguments();
        String original = context.getSourceRange(node.getStartPosition(), node.getLength());
        String proposed = "URLEncoder.encode(" + args.get(0)
                + ", java.nio.charset.StandardCharsets.UTF_8)";
        int startLine = context.getLineNumber(node.getStartPosition());
        List<SafetyInvariant> invariants = new ArrayList<>();
        invariants.add(SafetyInvariant.verified(
                "urlencoder_utf8",
                "Charset overload avoids UnsupportedEncodingException and default charset",
                proposed));
        return RefactorCandidate.builder(RULE_ID, context.getSourceFilePath())
                .startLine(startLine)
                .endLine(startLine)
                .startPosition(node.getStartPosition())
                .length(node.getLength())
                .originalSnippet(original)
                .proposedSnippet(proposed)
                .confidenceScore(0.92)
                .riskTier(RiskTier.LOW)
                .invariants(invariants)
                .analysisMetadata(Map.of("arg", args.get(0).toString()))
                .rationale("Prefer URLEncoder.encode with StandardCharsets.UTF_8")
                .astNode(node)
                .build();
    }

    @Override
    public PatchUnit apply(RefactorCandidate candidate) {
        if (!candidate.allInvariantsVerified()) {
            throw new IllegalArgumentException("Cannot apply URLEncoder charset rule");
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
