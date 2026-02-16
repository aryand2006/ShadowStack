package com.shadowstack.analysis;

import org.eclipse.jdt.core.dom.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Performs intraprocedural data-flow analysis on Java method bodies.
 *
 * <p>This analyzer walks the AST of a single method and builds <em>def-use chains</em>
 * — mapping each variable definition site to all sites that read that definition.
 * It also identifies:</p>
 * <ul>
 *   <li><strong>Effectively-final variables</strong> — variables assigned exactly once
 *       (eligible for use in lambdas and anonymous classes).</li>
 *   <li><strong>Mutations in lambdas/anonymous classes</strong> — variables that are
 *       mutated across scope boundaries, which blocks lambda conversion.</li>
 *   <li><strong>Dead definitions</strong> — definitions that are never used.</li>
 * </ul>
 *
 * <h3>Scope</h3>
 * <p>This is an intraprocedural, flow-insensitive approximation suitable for
 * refactoring safety analysis. It does <em>not</em> perform full reaching-definitions
 * analysis with fixed-point iteration — instead it uses a single-pass AST walk
 * that is fast and sufficient for the mutation-safety checks ShadowStack needs.</p>
 *
 * <p>Thread-safe: each analysis creates its own visitor state.</p>
 *
 * @see DataFlowResult
 * @see MutationAnalyzer
 */
public final class DataFlowAnalyzer {

    private static final Logger LOG = LoggerFactory.getLogger(DataFlowAnalyzer.class);

    private DataFlowAnalyzer() {
        throw new AssertionError("Utility class — do not instantiate");
    }

    // ─── Public API ──────────────────────────────────────────────────

    /**
     * Analyzes a single method body for data-flow information.
     *
     * @param method the method to analyze (must have a body)
     * @param cu     the enclosing compilation unit (for line number resolution)
     * @return an immutable {@link DataFlowResult}
     * @throws NullPointerException if either argument is null
     */
    public static DataFlowResult analyze(MethodDeclaration method, CompilationUnit cu) {
        Objects.requireNonNull(method, "method must not be null");
        Objects.requireNonNull(cu, "compilation unit must not be null");

        String methodKey = ComplexityMetrics.methodKey(method);
        LOG.debug("Analyzing data flow for method: {}", methodKey);

        if (method.getBody() == null) {
            LOG.debug("Method {} has no body (abstract or native) — returning empty result", methodKey);
            return DataFlowResult.empty(methodKey);
        }

        DataFlowVisitor visitor = new DataFlowVisitor(cu);
        method.getBody().accept(visitor);

        // Also register parameters as definitions
        @SuppressWarnings("unchecked")
        List<SingleVariableDeclaration> params = method.parameters();
        for (SingleVariableDeclaration param : params) {
            String varName = param.getName().getIdentifier();
            int line = cu.getLineNumber(param.getStartPosition());
            visitor.definitions.computeIfAbsent(varName, k -> new ArrayList<>())
                    .add(new DefSite(varName, line, DefSite.Kind.PARAMETER, false));
        }

        // Build the result
        Map<String, List<DefSite>> definitions = new LinkedHashMap<>();
        for (var entry : visitor.definitions.entrySet()) {
            definitions.put(entry.getKey(), List.copyOf(entry.getValue()));
        }

        Map<String, List<UseSite>> uses = new LinkedHashMap<>();
        for (var entry : visitor.uses.entrySet()) {
            uses.put(entry.getKey(), List.copyOf(entry.getValue()));
        }

        // Compute effectively-final variables
        Set<String> effectivelyFinal = new LinkedHashSet<>();
        for (var entry : visitor.definitions.entrySet()) {
            String varName = entry.getKey();
            List<DefSite> defs = entry.getValue();
            long assignmentCount = defs.stream()
                    .filter(d -> d.kind() == DefSite.Kind.ASSIGNMENT ||
                            d.kind() == DefSite.Kind.DECLARATION_WITH_INIT)
                    .count();
            boolean hasMutationInLambda = defs.stream().anyMatch(DefSite::inLambdaOrAnonymous);
            // A variable is effectively final if assigned at most once and never mutated in a lambda
            if (assignmentCount <= 1 && !hasMutationInLambda) {
                effectivelyFinal.add(varName);
            }
        }

        // Detect mutations in lambdas/anonymous classes
        Set<String> mutatedInLambda = new LinkedHashSet<>();
        for (var entry : visitor.definitions.entrySet()) {
            if (entry.getValue().stream().anyMatch(DefSite::inLambdaOrAnonymous)) {
                mutatedInLambda.add(entry.getKey());
            }
        }

        // Detect dead definitions (defined but never used)
        Set<String> deadDefinitions = new LinkedHashSet<>();
        for (String varName : definitions.keySet()) {
            List<UseSite> varUses = uses.getOrDefault(varName, List.of());
            if (varUses.isEmpty()) {
                deadDefinitions.add(varName);
            }
        }

        DataFlowResult result = new DataFlowResult(
                methodKey,
                Collections.unmodifiableMap(definitions),
                Collections.unmodifiableMap(uses),
                Set.copyOf(effectivelyFinal),
                Set.copyOf(mutatedInLambda),
                Set.copyOf(deadDefinitions));

        LOG.debug("Data flow for {}: {} vars defined, {} effectively final, {} mutated in lambda",
                methodKey, definitions.size(), effectivelyFinal.size(), mutatedInLambda.size());

        return result;
    }

