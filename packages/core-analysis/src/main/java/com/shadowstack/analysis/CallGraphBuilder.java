package com.shadowstack.analysis;

import org.eclipse.jdt.core.dom.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Phase 1 — Call Graph Construction.
 *
 * <p>Builds a whole-program call graph from Eclipse JDT AST compilation units.
 * The graph represents caller → callee relationships between methods, with
 * special handling for virtual dispatch (polymorphic calls), constructor chains,
 * and lambda/method-reference invocations.</p>
 *
 * <h3>Design decisions</h3>
 * <ul>
 *   <li><strong>Conservative on virtual dispatch:</strong> when the receiver type
 *       cannot be statically resolved, the edge is marked {@code polymorphic = true}
 *       so that downstream analyses can treat it conservatively.</li>
 *   <li><strong>Field-insensitive:</strong> the analysis does not track which object
 *       a field points to; all calls through a given type are merged.</li>
 *   <li><strong>Intraprocedural method bindings:</strong> binding resolution uses
 *       Eclipse JDT's built-in resolver when available; otherwise falls back to
 *       syntactic name matching.</li>
 * </ul>
 *
 * <p>Usage:</p>
 * <pre>{@code
 *   CallGraphBuilder builder = new CallGraphBuilder();
 *   builder.addCompilationUnit(cu);
 *   CallGraph graph = builder.build();
 * }</pre>
 *
 * @see CallGraph
 * @see CallEdge
 */
public final class CallGraphBuilder {

    private static final Logger LOG = LoggerFactory.getLogger(CallGraphBuilder.class);

    /**
     * Maps each method key to the set of edges originating from it.
     * Key format: {@code fully.qualified.ClassName#methodName(ParamType1,ParamType2)}
     */
    private final Map<String, Set<CallEdge>> adjacency = new LinkedHashMap<>();

    /** All known method keys (including callees that may not be in our source tree). */
    private final Set<String> allMethods = new LinkedHashSet<>();

    /** Tracks whether build() has already been called. */
    private boolean built = false;

    // ─── Public API ──────────────────────────────────────────────────

    /**
     * Processes a compilation unit, adding all discovered caller → callee edges.
     *
     * @param cu a parsed Eclipse JDT {@link CompilationUnit}
     * @throws NullPointerException  if {@code cu} is null
     * @throws IllegalStateException if {@link #build()} has already been called
     */
    public void addCompilationUnit(CompilationUnit cu) {
        Objects.requireNonNull(cu, "compilation unit must not be null");
        if (built) {
            throw new IllegalStateException("Cannot add compilation units after build() has been called");
        }

        LOG.debug("Processing compilation unit for call graph");
        cu.accept(new CallGraphVisitor(cu));
    }

    /**
     * Builds and returns the immutable call graph.
     *
     * <p>After this method returns, no further compilation units may be added.</p>
     *
     * @return an immutable {@link CallGraph}
     */
    public CallGraph build() {
        built = true;

        // Create unmodifiable deep copy
        Map<String, Set<CallEdge>> immutableAdj = new LinkedHashMap<>();
        for (var entry : adjacency.entrySet()) {
            immutableAdj.put(entry.getKey(), Set.copyOf(entry.getValue()));
        }

        CallGraph graph = new CallGraph(
                Collections.unmodifiableMap(immutableAdj),
                Set.copyOf(allMethods));

        LOG.info("Call graph built: {} methods, {} edges",
                graph.methodCount(), graph.edgeCount());

        return graph;
    }

    // ─── Internal ────────────────────────────────────────────────────

    /**
     * Adds an edge from caller to callee.
     */
    private void addEdge(String caller, String callee, boolean polymorphic,
                         CallEdge.InvocationType invocationType, int lineNumber) {
        allMethods.add(caller);
        allMethods.add(callee);

        CallEdge edge = new CallEdge(caller, callee, polymorphic, invocationType, lineNumber);
        adjacency.computeIfAbsent(caller, k -> new LinkedHashSet<>()).add(edge);
    }

    /**
     * Resolves a method key from a MethodDeclaration.
     */
    private static String resolveMethodKey(CompilationUnit cu, MethodDeclaration method) {
        String className = resolveEnclosingClassName(cu, method);
        String methodName = method.isConstructor() ? "<init>" : method.getName().getIdentifier();
        String params = resolveParameterTypes(method);
        return className + "#" + methodName + "(" + params + ")";
    }

