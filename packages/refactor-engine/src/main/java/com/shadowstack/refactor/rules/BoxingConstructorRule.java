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
 * {@code new Integer(x)} → {@code Integer.valueOf(x)} (JDK deprecation / OpenRewrite).
 */
public class BoxingConstructorRule implements RefactorRule {

    private static final String RULE_ID = "BOXING_CONSTRUCTOR_TO_VALUEOF";
    private static final Set<String> BOXED = Set.of(
            "Integer", "Long", "Double", "Float", "Short", "Byte", "Boolean", "Character");

    @Override
    public String ruleId() {
        return RULE_ID;
    }

    @Override
    public String ruleName() {
        return "Boxed constructor → valueOf";
    }

    @Override
    public String description() {
        return "Replaces deprecated boxed primitive constructors with Type.valueOf(...).";
    }

    @Override
    public RiskTier defaultRiskTier() {
        return RiskTier.LOW;
    }

    @Override
    public boolean appliesTo(ASTNode node, SemanticContext context) {
        if (!(node instanceof ClassInstanceCreation creation)) {
            return false;
        }
        if (creation.getAnonymousClassDeclaration() != null) {
            return false;
        }
        String simple = simpleName(creation.getType());
        if (simple == null || !BOXED.contains(simple)) {
            return false;
        }
        @SuppressWarnings("unchecked")
        List<Expression> args = creation.arguments();
        return args != null && args.size() == 1;
    }

    @Override
    public RefactorCandidate analyze(ASTNode node, SemanticContext context) {
        ClassInstanceCreation creation = (ClassInstanceCreation) node;
        String typeName = simpleName(creation.getType());
        @SuppressWarnings("unchecked")
        List<Expression> args = creation.arguments();
        String original = context.getSourceRange(node.getStartPosition(), node.getLength());
        String proposed = typeName + ".valueOf(" + args.get(0) + ")";
        int startLine = context.getLineNumber(node.getStartPosition());

        List<SafetyInvariant> invariants = new ArrayList<>();
        invariants.add(SafetyInvariant.verified(
                "valueof_cache_safe",
                "valueOf is the JDK-recommended replacement for boxed constructors",
                typeName + ".valueOf"));

        return RefactorCandidate.builder(RULE_ID, context.getSourceFilePath())
                .startLine(startLine)
                .endLine(startLine)
                .startPosition(node.getStartPosition())
                .length(node.getLength())
                .originalSnippet(original)
                .proposedSnippet(proposed)
                .confidenceScore(0.95)
                .riskTier(RiskTier.LOW)
                .invariants(invariants)
                .analysisMetadata(Map.of("type", typeName))
                .rationale("Deprecated boxed constructors → valueOf")
                .astNode(node)
                .build();
    }

    @Override
    public PatchUnit apply(RefactorCandidate candidate) {
        if (!candidate.allInvariantsVerified()) {
            throw new IllegalArgumentException("Cannot apply boxing rule");
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

    private static String simpleName(Type type) {
        if (type instanceof SimpleType simple) {
            return simple.getName().getFullyQualifiedName();
        }
        if (type instanceof NameQualifiedType nqt) {
            return nqt.getName().getIdentifier();
        }
        if (type instanceof QualifiedType qt) {
            return qt.getName().getIdentifier();
        }
        return null;
    }
}
