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
 * Sonar S1943 / JDK charset constructor modernization.
 */
public class ReaderWriterCharsetCtorRule implements RefactorRule {

    private static final String RULE_ID = "READER_WRITER_CHARSET_CTOR";
    private static final Set<String> TYPES = Set.of("FileReader", "FileWriter", "InputStreamReader", "OutputStreamWriter", "Scanner", "Formatter");

    @Override public String ruleId() { return RULE_ID; }
    @Override public String ruleName() { return "FileReader/Writer/StreamReader → UTF-8 ctor"; }
    @Override public String description() { return "Migrates single-arg FileReader/FileWriter/InputStreamReader/OutputStreamWriter/Scanner/Formatter ctors to charset-aware overloads."; }
    @Override public RiskTier defaultRiskTier() { return RiskTier.LOW; }

    @Override
    public boolean appliesTo(ASTNode node, SemanticContext context) {
        if (!(node instanceof ClassInstanceCreation cic)) return false;
        String type = cic.getType().toString();
        String simple = type.contains(".") ? type.substring(type.lastIndexOf('.') + 1) : type;
        if (!TYPES.contains(simple) && !TYPES.contains(type)) return false;
        @SuppressWarnings("unchecked")
        List<Expression> args = cic.arguments();
        if (args == null) return false;
        // FileReader(File)/FileWriter(File)/InputStreamReader(InputStream)/OutputStreamWriter(OutputStream)/Scanner(InputStream)/Formatter()
        return args.size() == 1;
    }

    @Override
    public RefactorCandidate analyze(ASTNode node, SemanticContext context) {
        ClassInstanceCreation cic = (ClassInstanceCreation) node;
        String type = cic.getType().toString();
        @SuppressWarnings("unchecked")
        List<Expression> args = cic.arguments();
        String arg0 = args.get(0).toString();
        String original = context.getSourceRange(node.getStartPosition(), node.getLength());
        String proposed = "new " + type + "(" + arg0 + ", java.nio.charset.StandardCharsets.UTF_8)";
        int startLine = context.getLineNumber(node.getStartPosition());
        List<SafetyInvariant> invariants = new ArrayList<>();
        invariants.add(SafetyInvariant.verified("explicit_utf8",
                "Charset-bearing constructor avoids platform default encoding", proposed));
        return RefactorCandidate.builder(RULE_ID, context.getSourceFilePath())
                .startLine(startLine).endLine(startLine)
                .startPosition(node.getStartPosition()).length(node.getLength())
                .originalSnippet(original).proposedSnippet(proposed)
                .confidenceScore(0.9).riskTier(RiskTier.LOW)
                .invariants(invariants)
                .analysisMetadata(Map.of("type", type))
                .rationale("Prefer charset-aware constructors over platform-default encoding")
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