    /**
     * Resolves the fully-qualified enclosing class name for a method.
     */
    private static String resolveEnclosingClassName(CompilationUnit cu, ASTNode node) {
        ASTNode current = node.getParent();
        Deque<String> nameStack = new ArrayDeque<>();

        while (current != null) {
            if (current instanceof TypeDeclaration td) {
                nameStack.push(td.getName().getIdentifier());
            } else if (current instanceof EnumDeclaration ed) {
                nameStack.push(ed.getName().getIdentifier());
            } else if (current instanceof AnonymousClassDeclaration) {
                nameStack.push("<anonymous>");
            }
            current = current.getParent();
        }

        PackageDeclaration pkg = cu.getPackage();
        String packageName = pkg != null ? pkg.getName().getFullyQualifiedName() : "";

        String className = String.join(".", nameStack);
        return packageName.isEmpty() ? className : packageName + "." + className;
    }

    /**
     * Resolves parameter types as a comma-separated string.
     */
    private static String resolveParameterTypes(MethodDeclaration method) {
        @SuppressWarnings("unchecked")
        List<SingleVariableDeclaration> params = method.parameters();
        StringJoiner joiner = new StringJoiner(",");
        for (SingleVariableDeclaration param : params) {
            joiner.add(param.getType().toString());
        }
        return joiner.toString();
    }

    /**
     * Attempts to resolve a callee key from a MethodInvocation.
     * Uses binding info if available; otherwise falls back to syntactic resolution.
     */
    private static String resolveCalleeKey(CompilationUnit cu, MethodInvocation node) {
        IMethodBinding binding = node.resolveMethodBinding();
        if (binding != null) {
            ITypeBinding declaringClass = binding.getDeclaringClass();
            String className = declaringClass != null
                    ? declaringClass.getQualifiedName()
                    : "<unresolved>";
            String methodName = binding.getName();
            StringJoiner paramJoiner = new StringJoiner(",");
            for (ITypeBinding paramType : binding.getParameterTypes()) {
                paramJoiner.add(paramType.getName());
            }
            return className + "#" + methodName + "(" + paramJoiner + ")";
        }

        // Fallback: syntactic resolution
        String methodName = node.getName().getIdentifier();
        Expression expr = node.getExpression();
        String receiver = expr != null ? expr.toString() : "<this>";

        @SuppressWarnings("unchecked")
        List<Expression> args = node.arguments();
        return receiver + "#" + methodName + "(arity=" + args.size() + ")";
    }

    /**
     * Determines if a method invocation might be polymorphic (virtual dispatch).
     */
    private static boolean isPolymorphic(MethodInvocation node) {
        IMethodBinding binding = node.resolveMethodBinding();
        if (binding != null) {
            int modifiers = binding.getModifiers();
            // Static, final, and private methods are not polymorphic
            if (Modifier.isStatic(modifiers) || Modifier.isFinal(modifiers) ||
                    Modifier.isPrivate(modifiers)) {
                return false;
            }
            ITypeBinding declaringClass = binding.getDeclaringClass();
            if (declaringClass != null && Modifier.isFinal(declaringClass.getModifiers())) {
                return false;
            }
            // Instance method on non-final class — potentially polymorphic
            return true;
        }
        // Without binding info, conservatively assume polymorphic for instance calls
        return node.getExpression() != null;
    }

    // ═════════════════════════════════════════════════════════════════
    //  AST Visitor
    // ═════════════════════════════════════════════════════════════════

    /**
     * Visits methods and their invocations to build the call graph.
     */
    private final class CallGraphVisitor extends ASTVisitor {
        private final CompilationUnit cu;
        private final Deque<String> methodStack = new ArrayDeque<>();

        CallGraphVisitor(CompilationUnit cu) {
            this.cu = cu;
        }

        @Override
        public boolean visit(MethodDeclaration node) {
            String callerKey = resolveMethodKey(cu, node);
            methodStack.push(callerKey);
            allMethods.add(callerKey);
            adjacency.computeIfAbsent(callerKey, k -> new LinkedHashSet<>());
            return true;
        }

        @Override
        public void endVisit(MethodDeclaration node) {
            if (!methodStack.isEmpty()) {
                methodStack.pop();
            }
        }

        @Override
        public boolean visit(MethodInvocation node) {
            if (methodStack.isEmpty()) return true;

            String caller = methodStack.peek();
            String callee = resolveCalleeKey(cu, node);
            boolean polymorphic = isPolymorphic(node);
            int line = cu.getLineNumber(node.getStartPosition());

            addEdge(caller, callee, polymorphic, CallEdge.InvocationType.VIRTUAL, line);
            return true;
        }

