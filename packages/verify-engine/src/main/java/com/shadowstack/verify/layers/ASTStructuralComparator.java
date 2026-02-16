package com.shadowstack.verify.layers;

import com.shadowstack.refactor.model.PatchUnit;
import com.shadowstack.verify.VerificationLayer;
import com.shadowstack.verify.model.VerificationContext;
import com.shadowstack.verify.model.VerificationLayerResult;
import com.shadowstack.verify.model.Verdict;
import org.eclipse.jdt.core.dom.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * Verification layer that compares the AST structure of original and transformed code.
 *
 * <p>Performs deep structural comparison of the AST trees, computing a similarity score
 * that quantifies how much the transformation changed the code structure. Small,
 * well-understood changes (like anonymous class → lambda) produce high similarity scores,
 * while large structural changes indicate higher risk.</p>
 *
 * <h3>Metrics Computed</h3>
 * <ul>
 *   <li><strong>Structural similarity</strong> — Ratio of unchanged AST subtrees</li>
 *   <li><strong>Node count delta</strong> — Difference in total AST node counts</li>
 *   <li><strong>Depth delta</strong> — Difference in maximum AST depth</li>
 *   <li><strong>AST hash</strong> — SHA-256 hash of normalized AST structure</li>
 * </ul>
 */
public class ASTStructuralComparator implements VerificationLayer {

    private static final Logger log = LoggerFactory.getLogger(ASTStructuralComparator.class);
    private static final String LAYER_ID = "ast_structural_comparator";
    private static final double DEFAULT_SIMILARITY_THRESHOLD = 0.70;

    @Override
    public String layerId() {
        return LAYER_ID;
    }

    @Override
    public VerificationLayerResult verify(PatchUnit patch, VerificationContext context) {
        Instant start = Instant.now();
        log.info("ASTStructuralComparator: comparing AST structure for patch {} in '{}'",
                patch.getPatchId(), patch.getSourceFile());

        VerificationLayerResult.Builder result = VerificationLayerResult.builder(LAYER_ID);
        double threshold = context.getConfig("astSimilarityThreshold", DEFAULT_SIMILARITY_THRESHOLD);

        String originalSource = context.getOriginalSource();
        String transformedSource = context.getTransformedSource();

        if (originalSource == null || transformedSource == null) {
            return result
                    .verdict(Verdict.WARN)
                    .riskContribution(0.05)
                    .summary("Source not available for AST comparison")
                    .executionTime(Duration.between(start, Instant.now()))
                    .build();
        }

        // Parse both sources into ASTs
        CompilationUnit originalAst = parseSource(originalSource);
        CompilationUnit transformedAst = parseSource(transformedSource);

        if (originalAst == null || transformedAst == null) {
            return result
                    .verdict(Verdict.WARN)
                    .riskContribution(0.10)
                    .summary("Failed to parse source into AST")
                    .addDiagnostic("AST parsing failed for " +
                            (originalAst == null ? "original" : "transformed") + " source")
                    .executionTime(Duration.between(start, Instant.now()))
                    .build();
        }

        // Compute metrics
        int originalNodeCount = countNodes(originalAst);
        int transformedNodeCount = countNodes(transformedAst);
        int originalDepth = computeMaxDepth(originalAst);
        int transformedDepth = computeMaxDepth(transformedAst);
        String originalHash = computeASTHash(originalAst);
        String transformedHash = computeASTHash(transformedAst);

        // Compute structural similarity
        Map<String, Integer> originalProfile = computeNodeProfile(originalAst);
        Map<String, Integer> transformedProfile = computeNodeProfile(transformedAst);
        double similarity = computeProfileSimilarity(originalProfile, transformedProfile);

        // Compute statement-level similarity for finer granularity
        List<String> originalStatements = extractNormalizedStatements(originalAst);
        List<String> transformedStatements = extractNormalizedStatements(transformedAst);
        double statementSimilarity = computeSequenceSimilarity(originalStatements, transformedStatements);

        // Weighted combined similarity
        double combinedSimilarity = 0.6 * similarity + 0.4 * statementSimilarity;

        result.addDetail("originalNodeCount", originalNodeCount);
        result.addDetail("transformedNodeCount", transformedNodeCount);
        result.addDetail("nodeCountDelta", transformedNodeCount - originalNodeCount);
        result.addDetail("originalDepth", originalDepth);
        result.addDetail("transformedDepth", transformedDepth);
        result.addDetail("depthDelta", transformedDepth - originalDepth);
        result.addDetail("originalAstHash", originalHash);
        result.addDetail("transformedAstHash", transformedHash);
        result.addDetail("nodeProfileSimilarity", similarity);
        result.addDetail("statementSimilarity", statementSimilarity);
        result.addDetail("combinedSimilarity", combinedSimilarity);
        result.addDetail("similarityThreshold", threshold);

        log.info("  Nodes: {} → {} (delta={}), Depth: {} → {} (delta={})",
                originalNodeCount, transformedNodeCount, transformedNodeCount - originalNodeCount,
                originalDepth, transformedDepth, transformedDepth - originalDepth);
        log.info("  Similarity: profile={:.3f}, statement={:.3f}, combined={:.3f} (threshold={:.3f})",
                similarity, statementSimilarity, combinedSimilarity, threshold);

        // Determine risk contribution based on delta magnitude
        double astDelta = 1.0 - combinedSimilarity;
        double riskContribution = astDelta > (1.0 - threshold) ? 0.10 : 0.0;

        if (combinedSimilarity < threshold) {
            log.warn("  AST similarity {:.3f} below threshold {:.3f}", combinedSimilarity, threshold);
            return result
                    .verdict(Verdict.WARN)
                    .riskContribution(riskContribution)
                    .summary("AST structural similarity (%.1f%%) below threshold (%.1f%%)"
                            .formatted(combinedSimilarity * 100, threshold * 100))
                    .addDiagnostic("Large structural change detected — review recommended")
                    .executionTime(Duration.between(start, Instant.now()))
                    .build();
        }

        if (originalHash.equals(transformedHash)) {
            log.info("  AST hashes match — transformation is cosmetic only");
        }

        return result
                .verdict(Verdict.PASS)
                .riskContribution(riskContribution)
                .summary("AST structural similarity %.1f%% (threshold %.1f%%)"
                        .formatted(combinedSimilarity * 100, threshold * 100))
                .executionTime(Duration.between(start, Instant.now()))
                .build();
    }

