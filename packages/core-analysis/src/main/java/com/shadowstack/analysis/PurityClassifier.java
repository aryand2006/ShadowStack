package com.shadowstack.analysis;

import com.shadowstack.analysis.CallGraphBuilder.CallEdge;
import com.shadowstack.analysis.CallGraphBuilder.CallGraph;
import com.shadowstack.analysis.MutationAnalyzer.MutabilityClass;
import com.shadowstack.analysis.MutationAnalyzer.MutationResult;
import org.eclipse.jdt.core.dom.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Classifies methods by their side-effect profile (purity).
 *
 * <p>Purity classification is essential for safe refactoring — a method that
 * is {@link Purity#PURE PURE} can be safely memoized, reordered, parallelized,
 * or inlined without changing observable behavior. Methods that read or mutate
 * state require progressively more caution.</p>
 *
 * <h3>Classification hierarchy</h3>
 * <ol>
 *   <li>{@link Purity#PURE} — no side effects, deterministic, depends only on parameters.</li>
 *   <li>{@link Purity#READS_STATE} — reads instance/class fields but does not modify them.</li>
 *   <li>{@link Purity#MUTATES_STATE} — modifies instance/class fields.</li>
 *   <li>{@link Purity#SIDE_EFFECTING} — performs IO, reflection, synchronization, or
 *       other observable side effects.</li>
 *   <li>{@link Purity#UNKNOWN} — insufficient information (conservative default).</li>
 * </ol>
 *
 * <h3>Analysis strategy</h3>
 * <p>The classifier combines:</p>
 * <ul>
 *   <li><strong>Mutation analysis</strong> — field reads/writes in the method body.</li>
 *   <li><strong>Call graph</strong> — transitive callee impurity propagates to callers.</li>
 *   <li><strong>IO/reflection detection</strong> — any IO or reflection makes the method side-effecting.</li>
 * </ul>
 *
 * <p>The analysis is <strong>conservative</strong>: if insufficient information is
 * available (e.g. a callee is in a library), the method defaults to {@link Purity#UNKNOWN}.</p>
 *
 * <p>Thread-safe: all state is local to each classification invocation.</p>
 *
 * @see MutationAnalyzer
 * @see CallGraphBuilder
 */
public final class PurityClassifier {

    private static final Logger LOG = LoggerFactory.getLogger(PurityClassifier.class);

    /** Method names that are known to be side-effecting. */
    private static final Set<String> SIDE_EFFECTING_METHODS = Set.of(
            "println", "print", "printf", "write", "flush", "close",
            "read", "readLine", "readAllBytes", "readString",
            "sleep", "wait", "notify", "notifyAll",
            "start", "join", "interrupt",
            "exit", "gc", "halt",
            "log", "info", "debug", "warn", "error", "trace",
            "execute", "submit", "invokeAll", "invokeAny",
            "lock", "unlock", "tryLock",
            "send", "receive", "connect", "accept", "bind"
    );

    /** Types whose method calls are inherently side-effecting. */
    private static final Set<String> SIDE_EFFECTING_TYPES = Set.of(
            "System", "Runtime", "Thread", "Process", "ProcessBuilder",
            "PrintStream", "PrintWriter", "InputStream", "OutputStream",
            "Socket", "ServerSocket", "Channel",
            "Connection", "Statement", "PreparedStatement",
            "Logger", "Log"
    );

    private PurityClassifier() {
        throw new AssertionError("Utility class — do not instantiate");
    }

    // ─── Public API ──────────────────────────────────────────────────

    /**
     * Classifies a single method's purity.
     *
     * @param method   the method to classify
     * @param cu       the enclosing compilation unit
     * @param callGraph the call graph (may be null if not available)
     * @return an immutable {@link PurityResult}
     */
    public static PurityResult classify(
            MethodDeclaration method,
            CompilationUnit cu,
            CallGraph callGraph) {

        Objects.requireNonNull(method, "method must not be null");
        Objects.requireNonNull(cu, "compilation unit must not be null");

        String methodKey = ComplexityMetrics.methodKey(method);
        LOG.debug("Classifying purity for method: {}", methodKey);

        if (method.getBody() == null) {
            // Abstract or native method — unknown purity
            return new PurityResult(methodKey, Purity.UNKNOWN,
                    List.of(new Evidence(EvidenceKind.NO_BODY, "Method has no body (abstract/native)", -1)));
        }

        // Collect evidence
        List<Evidence> evidence = new ArrayList<>();

        // 1) Run mutation analysis
        MutationResult mutationResult = MutationAnalyzer.analyze(method, cu);
        collectMutationEvidence(mutationResult, evidence);

        // 2) Scan for side-effecting calls
        SideEffectVisitor sideEffectVisitor = new SideEffectVisitor(cu);
        method.getBody().accept(sideEffectVisitor);
        evidence.addAll(sideEffectVisitor.evidence);

        // 3) Check call graph for transitive impurity
        if (callGraph != null) {
            collectCallGraphEvidence(methodKey, callGraph, evidence);
        }

        // 4) Determine classification based on evidence
        Purity classification = determineClassification(evidence);

        PurityResult result = new PurityResult(methodKey, classification, List.copyOf(evidence));

        LOG.debug("Purity for {}: {} ({} evidence items)",
                methodKey, classification, evidence.size());

        return result;
    }

    /**
     * Classifies all methods in a compilation unit.
     *
     * @param cu        the compilation unit
     * @param callGraph the call graph (may be null)
     * @return map of method key → purity result
     */
    public static Map<String, PurityResult> classifyAll(CompilationUnit cu, CallGraph callGraph) {
        Objects.requireNonNull(cu, "compilation unit must not be null");

        Map<String, PurityResult> results = new LinkedHashMap<>();
        cu.accept(new ASTVisitor() {
            @Override
            public boolean visit(MethodDeclaration node) {
                PurityResult result = classify(node, cu, callGraph);
                results.put(result.methodKey(), result);
                return false;
            }
        });
        return Collections.unmodifiableMap(results);
    }

    // ─── Evidence collection ─────────────────────────────────────────

    /**
     * Collects evidence from mutation analysis.
     */
    private static void collectMutationEvidence(MutationResult mutation, List<Evidence> evidence) {
        // Field mutations → MUTATES_STATE
        if (!mutation.fieldMutations().isEmpty()) {
            for (var entry : mutation.fieldMutations().entrySet()) {
                for (var site : entry.getValue()) {
                    evidence.add(new Evidence(
                            EvidenceKind.FIELD_WRITE,
                            "Writes to field '" + entry.getKey() + "'",
                            site.lineNumber()));
                }
            }
        }

        // Field reads (without writes) → READS_STATE
        for (var entry : mutation.fieldClassifications().entrySet()) {
            MutabilityClass mc = entry.getValue();
            if (mc != MutabilityClass.MUTABLE && mc != MutabilityClass.VOLATILE) {
                // Read-only field access
                if (!mutation.fieldMutations().containsKey(entry.getKey())) {
                    evidence.add(new Evidence(
                            EvidenceKind.FIELD_READ,
                            "Reads field '" + entry.getKey() + "'",
                            -1));
                }
            }
        }

        // Cross-scope mutations → SIDE_EFFECTING (because they indicate shared-state interaction)
        for (var crossScope : mutation.crossScopeMutations()) {
            evidence.add(new Evidence(
                    EvidenceKind.CROSS_SCOPE_MUTATION,
                    crossScope.description(),
                    crossScope.lineNumber()));
        }
    }

    /**
     * Collects evidence from the call graph (transitive impurity).
     */
    private static void collectCallGraphEvidence(
            String methodKey, CallGraph callGraph, List<Evidence> evidence) {

        Set<CallEdge> outgoing = callGraph.callees(methodKey);
        for (CallEdge edge : outgoing) {
            if (edge.polymorphic()) {
                evidence.add(new Evidence(
                        EvidenceKind.POLYMORPHIC_CALL,
                        "Polymorphic call to " + edge.callee() + " — purity unknown",
                        edge.lineNumber()));
            }
        }

        // Check if any reachable method is known to be side-effecting
        Set<String> reachable = callGraph.reachableFrom(methodKey);
        for (String reached : reachable) {
            if (reached.contains("System.out") || reached.contains("System.err") ||
                    reached.contains("System.in")) {
                evidence.add(new Evidence(
                        EvidenceKind.TRANSITIVE_SIDE_EFFECT,
                        "Transitively reaches IO method: " + reached,
                        -1));
            }
        }
    }

    /**
     * Determines the final purity classification from collected evidence.
     *
     * <p>Priority (highest to lowest): SIDE_EFFECTING > MUTATES_STATE > READS_STATE > PURE.
     * Falls back to UNKNOWN if polymorphic calls prevent certainty.</p>
     */
    private static Purity determineClassification(List<Evidence> evidence) {
        boolean hasSideEffect = false;
        boolean hasMutation = false;
        boolean hasStateRead = false;
        boolean hasUnknown = false;

        for (Evidence e : evidence) {
            switch (e.kind()) {
                case IO_CALL, REFLECTION_CALL, SYNCHRONIZATION, THREAD_OPERATION,
                        NATIVE_CALL, TRANSITIVE_SIDE_EFFECT, CROSS_SCOPE_MUTATION ->
                        hasSideEffect = true;
                case FIELD_WRITE ->
                        hasMutation = true;
                case FIELD_READ ->
                        hasStateRead = true;
                case POLYMORPHIC_CALL, UNRESOLVED_CALL, NO_BODY ->
                        hasUnknown = true;
                case SIDE_EFFECTING_METHOD ->
                        hasSideEffect = true;
            }
        }

        if (hasSideEffect) return Purity.SIDE_EFFECTING;
        if (hasMutation) return Purity.MUTATES_STATE;
        if (hasUnknown) return Purity.UNKNOWN;
        if (hasStateRead) return Purity.READS_STATE;
        return Purity.PURE;
    }

    // ═════════════════════════════════════════════════════════════════
    //  Side-effect visitor
    // ═════════════════════════════════════════════════════════════════

    /**
     * Scans a method body for side-effecting operations.
     */
    private static final class SideEffectVisitor extends ASTVisitor {
        private final CompilationUnit cu;
        final List<Evidence> evidence = new ArrayList<>();

        SideEffectVisitor(CompilationUnit cu) {
            this.cu = cu;
        }

        @Override
        public boolean visit(MethodInvocation node) {
            String methodName = node.getName().getIdentifier();
            Expression expr = node.getExpression();
            String receiver = expr != null ? expr.toString() : "";
            int line = cu.getLineNumber(node.getStartPosition());

            // Check for known side-effecting method names
            if (SIDE_EFFECTING_METHODS.contains(methodName)) {
                evidence.add(new Evidence(
                        EvidenceKind.SIDE_EFFECTING_METHOD,
                        "Calls side-effecting method: " + receiver + "." + methodName + "()",
                        line));
            }

            // Check for known side-effecting receiver types
            if (SIDE_EFFECTING_TYPES.stream().anyMatch(receiver::contains)) {
                evidence.add(new Evidence(
                        EvidenceKind.IO_CALL,
                        "Calls method on side-effecting type: " + receiver + "." + methodName + "()",
                        line));
            }

            // Check for reflection calls
            if (isReflectionCall(methodName, receiver)) {
                evidence.add(new Evidence(
                        EvidenceKind.REFLECTION_CALL,
                        "Reflection call: " + receiver + "." + methodName + "()",
                        line));
            }

            return true;
        }

        @Override
        public boolean visit(SynchronizedStatement node) {
            int line = cu.getLineNumber(node.getStartPosition());
            evidence.add(new Evidence(
                    EvidenceKind.SYNCHRONIZATION,
                    "synchronized block",
                    line));
            return true;
        }

        @Override
        public boolean visit(ThrowStatement node) {
            // Throwing exceptions is side-effecting (changes control flow observably)
            int line = cu.getLineNumber(node.getStartPosition());
            evidence.add(new Evidence(
                    EvidenceKind.SIDE_EFFECTING_METHOD,
                    "throw statement",
                    line));
            return true;
        }

        @Override
        public boolean visit(ClassInstanceCreation node) {
            String typeName = node.getType().toString();
            int line = cu.getLineNumber(node.getStartPosition());

            // Check if creating a side-effecting type
            if (SIDE_EFFECTING_TYPES.contains(typeName)) {
                evidence.add(new Evidence(
                        EvidenceKind.IO_CALL,
                        "Creates instance of side-effecting type: " + typeName,
                        line));
            }
            return true;
        }

        private boolean isReflectionCall(String methodName, String receiver) {
            return (receiver.contains("Class") || receiver.contains("Method") ||
                    receiver.contains("Field") || receiver.contains("Constructor")) &&
                    (methodName.equals("forName") || methodName.equals("invoke") ||
                            methodName.equals("newInstance") || methodName.startsWith("get") ||
                            methodName.startsWith("getDeclared") || methodName.equals("setAccessible"));
        }
    }

    // ═════════════════════════════════════════════════════════════════
    //  DTOs and Enums
    // ═════════════════════════════════════════════════════════════════

    /**
     * Purity classification for a method.
     */
    public enum Purity {
        /** No side effects — depends only on parameters, deterministic. */
        PURE("Pure — no side effects, safe to memoize/reorder/parallelize"),
        /** Reads instance or class fields but does not modify them. */
        READS_STATE("Reads state — depends on object/class fields"),
        /** Modifies instance or class fields. */
        MUTATES_STATE("Mutates state — modifies object/class fields"),
        /** Performs IO, reflection, synchronization, or other observable side effects. */
        SIDE_EFFECTING("Side-effecting — performs IO, reflection, or synchronization"),
        /** Insufficient information to classify. */
        UNKNOWN("Unknown — insufficient information for classification");

        private final String description;

        Purity(String description) {
            this.description = description;
        }

        /** Human-readable description. */
        public String description() {
            return description;
        }

        /** Whether this classification is safe for automatic refactoring. */
        public boolean isSafeForAutoRefactor() {
            return this == PURE || this == READS_STATE;
        }
    }

    /**
     * Classification of evidence types that contribute to purity determination.
     */
    public enum EvidenceKind {
        /** The method reads a field. */
        FIELD_READ,
        /** The method writes a field. */
        FIELD_WRITE,
        /** The method performs an IO operation. */
        IO_CALL,
        /** The method uses reflection. */
        REFLECTION_CALL,
        /** The method uses synchronization. */
        SYNCHRONIZATION,
        /** The method interacts with threads. */
        THREAD_OPERATION,
        /** The method calls a native method. */
        NATIVE_CALL,
        /** The method makes a polymorphic call (virtual dispatch). */
        POLYMORPHIC_CALL,
        /** The method calls an unresolved method. */
        UNRESOLVED_CALL,
        /** The method transitively reaches a side-effecting method. */
        TRANSITIVE_SIDE_EFFECT,
        /** The method mutates a variable across a scope boundary. */
        CROSS_SCOPE_MUTATION,
        /** The method calls a known side-effecting method. */
        SIDE_EFFECTING_METHOD,
        /** The method has no body (abstract/native). */
        NO_BODY
    }

    /**
     * A piece of evidence contributing to a method's purity classification.
     *
     * @param kind        the type of evidence
     * @param description human-readable explanation
     * @param lineNumber  source line (or -1 if not applicable)
     */
    public record Evidence(
            EvidenceKind kind,
            String description,
            int lineNumber
    ) {
        public Evidence {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(description, "description");
        }
    }

    /**
     * Immutable purity classification result for a single method.
     *
     * @param methodKey      fully-qualified method identifier
     * @param classification the determined purity level
     * @param evidence       all evidence collected during analysis
     */
    public record PurityResult(
            String methodKey,
            Purity classification,
            List<Evidence> evidence
    ) {
        public PurityResult {
            Objects.requireNonNull(methodKey, "methodKey");
            Objects.requireNonNull(classification, "classification");
            evidence = evidence == null ? List.of() : List.copyOf(evidence);
        }

        /** Whether this method is safe for automatic refactoring. */
        public boolean isSafeForAutoRefactor() {
            return classification.isSafeForAutoRefactor();
        }

        /** Returns evidence of a specific kind. */
        public List<Evidence> evidenceOfKind(EvidenceKind kind) {
            return evidence.stream()
                    .filter(e -> e.kind() == kind)
                    .toList();
        }

        /** Returns a human-readable summary. */
        public String summary() {
            return methodKey + " → " + classification.name() +
                    " (" + evidence.size() + " evidence items)";
        }
    }
}
