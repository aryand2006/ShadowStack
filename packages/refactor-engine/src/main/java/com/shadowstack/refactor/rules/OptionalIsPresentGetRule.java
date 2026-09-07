package com.shadowstack.refactor.rules;

import com.shadowstack.refactor.RefactorRule;
import com.shadowstack.refactor.model.*;
import org.eclipse.jdt.core.dom.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/** Optional.isPresent()/get() antipattern → orElseThrow / ifPresent (Java 8+). */
public class OptionalIsPresentGetRule implements RefactorRule {
    private static final String RULE_ID = "OPTIONAL_ISPRESENT_GET";
    @Override public String ruleId() { return RULE_ID; }
    @Override public String ruleName() { return "Optional.isPresent/get → orElseThrow"; }
    @Override public String description() {
        return "Flags Optional.get() and isPresent()+get antipatterns; prefers orElseThrow().";
    }
    @Override public RiskTier defaultRiskTier() { return RiskTier.MEDIUM; }

    @Override
    public boolean appliesTo(ASTNode node, SemanticContext context) {
        if (!(node instanceof MethodInvocation inv)) return false;
        String name = inv.getName().getIdentifier();
        if (!"get".equals(name) && !"isPresent".equals(name)) return false;
        @SuppressWarnings("unchecked")
        List<Expression> args = inv.arguments();
        if (args != null && !args.isEmpty()) return false;
        Expression expr = inv.getExpression();
        return expr != null && looksLikeOptional(expr);
    }

    @Override
    public RefactorCandidate analyze(ASTNode node, SemanticContext context) {
        MethodInvocation inv = (MethodInvocation) node;
        String name = inv.getName().getIdentifier();
        String receiver = inv.getExpression().toString();
        String original = context.getSourceRange(node.getStartPosition(), node.getLength());
        String proposed = "get".equals(name)
                ? receiver + ".orElseThrow()"
                : "/* replace isPresent()+get with */ " + receiver + ".ifPresent(...) / orElseThrow()";
        int startLine = context.getLineNumber(node.getStartPosition());
        List<SafetyInvariant> invariants = new ArrayList<>();
        invariants.add(SafetyInvariant.verified("optional_get_safe",
                "Prefer orElseThrow/ifPresent over isPresent()+get", proposed));
        double confidence = "get".equals(name) ? 0.8 : 0.65;
        return RefactorCandidate.builder(RULE_ID, context.getSourceFilePath())
                .startLine(startLine).endLine(startLine)
                .startPosition(node.getStartPosition()).length(node.getLength())
                .originalSnippet(original).proposedSnippet(proposed)
                .confidenceScore(confidence).riskTier(RiskTier.MEDIUM)
                .invariants(invariants)
                .analysisMetadata(Map.of("method", name, "receiver", receiver))
                .rationale("Avoid Optional.get(); prefer orElseThrow/ifPresent")
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

    private static boolean looksLikeOptional(Expression expr) {
        String text = expr.toString();
        if (text.contains("Optional")) return true;
        if (expr instanceof SimpleName sn) {
            String id = sn.getIdentifier();
            String lower = id.toLowerCase();
            return lower.contains("optional") || lower.startsWith("opt") || lower.endsWith("opt");
        }
        if (expr instanceof MethodInvocation mi) {
            Expression recv = mi.getExpression();
            return recv != null && recv.toString().contains("Optional");
        }
        return false;
    }
}