    /**
     * Convenience: analyzes all methods in a compilation unit.
     *
     * @param cu the compilation unit
     * @return list of data-flow results, one per method
     */
    public static List<DataFlowResult> analyzeAll(CompilationUnit cu) {
        Objects.requireNonNull(cu, "compilation unit must not be null");

        List<DataFlowResult> results = new ArrayList<>();
        cu.accept(new ASTVisitor() {
            @Override
            public boolean visit(MethodDeclaration node) {
                results.add(analyze(node, cu));
                return false; // don't recurse into nested methods
            }
        });
        return List.copyOf(results);
    }

    // ═════════════════════════════════════════════════════════════════
    //  AST Visitor
    // ═════════════════════════════════════════════════════════════════

    /**
     * Single-pass AST visitor that collects definition and use sites.
     */
    private static final class DataFlowVisitor extends ASTVisitor {
        private final CompilationUnit cu;

        /** Variable name → list of definition sites. */
        final Map<String, List<DefSite>> definitions = new LinkedHashMap<>();

        /** Variable name → list of use sites. */
        final Map<String, List<UseSite>> uses = new LinkedHashMap<>();

        /** Tracks nesting inside lambdas and anonymous classes. */
        int lambdaDepth = 0;

        DataFlowVisitor(CompilationUnit cu) {
            this.cu = cu;
        }

        // --- Definitions ---

        @Override
        public boolean visit(VariableDeclarationStatement node) {
            @SuppressWarnings("unchecked")
            List<VariableDeclarationFragment> fragments = node.fragments();
            for (VariableDeclarationFragment frag : fragments) {
                String varName = frag.getName().getIdentifier();
                int line = cu.getLineNumber(frag.getStartPosition());
                DefSite.Kind kind = frag.getInitializer() != null
                        ? DefSite.Kind.DECLARATION_WITH_INIT
                        : DefSite.Kind.DECLARATION;
                boolean inLambda = lambdaDepth > 0;
                definitions.computeIfAbsent(varName, k -> new ArrayList<>())
                        .add(new DefSite(varName, line, kind, inLambda));
            }
            return true;
        }

        @Override
        public boolean visit(VariableDeclarationExpression node) {
            @SuppressWarnings("unchecked")
            List<VariableDeclarationFragment> fragments = node.fragments();
            for (VariableDeclarationFragment frag : fragments) {
                String varName = frag.getName().getIdentifier();
                int line = cu.getLineNumber(frag.getStartPosition());
                DefSite.Kind kind = frag.getInitializer() != null
                        ? DefSite.Kind.DECLARATION_WITH_INIT
                        : DefSite.Kind.DECLARATION;
                boolean inLambda = lambdaDepth > 0;
                definitions.computeIfAbsent(varName, k -> new ArrayList<>())
                        .add(new DefSite(varName, line, kind, inLambda));
            }
            return true;
        }

