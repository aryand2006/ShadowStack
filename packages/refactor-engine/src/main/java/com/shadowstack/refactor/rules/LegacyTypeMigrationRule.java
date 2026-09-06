package com.shadowstack.refactor.rules;

import com.shadowstack.refactor.RefactorRule;
import com.shadowstack.refactor.model.*;
import org.eclipse.jdt.core.dom.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Renames legacy JDK types to modern replacements (OpenRewrite / Sonar classics).
 */
public class LegacyTypeMigrationRule implements RefactorRule {

    private final String ruleId;
    private final String ruleName;
    private final String fromSimple;
    private final String toSimple;
    private final String fromFqn;
    private final String toFqn;
    private final RiskTier risk;
    private final double confidence;
    private final Pattern wordBoundary;

    public LegacyTypeMigrationRule(String ruleId, String ruleName,
                                   String fromSimple, String toSimple,
                                   String fromFqn, String toFqn,
                                   RiskTier risk, double confidence) {
        this.ruleId = ruleId;
        this.ruleName = ruleName;
        this.fromSimple = fromSimple;
        this.toSimple = toSimple;
        this.fromFqn = fromFqn;
        this.toFqn = toFqn;
        this.risk = risk;
        this.confidence = confidence;
        this.wordBoundary = Pattern.compile("\\b" + Pattern.quote(fromSimple) + "\\b");
    }

    public static LegacyTypeMigrationRule stringBuffer() {
        return new LegacyTypeMigrationRule(
                "STRINGBUFFER_TO_STRINGBUILDER", "StringBuffer → StringBuilder",
                "StringBuffer", "StringBuilder",
                "java.lang.StringBuffer", "java.lang.StringBuilder",
                RiskTier.LOW, 0.93);
    }

    public static LegacyTypeMigrationRule vector() {
        return new LegacyTypeMigrationRule(
                "VECTOR_TO_ARRAYLIST", "Vector → ArrayList",
                "Vector", "ArrayList",
                "java.util.Vector", "java.util.ArrayList",
                RiskTier.MEDIUM, 0.85);
    }

    public static LegacyTypeMigrationRule hashtable() {
        return new LegacyTypeMigrationRule(
                "HASHTABLE_TO_HASHMAP", "Hashtable → HashMap",
                "Hashtable", "HashMap",
                "java.util.Hashtable", "java.util.HashMap",
                RiskTier.MEDIUM, 0.85);
    }

    public static LegacyTypeMigrationRule stack() {
        return new LegacyTypeMigrationRule(
                "STACK_TO_ARRAYDEQUE", "Stack → ArrayDeque",
                "Stack", "ArrayDeque",
                "java.util.Stack", "java.util.ArrayDeque",
                RiskTier.MEDIUM, 0.8);
    }

    @Override
    public String ruleId() {
        return ruleId;
    }

    @Override
    public String ruleName() {
        return ruleName;
    }

    @Override
    public String description() {
        return "Migrates legacy type " + fromSimple + " to modern " + toSimple + ".";
    }

    @Override
    public RiskTier defaultRiskTier() {
        return risk;
    }

    @Override
    public boolean appliesTo(ASTNode node, SemanticContext context) {
        if (node instanceof ImportDeclaration imp) {
            String q = imp.getName().getFullyQualifiedName();
            return q.equals(fromFqn) || q.equals(fromSimple) || q.endsWith("." + fromSimple);
        }
        if (node instanceof ClassInstanceCreation creation) {
            return typeNameMatches(creation.getType());
        }
        if (node instanceof SimpleType simple) {
            return nameMatches(simple.getName());
        }
        if (node instanceof ParameterizedType parameterized) {
            return typeNameMatches(parameterized.getType());
        }
        return false;
    }

    @Override
    public RefactorCandidate analyze(ASTNode node, SemanticContext context) {
        String original = context.getSourceRange(node.getStartPosition(), node.getLength());
        String proposed;
        if (node instanceof ImportDeclaration) {
            proposed = original.replace(fromFqn, toFqn).replace(fromSimple, toSimple);
        } else if (node instanceof ClassInstanceCreation) {
            proposed = wordBoundary.matcher(original).replaceAll(toSimple);
            if (proposed.contains("<") && !proposed.contains("<>")) {
                proposed = DiamondOperatorRule.toDiamond(proposed);
            }
        } else {
            proposed = wordBoundary.matcher(original).replaceAll(toSimple);
        }

        List<SafetyInvariant> invariants = new ArrayList<>();
        invariants.add(SafetyInvariant.verified(
                "jdk_type_migration",
                fromSimple + " → " + toSimple + " is a documented JDK modernization",
                fromFqn + " → " + toFqn));
        if (fromSimple.equals("Vector") || fromSimple.equals("Hashtable") || fromSimple.equals("Stack")) {
            invariants.add(SafetyInvariant.verified(
                    "sync_semantics_noted",
                    "Replacement drops legacy synchronization; use concurrent collections if needed",
                    "Review concurrent use sites"));
        }
        if (proposed.equals(original)) {
            invariants.add(SafetyInvariant.violated(
                    "rewrite_possible", "Could not rewrite type name", original));
        }

        int startLine = context.getLineNumber(node.getStartPosition());
        int endLine = context.getLineNumber(node.getStartPosition() + Math.max(node.getLength() - 1, 0));
        return RefactorCandidate.builder(ruleId, context.getSourceFilePath())
                .startLine(startLine)
                .endLine(endLine)
                .startPosition(node.getStartPosition())
                .length(node.getLength())
                .originalSnippet(original)
                .proposedSnippet(proposed)
                .confidenceScore(confidence)
                .riskTier(risk)
                .invariants(invariants)
                .analysisMetadata(Map.of("from", fromSimple, "to", toSimple))
                .rationale(ruleName + " — preferred modern JDK API")
                .astNode(node)
                .build();
    }

    @Override
    public PatchUnit apply(RefactorCandidate candidate) {
        if (!candidate.allInvariantsVerified()) {
            throw new IllegalArgumentException("Cannot apply " + ruleId);
        }
        return PatchUnit.builder(ruleId, candidate.getSourceFile())
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

    private boolean typeNameMatches(Type type) {
        if (type instanceof SimpleType simple) {
            return nameMatches(simple.getName());
        }
        if (type instanceof QualifiedType qt) {
            return fromSimple.equals(qt.getName().getIdentifier());
        }
        if (type instanceof NameQualifiedType nqt) {
            return fromSimple.equals(nqt.getName().getIdentifier());
        }
        if (type instanceof ParameterizedType parameterized) {
            return typeNameMatches(parameterized.getType());
        }
        return false;
    }

    private boolean nameMatches(Name name) {
        String fq = name.getFullyQualifiedName();
        return fromSimple.equals(fq) || fromFqn.equals(fq) || fq.endsWith("." + fromSimple);
    }
}
