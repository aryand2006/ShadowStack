package com.shadowstack.analysis;

import org.eclipse.jdt.core.dom.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Computes a comprehensive suite of complexity metrics for Java source files.
 *
 * <h3>Supported metrics</h3>
 * <ul>
 *   <li><strong>Cyclomatic complexity</strong> — counts linearly independent paths
 *       through a method's control-flow graph (McCabe, 1976).</li>
 *   <li><strong>Cognitive complexity</strong> — Sonar-style metric that penalises
 *       nesting depth and non-linear flow breaks (G. Ann Campbell, 2017).</li>
 *   <li><strong>Lines of code</strong> — physical LOC per method, class, and file.</li>
 *   <li><strong>Method count &amp; class count</strong> per compilation unit.</li>
 *   <li><strong>Maximum nesting depth</strong> per method.</li>
 * </ul>
 *
 * <p>All analysis is performed on Eclipse JDT AST nodes. The class is stateless
 * and thread-safe; each public method creates its own visitor.</p>
 *
 * @see ComplexityReport
 */
public final class ComplexityMetrics {

    private static final Logger LOG = LoggerFactory.getLogger(ComplexityMetrics.class);

    private ComplexityMetrics() {
        throw new AssertionError("Utility class — do not instantiate");
    }

    // ─── Public API ──────────────────────────────────────────────────

    /**
     * Analyses a single compilation unit and returns a full complexity report.
     *
     * @param cu       the parsed compilation unit (must not be null)
     * @param filePath path to the source file (for reporting only)
     * @return an immutable {@link ComplexityReport}
     */
    public static ComplexityReport analyze(CompilationUnit cu, Path filePath) {
        Objects.requireNonNull(cu, "compilation unit must not be null");
        Objects.requireNonNull(filePath, "filePath must not be null");

        LOG.debug("Analyzing complexity for {}", filePath);

        UnitVisitor visitor = new UnitVisitor(cu);
        cu.accept(visitor);

        ComplexityReport report = new ComplexityReport(
                filePath.toString(),
                Collections.unmodifiableMap(visitor.cyclomaticByMethod),
                Collections.unmodifiableMap(visitor.cognitiveByMethod),
                Collections.unmodifiableMap(visitor.locByMethod),
                Collections.unmodifiableMap(visitor.nestingDepthByMethod),
                visitor.classCount,
                visitor.methodCount,
                visitor.totalLoc);

        LOG.info("Complexity report for {}: {} classes, {} methods, total LOC {}",
                filePath, report.classCount(), report.methodCount(), report.totalLoc());

        return report;
    }

    /**
     * Computes the cyclomatic complexity of a single method declaration.
     *
     * @param method the method AST node
     * @return cyclomatic complexity (>= 1)
     */
    public static int cyclomaticComplexity(MethodDeclaration method) {
        Objects.requireNonNull(method, "method must not be null");
        CyclomaticVisitor visitor = new CyclomaticVisitor();
        method.accept(visitor);
        return visitor.complexity;
    }

    /**
     * Computes the cognitive complexity of a single method declaration.
     *
     * @param method the method AST node
     * @return cognitive complexity (>= 0)
     */
    public static int cognitiveComplexity(MethodDeclaration method) {
        Objects.requireNonNull(method, "method must not be null");
        CognitiveVisitor visitor = new CognitiveVisitor();
        method.accept(visitor);
        return visitor.complexity;
    }

    /**
     * Computes the maximum nesting depth in a method.
     *
     * @param method the method AST node
     * @return maximum nesting depth (0 means a flat method)
     */
    public static int maxNestingDepth(MethodDeclaration method) {
        Objects.requireNonNull(method, "method must not be null");
        NestingVisitor visitor = new NestingVisitor();
        method.accept(visitor);
        return visitor.maxDepth;
    }

    // ─── Helpers ─────────────────────────────────────────────────────