        @Override
        public boolean visit(Assignment node) {
            Expression lhs = node.getLeftHandSide();
            if (lhs instanceof SimpleName sn) {
                String varName = sn.getIdentifier();
                int line = cu.getLineNumber(node.getStartPosition());
                boolean inLambda = lambdaDepth > 0;
                definitions.computeIfAbsent(varName, k -> new ArrayList<>())
                        .add(new DefSite(varName, line, DefSite.Kind.ASSIGNMENT, inLambda));
            }
            return true;
        }

        @Override
        public boolean visit(PostfixExpression node) {
            if (node.getOperand() instanceof SimpleName sn) {
                recordMutation(sn, node);
            }
            return true;
        }

        @Override
        public boolean visit(PrefixExpression node) {
            PrefixExpression.Operator op = node.getOperator();
            if ((op == PrefixExpression.Operator.INCREMENT || op == PrefixExpression.Operator.DECREMENT)
                    && node.getOperand() instanceof SimpleName sn) {
                recordMutation(sn, node);
            }
            return true;
        }

        private void recordMutation(SimpleName sn, ASTNode node) {
            String varName = sn.getIdentifier();
            int line = cu.getLineNumber(node.getStartPosition());
            boolean inLambda = lambdaDepth > 0;
            definitions.computeIfAbsent(varName, k -> new ArrayList<>())
                    .add(new DefSite(varName, line, DefSite.Kind.MUTATION, inLambda));
        }

        // --- Uses ---

        @Override
        public boolean visit(SimpleName node) {
            // Only record reads (not writes — those are handled in definitions)
            if (isRead(node)) {
                String varName = node.getIdentifier();
                int line = cu.getLineNumber(node.getStartPosition());
                boolean inLambda = lambdaDepth > 0;
                uses.computeIfAbsent(varName, k -> new ArrayList<>())
                        .add(new UseSite(varName, line, inLambda));
            }
            return false;
        }

        /**
         * Determines if a SimpleName is in a read context (as opposed to being
         * the target of an assignment).
         */
        private boolean isRead(SimpleName node) {
            ASTNode parent = node.getParent();
            if (parent instanceof Assignment assign) {
                return assign.getLeftHandSide() != node;
            }
            if (parent instanceof VariableDeclarationFragment frag) {
                return frag.getName() != node;
            }
            if (parent instanceof SingleVariableDeclaration svd) {
                return svd.getName() != node;
            }
            // Method names in invocations are not variable reads
            if (parent instanceof MethodInvocation mi) {
                return mi.getName() != node;
            }
            if (parent instanceof MethodDeclaration md) {
                return md.getName() != node;
            }
            if (parent instanceof TypeDeclaration td) {
                return td.getName() != node;
            }
            return true;
        }

        // --- Lambda/anonymous class nesting tracking ---

        @Override
        public boolean visit(LambdaExpression node) {
            lambdaDepth++;
            return true;
        }

        @Override
        public void endVisit(LambdaExpression node) {
            lambdaDepth--;
        }

        @Override
        public boolean visit(AnonymousClassDeclaration node) {
            lambdaDepth++;
            return true;
        }

        @Override
        public void endVisit(AnonymousClassDeclaration node) {
            lambdaDepth--;
        }
    }

    // ═════════════════════════════════════════════════════════════════
    //  Result DTOs
    // ═════════════════════════════════════════════════════════════════

    /**
     * A definition site — where a variable is assigned a value.
     *
     * @param variableName        the variable being defined
     * @param lineNumber          source line
     * @param kind                how the definition occurs
     * @param inLambdaOrAnonymous whether this definition is inside a lambda or anonymous class
     */
    public record DefSite(
            String variableName,
            int lineNumber,
            Kind kind,
            boolean inLambdaOrAnonymous
    ) {
        public DefSite {
            Objects.requireNonNull(variableName, "variableName");
            Objects.requireNonNull(kind, "kind");
        }

        /** Classification of definition types. */
        public enum Kind {
            /** Variable declaration without initializer. */
            DECLARATION,
            /** Variable declaration with initializer ({@code int x = 5}). */
            DECLARATION_WITH_INIT,
            /** Assignment to existing variable ({@code x = 5}). */
            ASSIGNMENT,
            /** Compound mutation (increment, decrement, compound assignment). */
            MUTATION,
            /** Method parameter. */
            PARAMETER
        }
    }

