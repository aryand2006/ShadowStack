package com.shadowstack.refactor.model;

import org.eclipse.jdt.core.dom.*;

import java.util.*;

/**
 * Rich semantic context for rule evaluation during AST analysis.
 *
 * <p>SemanticContext aggregates type resolution data, call graph information, variable scope
 * analysis, and other semantic metadata needed by refactoring rules to make safe transformation
 * decisions. It is constructed from the Eclipse JDT AST with full type bindings enabled.</p>
 *
 * <p>This context is immutable once constructed — rules receive a read-only view of the
 * semantic state at analysis time.</p>
 */
public final class SemanticContext {

    private final CompilationUnit compilationUnit;
    private final String sourceFilePath;
    private final String sourceCode;

    // Type resolution
    private final Map<String, ITypeBinding> typeBindings;
    private final Map<String, IMethodBinding> methodBindings;

    // Enclosing structure
    private final TypeDeclaration enclosingClass;
    private final MethodDeclaration enclosingMethod;
    private final String enclosingClassName;
    private final String enclosingMethodName;

    // Variable scope analysis
    private final Map<String, VariableInfo> variableScope;

    // Outer this references
    private final Set<String> outerThisReferences;

    // Reflection usage
    private final boolean hasReflectionUsage;
    private final Set<String> reflectionTargetClasses;

    // Concurrent context flags
    private final boolean inSynchronizedBlock;
    private final boolean usesConcurrentAPIs;

    // Call graph edges (simplified)
    private final Map<String, Set<String>> callGraph;

    private SemanticContext(Builder builder) {
        this.compilationUnit = builder.compilationUnit;
        this.sourceFilePath = builder.sourceFilePath;
        this.sourceCode = builder.sourceCode;
        this.typeBindings = builder.typeBindings != null
                ? Collections.unmodifiableMap(new HashMap<>(builder.typeBindings)) : Map.of();
        this.methodBindings = builder.methodBindings != null
                ? Collections.unmodifiableMap(new HashMap<>(builder.methodBindings)) : Map.of();
        this.enclosingClass = builder.enclosingClass;
        this.enclosingMethod = builder.enclosingMethod;
        this.enclosingClassName = builder.enclosingClassName;
        this.enclosingMethodName = builder.enclosingMethodName;
        this.variableScope = builder.variableScope != null
                ? Collections.unmodifiableMap(new HashMap<>(builder.variableScope)) : Map.of();
        this.outerThisReferences = builder.outerThisReferences != null
                ? Collections.unmodifiableSet(new HashSet<>(builder.outerThisReferences)) : Set.of();
        this.hasReflectionUsage = builder.hasReflectionUsage;
        this.reflectionTargetClasses = builder.reflectionTargetClasses != null
                ? Collections.unmodifiableSet(new HashSet<>(builder.reflectionTargetClasses)) : Set.of();
        this.inSynchronizedBlock = builder.inSynchronizedBlock;
        this.usesConcurrentAPIs = builder.usesConcurrentAPIs;
        this.callGraph = builder.callGraph != null
                ? Collections.unmodifiableMap(new HashMap<>(builder.callGraph)) : Map.of();
    }

    // --- Getters ---

    public CompilationUnit getCompilationUnit() { return compilationUnit; }
    public String getSourceFilePath() { return sourceFilePath; }
    public String getSourceCode() { return sourceCode; }
    public Map<String, ITypeBinding> getTypeBindings() { return typeBindings; }
    public Map<String, IMethodBinding> getMethodBindings() { return methodBindings; }
    public TypeDeclaration getEnclosingClass() { return enclosingClass; }
    public MethodDeclaration getEnclosingMethod() { return enclosingMethod; }
    public String getEnclosingClassName() { return enclosingClassName; }
    public String getEnclosingMethodName() { return enclosingMethodName; }
    public Map<String, VariableInfo> getVariableScope() { return variableScope; }
    public Set<String> getOuterThisReferences() { return outerThisReferences; }
    public boolean hasReflectionUsage() { return hasReflectionUsage; }
    public Set<String> getReflectionTargetClasses() { return reflectionTargetClasses; }
    public boolean isInSynchronizedBlock() { return inSynchronizedBlock; }
    public boolean usesConcurrentAPIs() { return usesConcurrentAPIs; }
    public Map<String, Set<String>> getCallGraph() { return callGraph; }

