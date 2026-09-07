package com.shadowstack.refactor.rules;

import com.shadowstack.refactor.RefactorRule;
import com.shadowstack.refactor.model.*;
import org.eclipse.jdt.core.dom.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/** IOUtils/ByteStreams.toByteArray(InputStream) → InputStream.readAllBytes() (Java 9+). */
public class InputStreamReadAllBytesRule implements RefactorRule {
    private static final String RULE_ID = "INPUTSTREAM_READALLBYTES";
    @Override public String ruleId() { return RULE_ID; }
    @Override public String ruleName() { return "toByteArray → InputStream.readAllBytes"; }
    @Override public String description() {
        return "Replaces IOUtils/ByteStreams.toByteArray(stream) with stream.readAllBytes().";
    }
    @Override public RiskTier defaultRiskTier() { return RiskTier.LOW; }

    @Override
    public boolean appliesTo(ASTNode node, SemanticContext context) {
        if (!(node instanceof MethodInvocation inv)) return false;
        if (!"toByteArray".equals(inv.getName().getIdentifier())) return false;
        Expression expr = inv.getExpression();
        if (expr == null) return false;
        String recv = expr.toString();
        boolean known = "IOUtils".equals(recv) || recv.endsWith(".IOUtils")
                || "ByteStreams".equals(recv) || recv.endsWith(".ByteStreams")
                || "org.apache.commons.io.IOUtils".equals(recv)
                || "com.google.common.io.ByteStreams".equals(recv);
        if (!known) return false;
        @SuppressWarnings("unchecked")
        List<Expression> args = inv.arguments();
        return args != null && args.size() == 1;
    }

    @Override
    public RefactorCandidate analyze(ASTNode node, SemanticContext context) {
        MethodInvocation inv = (MethodInvocation) node;
        @SuppressWarnings("unchecked")
        List<Expression> args = inv.arguments();
        String stream = args.get(0).toString();
        String original = context.getSourceRange(node.getStartPosition(), node.getLength());
        String proposed = stream + ".readAllBytes()";
        int startLine = context.getLineNumber(node.getStartPosition());
        List<SafetyInvariant> invariants = new ArrayList<>();
        invariants.add(SafetyInvariant.verified("read_all_bytes",
                "InputStream.readAllBytes() replaces commons/guava toByteArray", proposed));
        return RefactorCandidate.builder(RULE_ID, context.getSourceFilePath())
                .startLine(startLine).endLine(startLine)
                .startPosition(node.getStartPosition()).length(node.getLength())
                .originalSnippet(original).proposedSnippet(proposed)
                .confidenceScore(0.86).riskTier(RiskTier.LOW)
                .invariants(invariants)
                .analysisMetadata(Map.of("stream", stream))
                .rationale("Prefer InputStream.readAllBytes() (Java 9+)")
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