        @Override
        public boolean visit(SuperMethodInvocation node) {
            if (methodStack.isEmpty()) return true;

            String caller = methodStack.peek();
            String methodName = node.getName().getIdentifier();
            int line = cu.getLineNumber(node.getStartPosition());

            // Super calls are always non-polymorphic (static dispatch to parent)
            String callee = "<super>#" + methodName + "()";

            IMethodBinding binding = node.resolveMethodBinding();
            if (binding != null) {
                ITypeBinding declaringClass = binding.getDeclaringClass();
                String className = declaringClass != null
                        ? declaringClass.getQualifiedName() : "<super>";
                StringJoiner joiner = new StringJoiner(",");
                for (ITypeBinding pt : binding.getParameterTypes()) {
                    joiner.add(pt.getName());
                }
                callee = className + "#" + methodName + "(" + joiner + ")";
            }

            addEdge(caller, callee, false, CallEdge.InvocationType.SUPER, line);
            return true;
        }

        @Override
        public boolean visit(ClassInstanceCreation node) {
            if (methodStack.isEmpty()) return true;

            String caller = methodStack.peek();
            String typeName = node.getType().toString();
            int line = cu.getLineNumber(node.getStartPosition());

            @SuppressWarnings("unchecked")
            List<Expression> args = node.arguments();
            String callee = typeName + "#<init>(arity=" + args.size() + ")";

            IMethodBinding binding = node.resolveConstructorBinding();
            if (binding != null) {
                ITypeBinding declaringClass = binding.getDeclaringClass();
                String className = declaringClass != null
                        ? declaringClass.getQualifiedName() : typeName;
                StringJoiner joiner = new StringJoiner(",");
                for (ITypeBinding pt : binding.getParameterTypes()) {
                    joiner.add(pt.getName());
                }
                callee = className + "#<init>(" + joiner + ")";
            }

            addEdge(caller, callee, false, CallEdge.InvocationType.CONSTRUCTOR, line);
            return true;
        }

        @Override
        public boolean visit(ConstructorInvocation node) {
            // this(...) calls
            if (methodStack.isEmpty()) return true;

            String caller = methodStack.peek();
            int line = cu.getLineNumber(node.getStartPosition());

            @SuppressWarnings("unchecked")
            List<Expression> args = node.arguments();
            String callee = "<this>#<init>(arity=" + args.size() + ")";

            addEdge(caller, callee, false, CallEdge.InvocationType.CONSTRUCTOR, line);
            return true;
        }

        @Override
        public boolean visit(SuperConstructorInvocation node) {
            // super(...) calls
            if (methodStack.isEmpty()) return true;

            String caller = methodStack.peek();
            int line = cu.getLineNumber(node.getStartPosition());

            @SuppressWarnings("unchecked")
            List<Expression> args = node.arguments();
            String callee = "<super>#<init>(arity=" + args.size() + ")";

            addEdge(caller, callee, false, CallEdge.InvocationType.SUPER, line);
            return true;
        }

        @Override
        public boolean visit(LambdaExpression node) {
            if (methodStack.isEmpty()) return true;

            // Treat lambda body as part of the enclosing method
            // (we do NOT push a new method key — lambda calls are attributed to the enclosing method)
            return true;
        }

        @Override
        public boolean visit(ExpressionMethodReference node) {
            if (methodStack.isEmpty()) return true;

            String caller = methodStack.peek();
            String methodName = node.getName().getIdentifier();
            String receiver = node.getExpression().toString();
            int line = cu.getLineNumber(node.getStartPosition());

            addEdge(caller, receiver + "#" + methodName + "()",
                    true, CallEdge.InvocationType.METHOD_REFERENCE, line);
            return true;
        }

        @Override
        public boolean visit(TypeMethodReference node) {
            if (methodStack.isEmpty()) return true;

            String caller = methodStack.peek();
            String methodName = node.getName().getIdentifier();
            String typeName = node.getType().toString();
            int line = cu.getLineNumber(node.getStartPosition());

            addEdge(caller, typeName + "#" + methodName + "()",
                    true, CallEdge.InvocationType.METHOD_REFERENCE, line);
            return true;
        }
    }

    // ═════════════════════════════════════════════════════════════════
    //  CallEdge — a single directed edge in the call graph
    // ═════════════════════════════════════════════════════════════════

