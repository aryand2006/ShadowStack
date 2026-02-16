package com.shadowstack.adapters.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.nio.file.Path;
import java.time.Instant;
import java.util.*;

/**
 * A rich semantic model of a parsed codebase, capturing structural, relational,
 * and metric information extracted from source code.
 *
 * <p>The semantic model is the central data structure flowing through ShadowStack's
 * pipeline. It is produced by a {@link com.shadowstack.adapters.LanguageAdapter}
 * and consumed by the refactor engine, verification engine, and analysis tools.</p>
 *
 * <h3>Contents</h3>
 * <ul>
 *   <li>Package structure and class hierarchy</li>
 *   <li>Method signatures with full type resolution</li>
 *   <li>Call graph edges (caller → callee relationships)</li>
 *   <li>Data flow nodes (def-use chains)</li>
 *   <li>Type resolution metadata</li>
 *   <li>Annotation metadata per element</li>
 *   <li>Complexity metrics per method</li>
 *   <li>Method purity classifications</li>
 * </ul>
 *
 * <p>Instances are built via {@link Builder} and are effectively immutable once
 * constructed (all returned collections are unmodifiable views).</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public final class SemanticModel {

    // ── Purity classification ────────────────────────────────────────────

    /**
     * Classifies the side-effect profile of a method.
     *
     * <p>Purity is determined through static analysis of field reads, field writes,
     * I/O operations, and invocations of impure methods.</p>
     */
    public enum Purity {
        /** No side effects; result depends only on parameters. */
        PURE,
        /** Reads instance or static state but does not mutate it. */
        READS_STATE,
        /** Mutates instance or static state. */
        MUTATES_STATE,
        /** Performs I/O, reflection, native calls, or other observable side effects. */
        SIDE_EFFECTING,
        /** Purity could not be determined (e.g., native method, unresolved binding). */
        UNKNOWN
    }

    // ── Nested record types ──────────────────────────────────────────────

    /**
     * Represents a resolved package in the source tree.
     *
     * @param name           fully qualified package name (e.g., "com.shadowstack.adapters")
     * @param sourceFiles    paths to source files belonging to this package
     * @param subPackages    immediate child package names
     */
    public record PackageInfo(
            @JsonProperty("name") String name,
            @JsonProperty("sourceFiles") List<String> sourceFiles,
            @JsonProperty("subPackages") List<String> subPackages
    ) {
        public PackageInfo {
            Objects.requireNonNull(name, "name");
            sourceFiles = sourceFiles != null ? List.copyOf(sourceFiles) : List.of();
            subPackages = subPackages != null ? List.copyOf(subPackages) : List.of();
        }
    }

    /**
     * Describes a class or interface in the model.
     *
     * @param fullyQualifiedName  e.g., "com.shadowstack.adapters.model.SemanticModel"
     * @param simpleName          e.g., "SemanticModel"
     * @param packageName         owning package
     * @param superClassName      fully qualified superclass (null for java.lang.Object)
     * @param interfaces          implemented interface FQNs
     * @param modifiers           access and other modifiers (public, abstract, final, …)
     * @param annotations         annotation FQNs present on this class
     * @param sourceFile          relative path to the source file
     * @param startLine           first line of the class declaration
     * @param endLine             last line of the class declaration
     * @param isInterface         whether this is an interface rather than a class
     * @param isEnum              whether this is an enum type
     * @param isRecord            whether this is a record type
     */
    public record ClassInfo(
            @JsonProperty("fullyQualifiedName") String fullyQualifiedName,
            @JsonProperty("simpleName") String simpleName,
            @JsonProperty("packageName") String packageName,
            @JsonProperty("superClassName") String superClassName,
            @JsonProperty("interfaces") List<String> interfaces,
            @JsonProperty("modifiers") List<String> modifiers,
            @JsonProperty("annotations") List<String> annotations,
            @JsonProperty("sourceFile") String sourceFile,
            @JsonProperty("startLine") int startLine,
            @JsonProperty("endLine") int endLine,
            @JsonProperty("isInterface") boolean isInterface,
            @JsonProperty("isEnum") boolean isEnum,
            @JsonProperty("isRecord") boolean isRecord
    ) {
        public ClassInfo {
            Objects.requireNonNull(fullyQualifiedName, "fullyQualifiedName");
            Objects.requireNonNull(simpleName, "simpleName");
            interfaces = interfaces != null ? List.copyOf(interfaces) : List.of();
            modifiers = modifiers != null ? List.copyOf(modifiers) : List.of();
            annotations = annotations != null ? List.copyOf(annotations) : List.of();
        }
    }

    /**
     * Describes a method or constructor.
     *
     * @param signature           unique method signature string
     * @param name                simple method name
     * @param owningClass         FQN of the declaring class
     * @param returnType          fully qualified return type
     * @param parameterTypes      ordered list of parameter type FQNs
     * @param parameterNames      ordered list of parameter names
     * @param modifiers           access and other modifiers
     * @param annotations         annotation FQNs on this method
     * @param thrownExceptions    declared checked exception FQNs
     * @param startLine           first line
     * @param endLine             last line
     * @param cyclomaticComplexity McCabe cyclomatic complexity
     * @param cognitiveComplexity  SonarSource cognitive complexity
     * @param lineCount           total line count including blanks and comments
     * @param purity              side-effect classification
     */
    public record MethodInfo(
            @JsonProperty("signature") String signature,
            @JsonProperty("name") String name,
            @JsonProperty("owningClass") String owningClass,
            @JsonProperty("returnType") String returnType,
            @JsonProperty("parameterTypes") List<String> parameterTypes,
            @JsonProperty("parameterNames") List<String> parameterNames,
            @JsonProperty("modifiers") List<String> modifiers,
            @JsonProperty("annotations") List<String> annotations,
            @JsonProperty("thrownExceptions") List<String> thrownExceptions,
            @JsonProperty("startLine") int startLine,
            @JsonProperty("endLine") int endLine,
            @JsonProperty("cyclomaticComplexity") int cyclomaticComplexity,
            @JsonProperty("cognitiveComplexity") int cognitiveComplexity,
            @JsonProperty("lineCount") int lineCount,
            @JsonProperty("purity") Purity purity
    ) {
        public MethodInfo {
            Objects.requireNonNull(signature, "signature");
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(owningClass, "owningClass");
            parameterTypes = parameterTypes != null ? List.copyOf(parameterTypes) : List.of();
            parameterNames = parameterNames != null ? List.copyOf(parameterNames) : List.of();
            modifiers = modifiers != null ? List.copyOf(modifiers) : List.of();
            annotations = annotations != null ? List.copyOf(annotations) : List.of();
            thrownExceptions = thrownExceptions != null ? List.copyOf(thrownExceptions) : List.of();
            purity = purity != null ? purity : Purity.UNKNOWN;
        }
    }

    /**
     * Describes a field declaration.
     *
     * @param name           field name
     * @param owningClass    FQN of the declaring class
     * @param typeName       fully qualified type
     * @param modifiers      access and other modifiers
     * @param annotations    annotation FQNs
     * @param line           declaration line number
     */
    public record FieldInfo(
            @JsonProperty("name") String name,
            @JsonProperty("owningClass") String owningClass,
            @JsonProperty("typeName") String typeName,
            @JsonProperty("modifiers") List<String> modifiers,
            @JsonProperty("annotations") List<String> annotations,
            @JsonProperty("line") int line
    ) {
        public FieldInfo {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(owningClass, "owningClass");
            modifiers = modifiers != null ? List.copyOf(modifiers) : List.of();
            annotations = annotations != null ? List.copyOf(annotations) : List.of();
        }
    }

    /**
     * An edge in the call graph: one method invoking another.
     *
     * @param callerSignature  signature of the calling method
     * @param calleeSignature  signature of the called method
     * @param callSiteLine     line number of the invocation
     * @param isVirtual        whether the call is a virtual (polymorphic) dispatch
     */
    public record CallGraphEdge(
            @JsonProperty("callerSignature") String callerSignature,
            @JsonProperty("calleeSignature") String calleeSignature,
            @JsonProperty("callSiteLine") int callSiteLine,
            @JsonProperty("isVirtual") boolean isVirtual
    ) {
        public CallGraphEdge {
            Objects.requireNonNull(callerSignature, "callerSignature");
            Objects.requireNonNull(calleeSignature, "calleeSignature");
        }
    }

    /**
     * A node in the data-flow graph representing a variable definition or use.
     *
     * @param variableName   the variable being tracked
     * @param owningMethod   signature of the enclosing method
     * @param kind           DEF, USE, or PHI
     * @param line           source line
     * @param typeName       resolved type at this point
     */
    public record DataFlowNode(
            @JsonProperty("variableName") String variableName,
            @JsonProperty("owningMethod") String owningMethod,
            @JsonProperty("kind") DataFlowKind kind,
            @JsonProperty("line") int line,
            @JsonProperty("typeName") String typeName
    ) {
        public DataFlowNode {
            Objects.requireNonNull(variableName, "variableName");
            Objects.requireNonNull(owningMethod, "owningMethod");
            Objects.requireNonNull(kind, "kind");
        }
    }

    /**
     * Kind of data-flow event.
     */
    public enum DataFlowKind {
        /** Variable definition (assignment). */
        DEF,
        /** Variable use (read). */
        USE,
        /** SSA phi node (merge point). */
        PHI
    }

    /**
     * Stores resolved type information for a type reference in the codebase.
     *
     * @param referencedType  the type as written in source
     * @param resolvedFqn     the fully qualified resolved name
     * @param sourceFile      file where the reference appears
     * @param line            line of the reference
     * @param isFromSource    whether the type is defined in the analyzed source (vs. a library)
     */
    public record TypeResolution(
            @JsonProperty("referencedType") String referencedType,
            @JsonProperty("resolvedFqn") String resolvedFqn,
            @JsonProperty("sourceFile") String sourceFile,
            @JsonProperty("line") int line,
            @JsonProperty("isFromSource") boolean isFromSource
    ) {
        public TypeResolution {
            Objects.requireNonNull(referencedType, "referencedType");
            Objects.requireNonNull(resolvedFqn, "resolvedFqn");
        }
    }

    /**
     * Annotation metadata attached to a program element.
     *
     * @param annotationFqn  fully qualified annotation type name
     * @param targetElement  the element this annotation is attached to (class FQN, method signature, etc.)
     * @param attributes     annotation attribute key-value pairs
     */
    public record AnnotationInfo(
            @JsonProperty("annotationFqn") String annotationFqn,
            @JsonProperty("targetElement") String targetElement,
            @JsonProperty("attributes") Map<String, String> attributes
    ) {
        public AnnotationInfo {
            Objects.requireNonNull(annotationFqn, "annotationFqn");
            Objects.requireNonNull(targetElement, "targetElement");
            attributes = attributes != null ? Map.copyOf(attributes) : Map.of();
        }
    }

    // ── Instance fields (all immutable) ──────────────────────────────────

    private final String languageId;
    private final String languageVersion;
    private final Path sourceRoot;
    private final Instant createdAt;

    private final List<PackageInfo> packages;
    private final List<ClassInfo> classes;
    private final List<MethodInfo> methods;
    private final List<FieldInfo> fields;
    private final List<CallGraphEdge> callGraphEdges;
    private final List<DataFlowNode> dataFlowNodes;
    private final List<TypeResolution> typeResolutions;
    private final List<AnnotationInfo> annotations;
    private final Map<String, Object> metadata;

    private SemanticModel(Builder builder) {
        this.languageId = Objects.requireNonNull(builder.languageId, "languageId");
        this.languageVersion = Objects.requireNonNull(builder.languageVersion, "languageVersion");
        this.sourceRoot = Objects.requireNonNull(builder.sourceRoot, "sourceRoot");
        this.createdAt = builder.createdAt != null ? builder.createdAt : Instant.now();

        this.packages = List.copyOf(builder.packages);
        this.classes = List.copyOf(builder.classes);
        this.methods = List.copyOf(builder.methods);
        this.fields = List.copyOf(builder.fields);
        this.callGraphEdges = List.copyOf(builder.callGraphEdges);
        this.dataFlowNodes = List.copyOf(builder.dataFlowNodes);
        this.typeResolutions = List.copyOf(builder.typeResolutions);
        this.annotations = List.copyOf(builder.annotations);
        this.metadata = Map.copyOf(builder.metadata);
    }

    // ── Accessors ────────────────────────────────────────────────────────

    @JsonProperty public String languageId() { return languageId; }
    @JsonProperty public String languageVersion() { return languageVersion; }
    public Path sourceRoot() { return sourceRoot; }
    @JsonProperty public Instant createdAt() { return createdAt; }

    @JsonProperty public List<PackageInfo> packages() { return packages; }
    @JsonProperty public List<ClassInfo> classes() { return classes; }
    @JsonProperty public List<MethodInfo> methods() { return methods; }
    @JsonProperty public List<FieldInfo> fields() { return fields; }
    @JsonProperty public List<CallGraphEdge> callGraphEdges() { return callGraphEdges; }
    @JsonProperty public List<DataFlowNode> dataFlowNodes() { return dataFlowNodes; }
    @JsonProperty public List<TypeResolution> typeResolutions() { return typeResolutions; }
    @JsonProperty public List<AnnotationInfo> annotations() { return annotations; }
    @JsonProperty public Map<String, Object> metadata() { return metadata; }

    // ── Convenience query methods ────────────────────────────────────────

    /**
     * Finds all methods belonging to a given class.
     *
     * @param classFqn fully qualified class name
     * @return unmodifiable list of matching methods
     */
    public List<MethodInfo> methodsOf(String classFqn) {
        Objects.requireNonNull(classFqn, "classFqn");
        return methods.stream()
                .filter(m -> classFqn.equals(m.owningClass()))
                .toList();
    }

    /**
     * Finds all direct callers of a given method.
     *
     * @param calleeSignature method signature to find callers of
     * @return list of caller signatures
     */
    public List<String> callersOf(String calleeSignature) {
        Objects.requireNonNull(calleeSignature, "calleeSignature");
        return callGraphEdges.stream()
                .filter(e -> calleeSignature.equals(e.calleeSignature()))
                .map(CallGraphEdge::callerSignature)
                .distinct()
                .toList();
    }

    /**
     * Returns the total number of classes in the model.
     *
     * @return class count
     */
    public int classCount() {
        return classes.size();
    }

    /**
     * Returns the total number of methods in the model.
     *
     * @return method count
     */
    public int methodCount() {
        return methods.size();
    }

    /**
     * Finds a class by its fully qualified name.
     *
     * @param fqn fully qualified class name
     * @return the class info, or empty if not found
     */
    public Optional<ClassInfo> findClass(String fqn) {
        Objects.requireNonNull(fqn, "fqn");
        return classes.stream()
                .filter(c -> fqn.equals(c.fullyQualifiedName()))
                .findFirst();
    }

    /**
     * Returns a new builder for constructing semantic models.
     *
     * @return a fresh builder instance
     */
    public static Builder builder() {
        return new Builder();
    }

    // ── Builder ──────────────────────────────────────────────────────────

    /**
     * Mutable builder for constructing {@link SemanticModel} instances.
     */
    public static final class Builder {
        private String languageId;
        private String languageVersion;
        private Path sourceRoot;
        private Instant createdAt;

        private final List<PackageInfo> packages = new ArrayList<>();
        private final List<ClassInfo> classes = new ArrayList<>();
        private final List<MethodInfo> methods = new ArrayList<>();
        private final List<FieldInfo> fields = new ArrayList<>();
        private final List<CallGraphEdge> callGraphEdges = new ArrayList<>();
        private final List<DataFlowNode> dataFlowNodes = new ArrayList<>();
        private final List<TypeResolution> typeResolutions = new ArrayList<>();
        private final List<AnnotationInfo> annotations = new ArrayList<>();
        private final Map<String, Object> metadata = new LinkedHashMap<>();

        private Builder() {}

        public Builder languageId(String languageId) { this.languageId = languageId; return this; }
        public Builder languageVersion(String languageVersion) { this.languageVersion = languageVersion; return this; }
        public Builder sourceRoot(Path sourceRoot) { this.sourceRoot = sourceRoot; return this; }
        public Builder createdAt(Instant createdAt) { this.createdAt = createdAt; return this; }

        public Builder addPackage(PackageInfo pkg) { packages.add(Objects.requireNonNull(pkg)); return this; }
        public Builder addClass(ClassInfo cls) { classes.add(Objects.requireNonNull(cls)); return this; }
        public Builder addMethod(MethodInfo method) { methods.add(Objects.requireNonNull(method)); return this; }
        public Builder addField(FieldInfo field) { fields.add(Objects.requireNonNull(field)); return this; }
        public Builder addCallGraphEdge(CallGraphEdge edge) { callGraphEdges.add(Objects.requireNonNull(edge)); return this; }
        public Builder addDataFlowNode(DataFlowNode node) { dataFlowNodes.add(Objects.requireNonNull(node)); return this; }
        public Builder addTypeResolution(TypeResolution res) { typeResolutions.add(Objects.requireNonNull(res)); return this; }
        public Builder addAnnotation(AnnotationInfo ann) { annotations.add(Objects.requireNonNull(ann)); return this; }
        public Builder putMetadata(String key, Object value) { metadata.put(Objects.requireNonNull(key), value); return this; }

        public Builder addAllPackages(Collection<PackageInfo> pkgs) { packages.addAll(pkgs); return this; }
        public Builder addAllClasses(Collection<ClassInfo> cls) { classes.addAll(cls); return this; }
        public Builder addAllMethods(Collection<MethodInfo> ms) { methods.addAll(ms); return this; }
        public Builder addAllFields(Collection<FieldInfo> fs) { fields.addAll(fs); return this; }
        public Builder addAllCallGraphEdges(Collection<CallGraphEdge> edges) { callGraphEdges.addAll(edges); return this; }
        public Builder addAllDataFlowNodes(Collection<DataFlowNode> nodes) { dataFlowNodes.addAll(nodes); return this; }

        /**
         * Constructs the immutable {@link SemanticModel}.
         *
         * @return a new semantic model
         * @throws NullPointerException if required fields are missing
         */
        public SemanticModel build() {
            return new SemanticModel(this);
        }
    }

    @Override
    public String toString() {
        return "SemanticModel{" +
                "language=" + languageId + '/' + languageVersion +
                ", classes=" + classes.size() +
                ", methods=" + methods.size() +
                ", callEdges=" + callGraphEdges.size() +
                ", sourceRoot=" + sourceRoot +
                '}';
    }
}
