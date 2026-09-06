package com.shadowstack.refactor.rules;

import com.shadowstack.refactor.RefactorRule;
import com.shadowstack.refactor.model.*;
import org.eclipse.jdt.core.dom.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/** new URL(s) → URI.create(s).toURL() (JDK deprecation of URL constructors). */
public class UrlConstructorToUriRule implements RefactorRule {
    private static final String RULE_ID = "URL_CTOR_TO_URI";
    @Override public String ruleId() { return RULE_ID; }
    @Override public String ruleName() { return "new URL(s) → URI.create(s).toURL()"; }
    @Override public String description() {
        return "Replaces deprecated java.net.URL constructors with URI.create(...).toURL().";
    }
    @Override public RiskTier defaultRiskTier() { return RiskTier.MEDIUM; }

    @Override
    public boolean appliesTo(ASTNode node, SemanticContext context) {
        if (!(node instanceof ClassInstanceCreation cic)) return false;
        String type = cic.getType().toString();
        String simple = type.contains(".") ? type.substring(type.lastIndexOf('.') + 1) : type;
        if (!"URL".equals(simple)) return false;
        @SuppressWarnings("unchecked")
        List<Expression> args = cic.arguments();
        return args != null && args.size() == 1;
    }

    @Override
    public RefactorCandidate analyze(ASTNode node, SemanticContext context) {
        ClassInstanceCreation cic = (ClassInstanceCreation) node;
        @SuppressWarnings("unchecked")
        List<Expression> args = cic.arguments();
        String arg0 = args.get(0).toString();
        String original = context.getSourceRange(node.getStartPosition(), node.getLength());
        String proposed = "java.net.URI.create(" + arg0 + ").toURL()";
        int startLine = context.getLineNumber(node.getStartPosition());
        List<SafetyInvariant> invariants = new ArrayList<>();
        invariants.add(SafetyInvariant.verified("uri_create",
                "URI.create + toURL replaces deprecated URL string constructor", proposed));
        return RefactorCandidate.builder(RULE_ID, context.getSourceFilePath())
                .startLine(startLine).endLine(startLine)
                .startPosition(node.getStartPosition()).length(node.getLength())
                .originalSnippet(original).proposedSnippet(proposed)
                .confidenceScore(0.85).riskTier(RiskTier.MEDIUM)
                .invariants(invariants)
                .analysisMetadata(Map.of())
                .rationale("URL constructors are deprecated; prefer URI.create")
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