    /**
     * Represents a directed edge in the call graph from a caller method to a callee method.
     *
     * @param caller         fully-qualified caller method key
     * @param callee         fully-qualified callee method key
     * @param polymorphic    whether this call involves virtual dispatch
     * @param invocationType how the call was made (virtual, super, constructor, etc.)
     * @param lineNumber     source line where the call occurs
     */
    public record CallEdge(
            String caller,
            String callee,
            boolean polymorphic,
            InvocationType invocationType,
            int lineNumber
    ) {
        public CallEdge {
            Objects.requireNonNull(caller, "caller");
            Objects.requireNonNull(callee, "callee");
            Objects.requireNonNull(invocationType, "invocationType");
        }

        /** How the callee is invoked. */
        public enum InvocationType {
            /** Regular instance method call (may be virtual dispatch). */
            VIRTUAL,
            /** Explicit call to a superclass method. */
            SUPER,
            /** Constructor invocation ({@code new}, {@code this(...)}, {@code super(...)}). */
            CONSTRUCTOR,
            /** Static method call. */
            STATIC,
            /** Method reference (e.g. {@code Foo::bar}). */
            METHOD_REFERENCE
        }
    }

    // ═════════════════════════════════════════════════════════════════
    //  CallGraph — immutable call graph with query API
    // ═════════════════════════════════════════════════════════════════

    /**
     * An immutable call graph with adjacency structure and query methods.
     *
     * <p>The graph is directed: each entry in the adjacency map represents
     * "caller → {callees}". Methods that make no calls still appear as keys
     * with empty callee sets.</p>
     *
     * @param adjacency  caller → set of call edges
     * @param allMethods all method keys present in the graph (callers and callees)
     */
    public record CallGraph(
            Map<String, Set<CallEdge>> adjacency,
            Set<String> allMethods
    ) {
        public CallGraph {
            Objects.requireNonNull(adjacency, "adjacency");
            Objects.requireNonNull(allMethods, "allMethods");
        }

        /** Returns the number of unique methods in the graph. */
        public int methodCount() {
            return allMethods.size();
        }

        /** Returns the total number of edges in the graph. */
        public int edgeCount() {
            return adjacency.values().stream().mapToInt(Set::size).sum();
        }

        /**
         * Returns all outgoing call edges from the given method.
         *
         * @param methodKey fully-qualified method key
         * @return set of call edges (empty if method has no calls or is unknown)
         */
        public Set<CallEdge> callees(String methodKey) {
            return adjacency.getOrDefault(methodKey, Set.of());
        }

        /**
         * Returns all methods that call the given method.
         *
         * @param methodKey fully-qualified method key
         * @return set of caller method keys
         */
        public Set<String> callers(String methodKey) {
            Set<String> result = new LinkedHashSet<>();
            for (var entry : adjacency.entrySet()) {
                for (CallEdge edge : entry.getValue()) {
                    if (edge.callee().equals(methodKey)) {
                        result.add(entry.getKey());
                    }
                }
            }
            return Collections.unmodifiableSet(result);
        }

        /**
         * Returns all polymorphic call edges (those involving virtual dispatch).
         *
         * @return unmodifiable list of polymorphic edges
         */
        public List<CallEdge> polymorphicEdges() {
            return adjacency.values().stream()
                    .flatMap(Set::stream)
                    .filter(CallEdge::polymorphic)
                    .toList();
        }

        /**
         * Performs a transitive closure (reachability) analysis from the given method.
         *
         * @param methodKey starting method
         * @return all transitively reachable method keys
         */
        public Set<String> reachableFrom(String methodKey) {
            Set<String> visited = new LinkedHashSet<>();
            Deque<String> worklist = new ArrayDeque<>();
            worklist.add(methodKey);

            while (!worklist.isEmpty()) {
                String current = worklist.poll();
                if (!visited.add(current)) continue;

                for (CallEdge edge : callees(current)) {
                    if (!visited.contains(edge.callee())) {
                        worklist.add(edge.callee());
                    }
                }
            }

            visited.remove(methodKey); // exclude the starting method itself
            return Collections.unmodifiableSet(visited);
        }

        /**
         * Detects cycles in the call graph starting from a given method.
         *
         * @param methodKey starting method
         * @return {@code true} if the method is (transitively) recursive
         */
        public boolean isRecursive(String methodKey) {
            return reachableFrom(methodKey).contains(methodKey) ||
                    callees(methodKey).stream().anyMatch(e -> e.callee().equals(methodKey));
        }
    }
}