    /**
     * Resolves the line number for a given AST node position.
     */
    public int getLineNumber(int position) {
        if (compilationUnit != null) {
            return compilationUnit.getLineNumber(position);
        }
        return -1;
    }

    /**
     * Extracts source code for a given AST node range.
     */
    public String getSourceRange(int startPosition, int length) {
        if (sourceCode != null && startPosition >= 0 && startPosition + length <= sourceCode.length()) {
            return sourceCode.substring(startPosition, startPosition + length);
        }
        return "";
    }

    /**
     * Checks if a variable name is effectively final in the current scope.
     */
    public boolean isEffectivelyFinal(String variableName) {
        VariableInfo info = variableScope.get(variableName);
        return info != null && info.isEffectivelyFinal();
    }

    /**
     * Checks if the context is concurrent (synchronized block or concurrent API usage).
     */
    public boolean isConcurrentContext() {
        return inSynchronizedBlock || usesConcurrentAPIs;
    }

    /**
     * Holds detailed information about a variable in scope.
     */
    public record VariableInfo(
            String name,
            String typeName,
            ITypeBinding typeBinding,
            boolean isFinal,
            boolean isEffectivelyFinal,
            boolean isMutated,
            boolean isCapturedByInnerClass,
            int declarationLine,
            VariableKind kind
    ) {
        public enum VariableKind {
            LOCAL, PARAMETER, FIELD, CATCH_VARIABLE, FOR_VARIABLE
        }
    }

    // --- Builder ---

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private CompilationUnit compilationUnit;
        private String sourceFilePath;
        private String sourceCode;
        private Map<String, ITypeBinding> typeBindings;
        private Map<String, IMethodBinding> methodBindings;
        private TypeDeclaration enclosingClass;
        private MethodDeclaration enclosingMethod;
        private String enclosingClassName;
        private String enclosingMethodName;
        private Map<String, VariableInfo> variableScope;
        private Set<String> outerThisReferences;
        private boolean hasReflectionUsage;
        private Set<String> reflectionTargetClasses;
        private boolean inSynchronizedBlock;
        private boolean usesConcurrentAPIs;
        private Map<String, Set<String>> callGraph;

        public Builder compilationUnit(CompilationUnit cu) { this.compilationUnit = cu; return this; }
        public Builder sourceFilePath(String path) { this.sourceFilePath = path; return this; }
        public Builder sourceCode(String src) { this.sourceCode = src; return this; }
        public Builder typeBindings(Map<String, ITypeBinding> bindings) { this.typeBindings = bindings; return this; }
        public Builder methodBindings(Map<String, IMethodBinding> bindings) { this.methodBindings = bindings; return this; }
        public Builder enclosingClass(TypeDeclaration cls) { this.enclosingClass = cls; return this; }
        public Builder enclosingMethod(MethodDeclaration method) { this.enclosingMethod = method; return this; }
        public Builder enclosingClassName(String name) { this.enclosingClassName = name; return this; }
        public Builder enclosingMethodName(String name) { this.enclosingMethodName = name; return this; }
        public Builder variableScope(Map<String, VariableInfo> scope) { this.variableScope = scope; return this; }
        public Builder outerThisReferences(Set<String> refs) { this.outerThisReferences = refs; return this; }
        public Builder hasReflectionUsage(boolean flag) { this.hasReflectionUsage = flag; return this; }
        public Builder reflectionTargetClasses(Set<String> classes) { this.reflectionTargetClasses = classes; return this; }
        public Builder inSynchronizedBlock(boolean flag) { this.inSynchronizedBlock = flag; return this; }
        public Builder usesConcurrentAPIs(boolean flag) { this.usesConcurrentAPIs = flag; return this; }
        public Builder callGraph(Map<String, Set<String>> graph) { this.callGraph = graph; return this; }

        public SemanticContext build() {
            return new SemanticContext(this);
        }
    }
}
