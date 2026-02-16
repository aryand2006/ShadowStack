package com.shadowstack.analysis;

import com.shadowstack.analysis.DataFlowAnalyzer.DataFlowResult;
import com.shadowstack.analysis.DataFlowAnalyzer.DefSite;
import org.eclipse.jdt.core.dom.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Analyzes field and variable mutations within Java source code.
 *
 * <p>For every variable and field in a method, the {@code MutationAnalyzer}
 * classifies its mutability into one of four categories:</p>
 * <ul>
 *   <li>{@link MutabilityClass#IMMUTABLE} — the variable is declared {@code final}
 *       and its value is never changed.</li>
 *   <li>{@link MutabilityClass#EFFECTIVELY_FINAL} — the variable is assigned
 *       exactly once and never mutated (safe for lambda capture).</li>
 *   <li>{@link MutabilityClass#MUTABLE} — the variable is reassigned or mutated
 *       at least once after its initial declaration.</li>
 *   <li>{@link MutabilityClass#VOLATILE} — the field is declared {@code volatile},
 *       indicating cross-thread visibility semantics.</li>
 * </ul>
 *
 * <h3>Cross-scope mutation detection</h3>
 * <p>A key responsibility of this analyzer is detecting mutations that cross
 * scope boundaries (into lambdas, anonymous classes, or inner classes).
 * These mutations are critical for lambda conversion safety — Java requires
 * captured variables to be effectively final.</p>
 *
 * <p>Thread-safe: each analysis creates its own visitor state.</p>
 *
 * @see DataFlowAnalyzer
 * @see PurityClassifier
 */
public final class MutationAnalyzer {

    private static final Logger LOG = LoggerFactory.getLogger(MutationAnalyzer.class);

    private MutationAnalyzer() {
        throw new AssertionError("Utility class — do not instantiate");
    }

    // ─── Public API ──────────────────────────────────────────────────

    /**
     * Analyzes all mutations in a method and classifies every variable.
     *
     * @param method the method to analyze
     * @param cu     the enclosing compilation unit
     * @return an immutable {@link MutationResult}
     */
    public static MutationResult analyze(MethodDeclaration method, CompilationUnit cu) {
        Objects.requireNonNull(method, "method must not be null");
        Objects.requireNonNull(cu, "compilation unit must not be null");

        String methodKey = ComplexityMetrics.methodKey(method);
        LOG.debug("Analyzing mutations for method: {}", methodKey);

        if (method.getBody() == null) {
            return MutationResult.empty(methodKey);
        }

        // First run data-flow analysis to get def-use chains
        DataFlowResult dataFlow = DataFlowAnalyzer.analyze(method, cu);

        // Collect field mutations
        FieldMutationVisitor fieldVisitor = new FieldMutationVisitor(cu);
        method.getBody().accept(fieldVisitor);

        // Classify local variables
        Map<String, MutabilityClass> localClassifications = new LinkedHashMap<>();
        Map<String, List<MutationSite>> localMutations = new LinkedHashMap<>();

        for (var entry : dataFlow.definitions().entrySet()) {
            String varName = entry.getKey();
            List<DefSite> defs = entry.getValue();

            MutabilityClass classification = classifyVariable(method, varName, defs, dataFlow);
            localClassifications.put(varName, classification);

            // Collect mutation sites
            List<MutationSite> mutations = defs.stream()
                    .filter(d -> d.kind() == DefSite.Kind.ASSIGNMENT || d.kind() == DefSite.Kind.MUTATION)
                    .map(d -> new MutationSite(
                            varName, d.lineNumber(), d.inLambdaOrAnonymous(),
                            d.kind() == DefSite.Kind.MUTATION
                                    ? MutationKind.COMPOUND
                                    : MutationKind.ASSIGNMENT))
                    .toList();
            if (!mutations.isEmpty()) {
                localMutations.put(varName, mutations);
            }
        }

        // Classify fields accessed in this method
        Map<String, MutabilityClass> fieldClassifications = new LinkedHashMap<>();
        for (var entry : fieldVisitor.fieldMutations.entrySet()) {
            String fieldName = entry.getKey();
            List<MutationSite> mutations = entry.getValue();
            boolean isVolatile = fieldVisitor.volatileFields.contains(fieldName);
            boolean isFinal = fieldVisitor.finalFields.contains(fieldName);

            if (isVolatile) {
                fieldClassifications.put(fieldName, MutabilityClass.VOLATILE);
            } else if (isFinal) {
                fieldClassifications.put(fieldName, MutabilityClass.IMMUTABLE);
            } else if (mutations.isEmpty()) {
                fieldClassifications.put(fieldName, MutabilityClass.EFFECTIVELY_FINAL);
            } else {
                fieldClassifications.put(fieldName, MutabilityClass.MUTABLE);
            }
        }

        // Detect cross-scope mutations
        List<CrossScopeMutation> crossScopeMutations = detectCrossScopeMutations(
                dataFlow, localMutations, fieldVisitor);

        MutationResult result = new MutationResult(
                methodKey,
                Collections.unmodifiableMap(localClassifications),
                Collections.unmodifiableMap(fieldClassifications),
                Collections.unmodifiableMap(new LinkedHashMap<>(localMutations)),
                Collections.unmodifiableMap(fieldVisitor.fieldMutations),
                List.copyOf(crossScopeMutations),
                dataFlow);

        LOG.debug("Mutation analysis for {}: {} locals classified, {} fields, {} cross-scope mutations",
                methodKey, localClassifications.size(), fieldClassifications.size(),
                crossScopeMutations.size());

        return result;
    }

    /**
     * Analyzes all methods in a compilation unit.
     *
     * @param cu the compilation unit
     * @return list of mutation results, one per method
     */
    public static List<MutationResult> analyzeAll(CompilationUnit cu) {
        Objects.requireNonNull(cu, "compilation unit must not be null");

        List<MutationResult> results = new ArrayList<>();
        cu.accept(new ASTVisitor() {
            @Override
            public boolean visit(MethodDeclaration node) {
                results.add(analyze(node, cu));
                return false;
            }
        });
        return List.copyOf(results);
    }

    // ─── Classification logic ────────────────────────────────────────

    /**
     * Classifies a local variable based on its definition sites and modifiers.
     */
    private static MutabilityClass classifyVariable(
            MethodDeclaration method,
            String varName,
            List<DefSite> defs,
            DataFlowResult dataFlow) {

        // Check if declared final
        if (isDeclaredFinal(method, varName)) {
            return MutabilityClass.IMMUTABLE;
        }

        // Check if effectively final
        if (dataFlow.effectivelyFinal().contains(varName)) {
            return MutabilityClass.EFFECTIVELY_FINAL;
        }

        return MutabilityClass.MUTABLE;
    }

    /**
     * Checks if a variable is declared with the {@code final} modifier.
     */
    private static boolean isDeclaredFinal(MethodDeclaration method, String varName) {
        FinalCheckVisitor visitor = new FinalCheckVisitor(varName);
        method.accept(visitor);
        return visitor.isFinal;
    }

    /**
     * Detects mutations that cross scope boundaries.
     */
    private static List<CrossScopeMutation> detectCrossScopeMutations(
            DataFlowResult dataFlow,
            Map<String, List<MutationSite>> localMutations,
            FieldMutationVisitor fieldVisitor) {

        List<CrossScopeMutation> crossScope = new ArrayList<>();

        // Local variables mutated in lambdas/anonymous classes
        for (String varName : dataFlow.mutatedInLambda()) {
            List<MutationSite> mutations = localMutations.getOrDefault(varName, List.of());
            for (MutationSite site : mutations) {
                if (site.inLambdaOrAnonymous()) {
                    crossScope.add(new CrossScopeMutation(
                            varName,
                            site.lineNumber(),
                            CrossScopeKind.LOCAL_IN_LAMBDA,
                            "Local variable '" + varName + "' mutated inside lambda/anonymous class"));
                }
            }
        }

        // Field mutations inside lambdas
        for (var entry : fieldVisitor.fieldMutations.entrySet()) {
            for (MutationSite site : entry.getValue()) {
                if (site.inLambdaOrAnonymous()) {
                    crossScope.add(new CrossScopeMutation(
                            entry.getKey(),
                            site.lineNumber(),
                            CrossScopeKind.FIELD_IN_LAMBDA,
                            "Field '" + entry.getKey() + "' mutated inside lambda/anonymous class"));
                }
            }
        }

        return crossScope;
    }

    // ═════════════════════════════════════════════════════════════════
    //  AST Visitors
    // ═════════════════════════════════════════════════════════════════

    /**
     * Detects field mutations within a method body.
     */
    private static final class FieldMutationVisitor extends ASTVisitor {
        private final CompilationUnit cu;

        final Map<String, List<MutationSite>> fieldMutations = new LinkedHashMap<>();
        final Set<String> volatileFields = new LinkedHashSet<>();
        final Set<String> finalFields = new LinkedHashSet<>();

        int lambdaDepth = 0;

        FieldMutationVisitor(CompilationUnit cu) {
            this.cu = cu;
        }

        @Override
        public boolean visit(Assignment node) {
            Expression lhs = node.getLeftHandSide();
            if (lhs instanceof FieldAccess fa) {
                recordFieldMutation(fa.getName().getIdentifier(), node);
            } else if (lhs instanceof QualifiedName qn) {
                // e.g. this.field = value
                recordFieldMutation(qn.getName().getIdentifier(), node);
            }
            return true;
        }

        @Override
        public boolean visit(PostfixExpression node) {
            if (node.getOperand() instanceof FieldAccess fa) {
                recordFieldMutation(fa.getName().getIdentifier(), node);
            }
            return true;
        }

        @Override
        public boolean visit(PrefixExpression node) {
            PrefixExpression.Operator op = node.getOperator();
            if (op == PrefixExpression.Operator.INCREMENT || op == PrefixExpression.Operator.DECREMENT) {
                if (node.getOperand() instanceof FieldAccess fa) {
                    recordFieldMutation(fa.getName().getIdentifier(), node);
                }
            }
            return true;
        }

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

        private void recordFieldMutation(String fieldName, ASTNode node) {
            int line = cu.getLineNumber(node.getStartPosition());
            boolean inLambda = lambdaDepth > 0;
            fieldMutations.computeIfAbsent(fieldName, k -> new ArrayList<>())
                    .add(new MutationSite(fieldName, line, inLambda, MutationKind.ASSIGNMENT));
        }
    }

    /**
     * Checks if a specific variable is declared with the final modifier.
     */
    private static final class FinalCheckVisitor extends ASTVisitor {
        private final String targetVariable;
        boolean isFinal = false;

        FinalCheckVisitor(String targetVariable) {
            this.targetVariable = targetVariable;
        }

        @Override
        public boolean visit(VariableDeclarationStatement node) {
            if (Modifier.isFinal(node.getModifiers())) {
                @SuppressWarnings("unchecked")
                List<VariableDeclarationFragment> fragments = node.fragments();
                for (VariableDeclarationFragment frag : fragments) {
                    if (frag.getName().getIdentifier().equals(targetVariable)) {
                        isFinal = true;
                    }
                }
            }
            return true;
        }

        @Override
        public boolean visit(SingleVariableDeclaration node) {
            if (Modifier.isFinal(node.getModifiers()) &&
                    node.getName().getIdentifier().equals(targetVariable)) {
                isFinal = true;
            }
            return true;
        }
    }

    // ═════════════════════════════════════════════════════════════════
    //  Result DTOs and Enums
    // ═════════════════════════════════════════════════════════════════

    /**
     * Mutability classification for a variable or field.
     */
    public enum MutabilityClass {
        /** Declared {@code final} — value guaranteed never to change. */
        IMMUTABLE,
        /** Assigned exactly once, never mutated — safe for lambda capture. */
        EFFECTIVELY_FINAL,
        /** Reassigned or mutated after initial declaration. */
        MUTABLE,
        /** Declared {@code volatile} — cross-thread visibility semantics. */
        VOLATILE
    }

    /**
     * How a mutation occurs.
     */
    public enum MutationKind {
        /** Direct assignment ({@code x = value}). */
        ASSIGNMENT,
        /** Compound mutation ({@code x++}, {@code x += 1}). */
        COMPOUND,
        /** Array element mutation ({@code arr[i] = value}). */
        ARRAY_ELEMENT,
        /** Method call that mutates the receiver ({@code list.add(x)}). */
        RECEIVER_MUTATION
    }

    /**
     * Classification of cross-scope mutation types.
     */
    public enum CrossScopeKind {
        /** Local variable mutated inside a lambda expression. */
        LOCAL_IN_LAMBDA,
        /** Local variable mutated inside an anonymous class. */
        LOCAL_IN_ANONYMOUS,
        /** Field mutated inside a lambda expression. */
        FIELD_IN_LAMBDA,
        /** Field mutated inside an anonymous class. */
        FIELD_IN_ANONYMOUS
    }

    /**
     * A specific location where a mutation occurs.
     *
     * @param variableName        the mutated variable or field
     * @param lineNumber          source line
     * @param inLambdaOrAnonymous whether inside a lambda or anonymous class
     * @param kind                how the mutation occurs
     */
    public record MutationSite(
            String variableName,
            int lineNumber,
            boolean inLambdaOrAnonymous,
            MutationKind kind
    ) {
        public MutationSite {
            Objects.requireNonNull(variableName, "variableName");
            Objects.requireNonNull(kind, "kind");
        }
    }

    /**
     * A mutation that crosses a scope boundary.
     *
     * @param variableName name of the variable or field
     * @param lineNumber   where the mutation occurs
     * @param kind         the type of cross-scope mutation
     * @param description  human-readable explanation
     */
    public record CrossScopeMutation(
            String variableName,
            int lineNumber,
            CrossScopeKind kind,
            String description
    ) {
        public CrossScopeMutation {
            Objects.requireNonNull(variableName, "variableName");
            Objects.requireNonNull(kind, "kind");
        }
    }

    /**
     * Complete mutation analysis result for a single method.
     *
     * @param methodKey             fully-qualified method identifier
     * @param localClassifications  mutability class per local variable
     * @param fieldClassifications  mutability class per field accessed in the method
     * @param localMutations        mutation sites per local variable
     * @param fieldMutations        mutation sites per field
     * @param crossScopeMutations   mutations that cross scope boundaries
     * @param dataFlow              underlying data-flow result
     */
    public record MutationResult(
            String methodKey,
            Map<String, MutabilityClass> localClassifications,
            Map<String, MutabilityClass> fieldClassifications,
            Map<String, List<MutationSite>> localMutations,
            Map<String, List<MutationSite>> fieldMutations,
            List<CrossScopeMutation> crossScopeMutations,
            DataFlowResult dataFlow
    ) {
        public MutationResult {
            Objects.requireNonNull(methodKey, "methodKey");
            localClassifications = localClassifications == null ? Map.of() : Map.copyOf(localClassifications);
            fieldClassifications = fieldClassifications == null ? Map.of() : Map.copyOf(fieldClassifications);
            localMutations = localMutations == null ? Map.of() : localMutations;
            fieldMutations = fieldMutations == null ? Map.of() : fieldMutations;
            crossScopeMutations = crossScopeMutations == null ? List.of() : List.copyOf(crossScopeMutations);
        }

        /** Creates an empty result for a method with no body. */
        static MutationResult empty(String methodKey) {
            return new MutationResult(
                    methodKey, Map.of(), Map.of(), Map.of(), Map.of(), List.of(),
                    DataFlowResult.empty(methodKey));
        }

        /** Whether it is safe to convert anonymous classes in this method to lambdas. */
        public boolean isSafeForLambdaConversion() {
            return crossScopeMutations.isEmpty();
        }

        /** Returns all mutable local variables. */
        public Set<String> mutableLocals() {
            return localClassifications.entrySet().stream()
                    .filter(e -> e.getValue() == MutabilityClass.MUTABLE)
                    .map(Map.Entry::getKey)
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
        }

        /** Returns all effectively final local variables. */
        public Set<String> effectivelyFinalLocals() {
            return localClassifications.entrySet().stream()
                    .filter(e -> e.getValue() == MutabilityClass.EFFECTIVELY_FINAL)
                    .map(Map.Entry::getKey)
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
        }
    }
}
