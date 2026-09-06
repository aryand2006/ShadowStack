package com.shadowstack.refactor.rules;

import com.shadowstack.refactor.RefactorRule;
import com.shadowstack.refactor.model.*;
import org.eclipse.jdt.core.dom.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * {@code new ArrayList<T>()} → {@code new ArrayList<>()} (OpenRewrite diamond operator).
 */
public class DiamondOperatorRule implements RefactorRule {

    private static final String RULE_ID = "DIAMOND_OPERATOR";

    @Override
    public String ruleId() {
        return RULE_ID;
    }

    @Override
    public String ruleName() {
        return "Diamond Operator";
    }

    @Override
    public String description() {
        return "Replaces explicit constructor type arguments with the diamond operator <>.";
    }

    @Override
    public RiskTier defaultRiskTier() {
        return RiskTier.COSMETIC;
    }

    @Override
    public boolean appliesTo(ASTNode node, SemanticContext context) {
        if (!(node instanceof ClassInstanceCreation creation)) {
            return false;
        }
        if (creation.getAnonymousClassDeclaration() != null) {
            return false;
        }
        Type type = creation.getType();
        if (isLegacyCollectionType(type)) {
            return false; // owned by LegacyTypeMigrationRule
        }
        if (type instanceof ParameterizedType parameterized) {
            if (isLegacyCollectionType(parameterized.getType())) {
                return false;
            }
            @SuppressWarnings("unchecked")
            List<Type> args = parameterized.typeArguments();
            return args != null && !args.isEmpty();
        }
        @SuppressWarnings("unchecked")
        List<Type> typeArgs = creation.typeArguments();
        return typeArgs != null && !typeArgs.isEmpty();
    }

    @Override
    public RefactorCandidate analyze(ASTNode node, SemanticContext context) {
        String original = context.getSourceRange(node.getStartPosition(), node.getLength());
        String proposed = toDiamond(original);
        List<SafetyInvariant> invariants = new ArrayList<>();
        invariants.add(SafetyInvariant.verified(
                "diamond_safe",
                "Diamond operator preserves compile-time type inference",
                "OpenRewrite / Java 7+ idiom"));
        if (proposed.equals(original)) {
            invariants.add(SafetyInvariant.violated(
                    "rewrite_possible", "Could not rewrite to diamond", original));
        }
        int startLine = context.getLineNumber(node.getStartPosition());
        int endLine = context.getLineNumber(node.getStartPosition() + node.getLength() - 1);
        return RefactorCandidate.builder(RULE_ID, context.getSourceFilePath())
                .startLine(startLine)
                .endLine(endLine)
                .startPosition(node.getStartPosition())
                .length(node.getLength())
                .originalSnippet(original)
                .proposedSnippet(proposed)
                .confidenceScore(0.98)
                .riskTier(RiskTier.COSMETIC)
                .invariants(invariants)
                .analysisMetadata(Map.of("rule", RULE_ID))
                .rationale("Prefer diamond operator <> over redundant type arguments")
                .astNode(node)
                .build();
    }

    @Override
    public PatchUnit apply(RefactorCandidate candidate) {
        if (!candidate.allInvariantsVerified()) {
            throw new IllegalArgumentException("Cannot apply diamond rule: invariants violated");
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

    static String toDiamond(String creationExpression) {
        int newIdx = creationExpression.indexOf("new ");
        if (newIdx < 0) {
            return creationExpression;
        }
        int typeStart = newIdx + 4;
        while (typeStart < creationExpression.length()
                && Character.isWhitespace(creationExpression.charAt(typeStart))) {
            typeStart++;
        }
        int angle = creationExpression.indexOf('<', typeStart);
        if (angle < 0) {
            return creationExpression;
        }
        int depth = 0;
        int close = -1;
        for (int i = angle; i < creationExpression.length(); i++) {
            char c = creationExpression.charAt(i);
            if (c == '<') {
                depth++;
            } else if (c == '>') {
                depth--;
                if (depth == 0) {
                    close = i;
                    break;
                }
            }
        }
        if (close < 0) {
            return creationExpression;
        }
        return creationExpression.substring(0, angle) + "<>" + creationExpression.substring(close + 1);
    }

    private static boolean isLegacyCollectionType(Type type) {
        String name = simpleName(type);
        return "Vector".equals(name) || "Hashtable".equals(name)
                || "Stack".equals(name) || "StringBuffer".equals(name);
    }

    private static String simpleName(Type type) {
        if (type instanceof SimpleType simple) {
            return simple.getName().getFullyQualifiedName();
        }
        if (type instanceof ParameterizedType parameterized) {
            return simpleName(parameterized.getType());
        }
        if (type instanceof QualifiedType qt) {
            return qt.getName().getIdentifier();
        }
        if (type instanceof NameQualifiedType nqt) {
            return nqt.getName().getIdentifier();
        }
        return null;
    }
}
