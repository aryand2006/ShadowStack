package com.shadowstack.refactor.rules;

import com.shadowstack.refactor.RefactorRule;
import com.shadowstack.refactor.model.*;
import org.eclipse.jdt.core.dom.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Replaces deprecated Float constructors. */
public class FloatConstructorRule implements RefactorRule {
    private static final String RULE_ID = "FLOAT_CTOR_TO_VALUEOF";
    private static final Set<String> TYPES = Set.of("Float");
    @Override public String ruleId() { return RULE_ID; }
    @Override public String ruleName() { return "new Float(x) → Float.valueOf"; }
    @Override public String description() { return "Replaces deprecated Float constructors."; }
    @Override public RiskTier defaultRiskTier() { return RiskTier.LOW; }

    @Override
    public boolean appliesTo(ASTNode node, SemanticContext context) {
        if (!(node instanceof ClassInstanceCreation cic)) return false;
        String type = cic.getType().toString();
        String simple = type.contains(".") ? type.substring(type.lastIndexOf('.') + 1) : type;
        if (!TYPES.contains(simple)) return false;
        @SuppressWarnings("unchecked")
        List<Expression> args = cic.arguments();
        return args != null && args.size() == 1;
    }

    @Override
    public RefactorCandidate analyze(ASTNode node, SemanticContext context) {
        ClassInstanceCreation cic = (ClassInstanceCreation) node;
        String type = cic.getType().toString();
        String simple = type.contains(".") ? type.substring(type.lastIndexOf('.') + 1) : type;
        @SuppressWarnings("unchecked")
        List<Expression> args = cic.arguments();
        String joined = String.join(", ", args.stream().map(Object::toString).toList());
        String arg0 = args.isEmpty() ? "" : args.get(0).toString();
        String original = context.getSourceRange(node.getStartPosition(), node.getLength());
        String proposed = "Float.valueOf(" + arg0 + ")";
        int startLine = context.getLineNumber(node.getStartPosition());
        List<SafetyInvariant> invariants = new ArrayList<>();
        invariants.add(SafetyInvariant.verified("float_valueof", "valueOf replaces Float constructors", proposed));
        return RefactorCandidate.builder(RULE_ID, context.getSourceFilePath())
                .startLine(startLine).endLine(startLine)
                .startPosition(node.getStartPosition()).length(node.getLength())
                .originalSnippet(original).proposedSnippet(proposed)
                .confidenceScore(0.93).riskTier(RiskTier.LOW)
                .invariants(invariants)
                .analysisMetadata(Map.of("type", simple))
                .rationale("Float constructors are deprecated")
                .astNode(node).build();
    }

    @Override
    public PatchUnit apply(RefactorCandidate candidate) {
        if (!candidate.allInvariantsVerified()) {
            throw new IllegalArgumentException("Cannot apply " + RULE_ID);
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