    /**
     * Resolves a fully-qualified method key from a MethodDeclaration.
     */
    static String methodKey(MethodDeclaration method) {
        ASTNode parent = method.getParent();
        String className = "<anonymous>";
        if (parent instanceof TypeDeclaration td) {
            className = td.getName().getIdentifier();
            // Walk up to capture outer class names
            ASTNode outer = td.getParent();
            while (outer instanceof TypeDeclaration outerTd) {
                className = outerTd.getName().getIdentifier() + "." + className;
                outer = outerTd.getParent();
            }
        } else if (parent instanceof AnonymousClassDeclaration) {
            className = "<anonymous>";
        }
        return className + "#" + method.getName().getIdentifier();
    }

    /**
     * Counts physical lines of code between two positions in the compilation unit.
     */
    private static int computeLoc(CompilationUnit cu, ASTNode node) {
        int startLine = cu.getLineNumber(node.getStartPosition());
        int endLine = cu.getLineNumber(node.getStartPosition() + node.getLength() - 1);
        return Math.max(1, endLine - startLine + 1);
    }

    // ═════════════════════════════════════════════════════════════════
    //  Unit-level visitor
    // ═════════════════════════════════════════════════════════════════

    /**
     * Walks the full compilation unit collecting per-method and aggregate metrics.
     */
    private static final class UnitVisitor extends ASTVisitor {
        private final CompilationUnit cu;

        final Map<String, Integer> cyclomaticByMethod = new LinkedHashMap<>();
        final Map<String, Integer> cognitiveByMethod = new LinkedHashMap<>();
        final Map<String, Integer> locByMethod = new LinkedHashMap<>();
        final Map<String, Integer> nestingDepthByMethod = new LinkedHashMap<>();
        int classCount = 0;
        int methodCount = 0;
        int totalLoc = 0;

        UnitVisitor(CompilationUnit cu) {
            this.cu = cu;
            this.totalLoc = computeLoc(cu, cu);
        }

        @Override
        public boolean visit(TypeDeclaration node) {
            classCount++;
            return true;
        }

        @Override
        public boolean visit(EnumDeclaration node) {
            classCount++;
            return true;
        }

        @Override
        public boolean visit(MethodDeclaration node) {
            methodCount++;
            String key = methodKey(node);

            cyclomaticByMethod.put(key, cyclomaticComplexity(node));
            cognitiveByMethod.put(key, cognitiveComplexity(node));
            locByMethod.put(key, computeLoc(cu, node));
            nestingDepthByMethod.put(key, maxNestingDepth(node));

            // Don't recurse into nested classes from here — the TypeDeclaration visitor handles them
            return false;
        }
    }

    // ═════════════════════════════════════════════════════════════════
    //  Cyclomatic complexity visitor
    // ═════════════════════════════════════════════════════════════════

    /**
     * Counts decision points to compute cyclomatic complexity.
     *
     * <p>Cyclomatic complexity starts at 1 (the method entry) and increments for each:
     * {@code if}, {@code for}, {@code while}, {@code do-while}, {@code case},
     * {@code catch}, {@code &&}, {@code ||}, {@code ?:} (ternary), and {@code throw}.</p>
     */
    private static final class CyclomaticVisitor extends ASTVisitor {
        int complexity = 1;

        @Override
        public boolean visit(IfStatement node) {
            complexity++;
            return true;
        }

        @Override
        public boolean visit(ForStatement node) {
            complexity++;
            return true;
        }

        @Override
        public boolean visit(EnhancedForStatement node) {
            complexity++;
            return true;
        }

        @Override
        public boolean visit(WhileStatement node) {
            complexity++;
            return true;
        }

        @Override
        public boolean visit(DoStatement node) {
            complexity++;
            return true;
        }

        @Override
        public boolean visit(SwitchCase node) {
            if (!node.isDefault()) {
                complexity++;
            }
            return true;
        }

        @Override
        public boolean visit(CatchClause node) {
            complexity++;
            return true;
        }

        @Override
        public boolean visit(ConditionalExpression node) {
            complexity++;
            return true;
        }

        @Override
        public boolean visit(InfixExpression node) {
            InfixExpression.Operator op = node.getOperator();
            if (op == InfixExpression.Operator.CONDITIONAL_AND ||
                    op == InfixExpression.Operator.CONDITIONAL_OR) {
                complexity++;
                // Count extended operands (e.g. a || b || c has one extra)
                complexity += node.extendedOperands().size();
            }
            return true;
        }

        @Override
        public boolean visit(ThrowStatement node) {
            complexity++;
            return true;
        }
    }