    /**
     * Parses Java source code into an Eclipse JDT CompilationUnit.
     */
    private CompilationUnit parseSource(String source) {
        try {
            ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
            parser.setSource(source.toCharArray());
            parser.setKind(ASTParser.K_COMPILATION_UNIT);
            parser.setResolveBindings(false);

            Map<String, String> options = JavaCore.getOptions();
            options.put(JavaCore.COMPILER_SOURCE, "21");
            options.put(JavaCore.COMPILER_COMPLIANCE, "21");
            options.put(JavaCore.COMPILER_CODEGEN_TARGET_PLATFORM, "21");
            parser.setCompilerOptions(options);

            return (CompilationUnit) parser.createAST(null);
        } catch (Exception e) {
            log.error("Failed to parse source: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Counts total AST nodes in a compilation unit.
     */
    private int countNodes(CompilationUnit cu) {
        int[] count = {0};
        cu.accept(new ASTVisitor() {
            @Override
            public void preVisit(ASTNode node) {
                count[0]++;
            }
        });
        return count[0];
    }

    /**
     * Computes the maximum depth of the AST tree.
     */
    private int computeMaxDepth(CompilationUnit cu) {
        int[] maxDepth = {0};
        cu.accept(new ASTVisitor() {
            @Override
            public void preVisit(ASTNode node) {
                int depth = 0;
                ASTNode current = node;
                while (current.getParent() != null) {
                    depth++;
                    current = current.getParent();
                }
                maxDepth[0] = Math.max(maxDepth[0], depth);
            }
        });
        return maxDepth[0];
    }

    /**
     * Computes a SHA-256 hash of the normalized AST structure (type-only, ignoring identifiers).
     */
    private String computeASTHash(CompilationUnit cu) {
        StringBuilder structure = new StringBuilder();
        cu.accept(new ASTVisitor() {
            @Override
            public void preVisit(ASTNode node) {
                structure.append(node.getNodeType()).append(':');
            }
        });

        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(structure.toString().getBytes());
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            return "hash-unavailable";
        }
    }

    /**
     * Builds a profile of node type frequencies.
     */
    private Map<String, Integer> computeNodeProfile(CompilationUnit cu) {
        Map<String, Integer> profile = new TreeMap<>();
        cu.accept(new ASTVisitor() {
            @Override
            public void preVisit(ASTNode node) {
                String typeName = ASTNode.nodeClassForType(node.getNodeType()).getSimpleName();
                profile.merge(typeName, 1, Integer::sum);
            }
        });
        return profile;
    }

    /**
     * Computes cosine similarity between two node profiles.
     */
    private double computeProfileSimilarity(Map<String, Integer> a, Map<String, Integer> b) {
        Set<String> allKeys = new TreeSet<>();
        allKeys.addAll(a.keySet());
        allKeys.addAll(b.keySet());

        double dotProduct = 0.0;
        double normA = 0.0;
        double normB = 0.0;

        for (String key : allKeys) {
            int valA = a.getOrDefault(key, 0);
            int valB = b.getOrDefault(key, 0);
            dotProduct += valA * valB;
            normA += valA * valA;
            normB += valB * valB;
        }

        if (normA == 0 || normB == 0) return 0.0;
        return dotProduct / (Math.sqrt(normA) * Math.sqrt(normB));
    }

    /**
     * Extracts normalized statement strings for sequence comparison.
     */
    private List<String> extractNormalizedStatements(CompilationUnit cu) {
        List<String> statements = new ArrayList<>();
        cu.accept(new ASTVisitor() {
            @Override
            public boolean visit(ExpressionStatement node) {
                statements.add(normalizeStatement(node.toString()));
                return false;
            }

            @Override
            public boolean visit(ReturnStatement node) {
                statements.add(normalizeStatement(node.toString()));
                return false;
            }

            @Override
            public boolean visit(VariableDeclarationStatement node) {
                statements.add(normalizeStatement(node.toString()));
                return false;
            }

            @Override
            public boolean visit(IfStatement node) {
                statements.add("IF:" + normalizeStatement(node.getExpression().toString()));
                return true;
            }
        });
        return statements;
    }

    private String normalizeStatement(String stmt) {
        return stmt.replaceAll("\\s+", " ").trim();
    }

    /**
     * Computes the Longest Common Subsequence similarity between two string sequences.
     */
    private double computeSequenceSimilarity(List<String> a, List<String> b) {
        if (a.isEmpty() && b.isEmpty()) return 1.0;
        if (a.isEmpty() || b.isEmpty()) return 0.0;

        int m = a.size();
        int n = b.size();
        int[][] dp = new int[m + 1][n + 1];

        for (int i = 1; i <= m; i++) {
            for (int j = 1; j <= n; j++) {
                if (a.get(i - 1).equals(b.get(j - 1))) {
                    dp[i][j] = dp[i - 1][j - 1] + 1;
                } else {
                    dp[i][j] = Math.max(dp[i - 1][j], dp[i][j - 1]);
                }
            }
        }

        int lcsLength = dp[m][n];
        return (2.0 * lcsLength) / (m + n);
    }
}