    /**
     * A use site — where a variable's value is read.
     *
     * @param variableName        the variable being read
     * @param lineNumber          source line
     * @param inLambdaOrAnonymous whether this use is inside a lambda or anonymous class
     */
    public record UseSite(
            String variableName,
            int lineNumber,
            boolean inLambdaOrAnonymous
    ) {
        public UseSite {
            Objects.requireNonNull(variableName, "variableName");
        }
    }

    /**
     * Immutable result of intraprocedural data-flow analysis for a single method.
     *
     * @param methodKey         fully-qualified method identifier
     * @param definitions       all definition sites per variable name
     * @param uses              all use sites per variable name
     * @param effectivelyFinal  variables assigned at most once (safe for lambda capture)
     * @param mutatedInLambda   variables mutated inside a lambda or anonymous class
     * @param deadDefinitions   variables defined but never used
     */
    public record DataFlowResult(
            String methodKey,
            Map<String, List<DefSite>> definitions,
            Map<String, List<UseSite>> uses,
            Set<String> effectivelyFinal,
            Set<String> mutatedInLambda,
            Set<String> deadDefinitions
    ) {
        public DataFlowResult {
            Objects.requireNonNull(methodKey, "methodKey");
            definitions = definitions == null ? Map.of() : Map.copyOf(definitions);
            uses = uses == null ? Map.of() : Map.copyOf(uses);
            effectivelyFinal = effectivelyFinal == null ? Set.of() : Set.copyOf(effectivelyFinal);
            mutatedInLambda = mutatedInLambda == null ? Set.of() : Set.copyOf(mutatedInLambda);
            deadDefinitions = deadDefinitions == null ? Set.of() : Set.copyOf(deadDefinitions);
        }

        /** Creates an empty result for a method with no body. */
        static DataFlowResult empty(String methodKey) {
            return new DataFlowResult(methodKey, Map.of(), Map.of(), Set.of(), Set.of(), Set.of());
        }

        /**
         * Returns the def-use chain for a specific variable.
         *
         * @param variableName the variable to query
         * @return a pair of (definitions, uses) for that variable
         */
        public DefUseChain defUseChain(String variableName) {
            return new DefUseChain(
                    variableName,
                    definitions.getOrDefault(variableName, List.of()),
                    uses.getOrDefault(variableName, List.of()));
        }

        /**
         * Returns all def-use chains.
         *
         * @return list of chains, one per variable
         */
        public List<DefUseChain> allDefUseChains() {
            Set<String> allVars = new LinkedHashSet<>();
            allVars.addAll(definitions.keySet());
            allVars.addAll(uses.keySet());
            return allVars.stream()
                    .map(this::defUseChain)
                    .toList();
        }

        /** Whether any variables are mutated inside lambdas (blocking lambda conversion). */
        public boolean hasLambdaMutationHazard() {
            return !mutatedInLambda.isEmpty();
        }
    }

    /**
     * A complete def-use chain for a single variable.
     *
     * @param variableName the variable
     * @param definitions  all sites where the variable is defined
     * @param uses         all sites where the variable is read
     */
    public record DefUseChain(
            String variableName,
            List<DefSite> definitions,
            List<UseSite> uses
    ) {
        public DefUseChain {
            Objects.requireNonNull(variableName, "variableName");
            definitions = definitions == null ? List.of() : List.copyOf(definitions);
            uses = uses == null ? List.of() : List.copyOf(uses);
        }

        /** Whether the variable is never read. */
        public boolean isDead() {
            return uses.isEmpty() && !definitions.isEmpty();
        }

        /** Whether the variable has exactly one assignment. */
        public boolean isSingleAssignment() {
            long writeCount = definitions.stream()
                    .filter(d -> d.kind() != DefSite.Kind.DECLARATION)
                    .count();
            return writeCount <= 1;
        }
    }
}