    // ═════════════════════════════════════════════════════════════════
    //  Cognitive complexity visitor
    // ═════════════════════════════════════════════════════════════════

    /**
     * Computes cognitive complexity following the Sonar model.
     *
     * <p>Increments:
     * <ul>
     *   <li><strong>+1</strong> for each structural break of linear flow
     *       ({@code if}, {@code else if}, {@code else}, loops, {@code catch},
     *       {@code switch}, ternary, logical operators that break a sequence).</li>
     *   <li><strong>+nesting</strong> for structures nested inside other structures.</li>
     * </ul>
     *
     * <p>Does <em>not</em> increment for methods, lambdas (treated as flattening),
     * or simple early returns.</p>
     */
    private static final class CognitiveVisitor extends ASTVisitor {
        int complexity = 0;
        int nesting = 0;

        @Override
        public boolean visit(IfStatement node) {
            // +1 for 'if', plus nesting penalty
            complexity += 1 + nesting;
            nesting++;
            if (node.getThenStatement() != null) {
                node.getThenStatement().accept(this);
            }
            nesting--;

            // Handle else-if chain (only +1, no nesting increment)
            Statement elseStmt = node.getElseStatement();
            if (elseStmt instanceof IfStatement) {
                complexity += 1; // else-if: +1, no nesting penalty
                ((IfStatement) elseStmt).getThenStatement().accept(this);
                Statement nestedElse = ((IfStatement) elseStmt).getElseStatement();
                if (nestedElse != null) {
                    if (nestedElse instanceof IfStatement) {
                        // Recursively handle further else-if chains
                        visit((IfStatement) nestedElse);
                    } else {
                        complexity += 1; // final else: +1
                        nestedElse.accept(this);
                    }
                }
            } else if (elseStmt != null) {
                complexity += 1; // else: +1
                nesting++;
                elseStmt.accept(this);
                nesting--;
            }
            return false; // We manually traversed children
        }

        @Override
        public boolean visit(ForStatement node) {
            complexity += 1 + nesting;
            nesting++;
            return true;
        }

        @Override
        public void endVisit(ForStatement node) {
            nesting--;
        }

        @Override
        public boolean visit(EnhancedForStatement node) {
            complexity += 1 + nesting;
            nesting++;
            return true;
        }

        @Override
        public void endVisit(EnhancedForStatement node) {
            nesting--;
        }

        @Override
        public boolean visit(WhileStatement node) {
            complexity += 1 + nesting;
            nesting++;
            return true;
        }

        @Override
        public void endVisit(WhileStatement node) {
            nesting--;
        }

        @Override
        public boolean visit(DoStatement node) {
            complexity += 1 + nesting;
            nesting++;
            return true;
        }

        @Override
        public void endVisit(DoStatement node) {
            nesting--;
        }

        @Override
        public boolean visit(SwitchStatement node) {
            complexity += 1 + nesting;
            nesting++;
            return true;
        }

        @Override
        public void endVisit(SwitchStatement node) {
            nesting--;
        }

        @Override
        public boolean visit(CatchClause node) {
            complexity += 1 + nesting;
            nesting++;
            return true;
        }

        @Override
        public void endVisit(CatchClause node) {
            nesting--;
        }

        @Override
        public boolean visit(ConditionalExpression node) {
            complexity += 1 + nesting;
            return true;
        }

        @Override
        public boolean visit(InfixExpression node) {
            InfixExpression.Operator op = node.getOperator();
            if (op == InfixExpression.Operator.CONDITIONAL_AND ||
                    op == InfixExpression.Operator.CONDITIONAL_OR) {
                complexity += 1;
            }
            return true;
        }

        @Override
        public boolean visit(BreakStatement node) {
            if (node.getLabel() != null) {
                complexity += 1; // Labeled break: +1
            }
            return true;
        }

        @Override
        public boolean visit(ContinueStatement node) {
            if (node.getLabel() != null) {
                complexity += 1; // Labeled continue: +1
            }
            return true;
        }

        @Override
        public boolean visit(LambdaExpression node) {
            // Lambdas increase nesting but no structural increment
            nesting++;
            return true;
        }

        @Override
        public void endVisit(LambdaExpression node) {
            nesting--;
        }
    }

