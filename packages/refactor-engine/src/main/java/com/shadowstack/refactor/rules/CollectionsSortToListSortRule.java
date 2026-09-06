package com.shadowstack.refactor.rules;

import com.shadowstack.refactor.RefactorRule;
import com.shadowstack.refactor.model.*;
import org.eclipse.jdt.core.dom.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * {@code Collections.sort(list[, cmp])} → {@code list.sort(cmp|null)}.
 */
public class CollectionsSortToListSortRule implements RefactorRule {

    private static final String RULE_ID = "COLLECTIONS_SORT_TO_LIST_SORT";

    @Override
    public String ruleId() {
        return RULE_ID;
    }

    @Override
    public String ruleName() {
        return "Collections.sort → List.sort";
    }

    @Override
    public String description() {
        return "Replaces Collections.sort with List.sort (Java 8+).";
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
        if (!"sort".equals(inv.getName().getIdentifier())) {
            return false;
        }
        Expression expr = inv.getExpression();
        if (!(expr instanceof Name name)) {
            return false;
        }
        String qualifier = name.getFullyQualifiedName();
        if (!"Collections".equals(qualifier) && !"java.util.Collections".equals(qualifier)) {
            return false;
        }
        @SuppressWarnings("unchecked")
        List<Expression> args = inv.arguments();
        return args != null && (args.size() == 1 || args.size() == 2);
    }

    @Override
    public RefactorCandidate analyze(ASTNode node, SemanticContext context) {
        MethodInvocation inv = (MethodInvocation) node;
        @SuppressWarnings("unchecked")
        List<Expression> args = inv.arguments();
        String listExpr = args.get(0).toString();
        String proposed = args.size() == 1
                ? listExpr + ".sort(null)"
                : listExpr + ".sort(" + args.get(1) + ")";
        String original = context.getSourceRange(node.getStartPosition(), node.getLength());
        int startLine = context.getLineNumber(node.getStartPosition());
        int endLine = context.getLineNumber(node.getStartPosition() + node.getLength() - 1);

        List<SafetyInvariant> invariants = new ArrayList<>();
        invariants.add(SafetyInvariant.verified(
                "list_sort_equivalent",
                "List.sort is behaviorally equivalent to Collections.sort for List instances",
                "Java 8 instance method"));

        return RefactorCandidate.builder(RULE_ID, context.getSourceFilePath())
                .startLine(startLine)
                .endLine(endLine)
                .startPosition(node.getStartPosition())
                .length(node.getLength())
                .originalSnippet(original)
                .proposedSnippet(proposed)
                .confidenceScore(0.92)
                .riskTier(RiskTier.LOW)
                .invariants(invariants)
                .analysisMetadata(Map.of("list", listExpr))
                .rationale("Prefer List.sort over Collections.sort")
                .astNode(node)
                .build();
    }

    @Override
    public PatchUnit apply(RefactorCandidate candidate) {
        if (!candidate.allInvariantsVerified()) {
            throw new IllegalArgumentException("Cannot apply Collections.sort rule");
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
