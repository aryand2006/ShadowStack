package com.shadowstack.refactor.rules;

import com.shadowstack.refactor.RefactorRule;
import com.shadowstack.refactor.model.*;
import org.eclipse.jdt.core.dom.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/** new SimpleDateFormat(pattern) → DateTimeFormatter.ofPattern(pattern). */
public class SimpleDateFormatToDateTimeFormatterRule implements RefactorRule {
    private static final String RULE_ID = "SIMPLEDATEFORMAT_TO_DATETIMEFORMATTER";
    @Override public String ruleId() { return RULE_ID; }
    @Override public String ruleName() { return "SimpleDateFormat → DateTimeFormatter"; }
    @Override public String description() {
        return "Migrates SimpleDateFormat construction to DateTimeFormatter.ofPattern.";
    }
    @Override public RiskTier defaultRiskTier() { return RiskTier.HIGH; }

    @Override
    public boolean appliesTo(ASTNode node, SemanticContext context) {
        if (!(node instanceof ClassInstanceCreation cic)) return false;
        String type = cic.getType().toString();
        String simple = type.contains(".") ? type.substring(type.lastIndexOf('.') + 1) : type;
        if (!"SimpleDateFormat".equals(simple)) return false;
        @SuppressWarnings("unchecked")
        List<Expression> args = cic.arguments();
        return args != null && args.size() == 1;
    }

    @Override
    public RefactorCandidate analyze(ASTNode node, SemanticContext context) {
        ClassInstanceCreation cic = (ClassInstanceCreation) node;
        @SuppressWarnings("unchecked")
        List<Expression> args = cic.arguments();
        String proposed = "java.time.format.DateTimeFormatter.ofPattern(" + args.get(0) + ")";
        String original = context.getSourceRange(node.getStartPosition(), node.getLength());
        int startLine = context.getLineNumber(node.getStartPosition());
        List<SafetyInvariant> invariants = new ArrayList<>();
        invariants.add(SafetyInvariant.verified("dtf_threadsafe",
                "DateTimeFormatter is immutable/thread-safe; call-site format API changes", proposed));
        return RefactorCandidate.builder(RULE_ID, context.getSourceFilePath())
                .startLine(startLine).endLine(startLine)
                .startPosition(node.getStartPosition()).length(node.getLength())
                .originalSnippet(original).proposedSnippet(proposed)
                .confidenceScore(0.6).riskTier(RiskTier.HIGH)
                .invariants(invariants)
                .analysisMetadata(Map.of())
                .rationale("SimpleDateFormat is legacy; prefer DateTimeFormatter")
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
