package com.shadowstack.refactor.rules;

import com.shadowstack.refactor.RefactorRule;
import com.shadowstack.refactor.model.*;
import org.eclipse.jdt.core.dom.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/** Detect-only: HttpURLConnection / URL.openConnection → java.net.http.HttpClient. */
public class HttpUrlConnectionToHttpClientRule implements RefactorRule {
    private static final String RULE_ID = "HTTPURLCONNECTION_TO_HTTPCLIENT";
    @Override public String ruleId() { return RULE_ID; }
    @Override public String ruleName() { return "HttpURLConnection → HttpClient (detect)"; }
    @Override public String description() {
        return "Detects legacy HttpURLConnection usage and suggests java.net.http.HttpClient migration.";
    }
    @Override public RiskTier defaultRiskTier() { return RiskTier.HIGH; }

    @Override
    public boolean appliesTo(ASTNode node, SemanticContext context) {
        if (node instanceof ClassInstanceCreation cic) {
            return typeMentions(cic.getType(), "HttpURLConnection");
        }
        if (node instanceof MethodInvocation inv) {
            if (!"openConnection".equals(inv.getName().getIdentifier())) return false;
            return inv.getExpression() != null;
        }
        if (node instanceof SimpleType simple) {
            String q = simple.getName().getFullyQualifiedName();
            return q.equals("HttpURLConnection") || q.endsWith(".HttpURLConnection");
        }
        if (node instanceof ImportDeclaration imp) {
            String q = imp.getName().getFullyQualifiedName();
            return q.equals("java.net.HttpURLConnection") || q.endsWith(".HttpURLConnection");
        }
        return false;
    }

    @Override
    public RefactorCandidate analyze(ASTNode node, SemanticContext context) {
        String original = context.getSourceRange(node.getStartPosition(), node.getLength());
        String proposed = "/* migrate to java.net.http.HttpClient */ " + original;
        int startLine = context.getLineNumber(node.getStartPosition());
        List<SafetyInvariant> invariants = new ArrayList<>();
        invariants.add(SafetyInvariant.verified("httpclient_detect",
                "Detect-only stub; manual HttpClient migration recommended", proposed));
        return RefactorCandidate.builder(RULE_ID, context.getSourceFilePath())
                .startLine(startLine).endLine(startLine)
                .startPosition(node.getStartPosition()).length(node.getLength())
                .originalSnippet(original).proposedSnippet(proposed)
                .confidenceScore(0.45).riskTier(RiskTier.HIGH)
                .invariants(invariants)
                .analysisMetadata(Map.of("mode", "detect_only"))
                .rationale("Legacy HttpURLConnection should migrate to java.net.http.HttpClient")
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

    private static boolean typeMentions(Type type, String simple) {
        if (type instanceof SimpleType st) {
            String q = st.getName().getFullyQualifiedName();
            return q.equals(simple) || q.endsWith("." + simple);
        }
        if (type instanceof QualifiedType qt) {
            return simple.equals(qt.getName().getIdentifier());
        }
        if (type instanceof NameQualifiedType nqt) {
            return simple.equals(nqt.getName().getIdentifier());
        }
        return false;
    }
}