    // ═════════════════════════════════════════════════════════════════
    //  Nesting depth visitor
    // ═════════════════════════════════════════════════════════════════

    /**
     * Tracks maximum nesting depth within a method body.
     */
    private static final class NestingVisitor extends ASTVisitor {
        int currentDepth = 0;
        int maxDepth = 0;

        private void enter() {
            currentDepth++;
            maxDepth = Math.max(maxDepth, currentDepth);
        }

        private void leave() {
            currentDepth--;
        }

        @Override public boolean visit(IfStatement n)          { enter(); return true; }
        @Override public void endVisit(IfStatement n)          { leave(); }
        @Override public boolean visit(ForStatement n)         { enter(); return true; }
        @Override public void endVisit(ForStatement n)         { leave(); }
        @Override public boolean visit(EnhancedForStatement n) { enter(); return true; }
        @Override public void endVisit(EnhancedForStatement n) { leave(); }
        @Override public boolean visit(WhileStatement n)       { enter(); return true; }
        @Override public void endVisit(WhileStatement n)       { leave(); }
        @Override public boolean visit(DoStatement n)          { enter(); return true; }
        @Override public void endVisit(DoStatement n)          { leave(); }
        @Override public boolean visit(SwitchStatement n)      { enter(); return true; }
        @Override public void endVisit(SwitchStatement n)      { leave(); }
        @Override public boolean visit(TryStatement n)         { enter(); return true; }
        @Override public void endVisit(TryStatement n)         { leave(); }
        @Override public boolean visit(SynchronizedStatement n){ enter(); return true; }
        @Override public void endVisit(SynchronizedStatement n){ leave(); }
        @Override public boolean visit(LambdaExpression n)     { enter(); return true; }
        @Override public void endVisit(LambdaExpression n)     { leave(); }
    }

    // ═════════════════════════════════════════════════════════════════
    //  ComplexityReport DTO
    // ═════════════════════════════════════════════════════════════════

    /**
     * Immutable complexity report for a single source file.
     *
     * @param filePath             path to the analyzed source file
     * @param cyclomaticByMethod   cyclomatic complexity per method key
     * @param cognitiveByMethod    cognitive complexity per method key
     * @param locByMethod          lines of code per method key
     * @param nestingDepthByMethod max nesting depth per method key
     * @param classCount           number of classes in the file
     * @param methodCount          number of methods in the file
     * @param totalLoc             total lines of code in the file
     */
    public record ComplexityReport(
            String filePath,
            Map<String, Integer> cyclomaticByMethod,
            Map<String, Integer> cognitiveByMethod,
            Map<String, Integer> locByMethod,
            Map<String, Integer> nestingDepthByMethod,
            int classCount,
            int methodCount,
            int totalLoc
    ) {
        public ComplexityReport {
            Objects.requireNonNull(filePath, "filePath");
            cyclomaticByMethod = cyclomaticByMethod == null ? Map.of() : Map.copyOf(cyclomaticByMethod);
            cognitiveByMethod = cognitiveByMethod == null ? Map.of() : Map.copyOf(cognitiveByMethod);
            locByMethod = locByMethod == null ? Map.of() : Map.copyOf(locByMethod);
            nestingDepthByMethod = nestingDepthByMethod == null ? Map.of() : Map.copyOf(nestingDepthByMethod);
        }

        /** Returns the maximum cyclomatic complexity across all methods. */
        public int maxCyclomaticComplexity() {
            return cyclomaticByMethod.values().stream().mapToInt(Integer::intValue).max().orElse(0);
        }

        /** Returns the average cyclomatic complexity across all methods. */
        public double averageCyclomaticComplexity() {
            return cyclomaticByMethod.values().stream().mapToInt(Integer::intValue).average().orElse(0.0);
        }

        /** Returns the maximum cognitive complexity across all methods. */
        public int maxCognitiveComplexity() {
            return cognitiveByMethod.values().stream().mapToInt(Integer::intValue).max().orElse(0);
        }

        /** Returns the maximum nesting depth across all methods. */
        public int maxNestingDepth() {
            return nestingDepthByMethod.values().stream().mapToInt(Integer::intValue).max().orElse(0);
        }
    }
}
