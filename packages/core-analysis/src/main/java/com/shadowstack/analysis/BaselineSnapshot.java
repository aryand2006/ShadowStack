package com.shadowstack.analysis;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable snapshot of a project's baseline state captured during Phase 0.
 *
 * <p>A {@code BaselineSnapshot} records every observable characteristic of the source
 * tree at a point in time: compilation status, test outcomes, public API surface,
 * reflection usage, concurrency patterns, IO boundaries, complexity metrics, and
 * semantic fingerprints of public methods. This snapshot is the reference against
 * which all subsequent refactoring transformations are verified.</p>
 *
 * <p>All collection fields are defensively copied on construction and returned as
 * unmodifiable views. The snapshot is safe for concurrent reads and for
 * serialization via Jackson.</p>
 *
 * @see BaselineCapture
 * @see SemanticFingerprint
 */
public final class BaselineSnapshot {

    // ─── Compilation & test ──────────────────────────────────────────

    private final boolean compileSuccess;
    private final TestResults testResults;

    // ─── API surface ─────────────────────────────────────────────────

    private final List<MethodSignature> apiSignatures;

    // ─── Behavioural detections ──────────────────────────────────────

    private final List<ReflectionUsage> reflectionUsages;
    private final List<ConcurrencyPattern> concurrencyPatterns;
    private final List<IOBoundary> ioBoundaries;

    // ─── Metrics & fingerprints ──────────────────────────────────────

    private final Map<String, Integer> complexityMetrics;
    private final Map<String, String> semanticFingerprints;

    // ─── Metadata ────────────────────────────────────────────────────

    private final Instant timestamp;
    private final String sourceRoot;

    @JsonCreator
    public BaselineSnapshot(
            @JsonProperty("compileSuccess") boolean compileSuccess,
            @JsonProperty("testResults") TestResults testResults,
            @JsonProperty("apiSignatures") List<MethodSignature> apiSignatures,
            @JsonProperty("reflectionUsages") List<ReflectionUsage> reflectionUsages,
            @JsonProperty("concurrencyPatterns") List<ConcurrencyPattern> concurrencyPatterns,
            @JsonProperty("ioBoundaries") List<IOBoundary> ioBoundaries,
            @JsonProperty("complexityMetrics") Map<String, Integer> complexityMetrics,
            @JsonProperty("semanticFingerprints") Map<String, String> semanticFingerprints,
            @JsonProperty("timestamp") Instant timestamp,
            @JsonProperty("sourceRoot") String sourceRoot) {

        this.compileSuccess = compileSuccess;
        this.testResults = Objects.requireNonNull(testResults, "testResults must not be null");
        this.apiSignatures = List.copyOf(Objects.requireNonNull(apiSignatures, "apiSignatures"));
        this.reflectionUsages = List.copyOf(Objects.requireNonNull(reflectionUsages, "reflectionUsages"));
        this.concurrencyPatterns = List.copyOf(Objects.requireNonNull(concurrencyPatterns, "concurrencyPatterns"));
        this.ioBoundaries = List.copyOf(Objects.requireNonNull(ioBoundaries, "ioBoundaries"));
        this.complexityMetrics = Map.copyOf(Objects.requireNonNull(complexityMetrics, "complexityMetrics"));
        this.semanticFingerprints = Map.copyOf(Objects.requireNonNull(semanticFingerprints, "semanticFingerprints"));
        this.timestamp = Objects.requireNonNull(timestamp, "timestamp");
        this.sourceRoot = Objects.requireNonNull(sourceRoot, "sourceRoot");
    }

    // ─── Accessors ───────────────────────────────────────────────────

    /** Whether the project compiled successfully. */
    public boolean compileSuccess() { return compileSuccess; }

    /** Results of the baseline test run. */
    public TestResults testResults() { return testResults; }

    /** Public API signatures (classes, methods, fields) discovered. */
    public List<MethodSignature> apiSignatures() { return apiSignatures; }

    /** Reflection usage sites detected in the source tree. */
    public List<ReflectionUsage> reflectionUsages() { return reflectionUsages; }

    /** Concurrency patterns detected (synchronized, locks, executors). */
    public List<ConcurrencyPattern> concurrencyPatterns() { return concurrencyPatterns; }

    /** IO boundary operations detected (file, socket, stream). */
    public List<IOBoundary> ioBoundaries() { return ioBoundaries; }

    /** Cyclomatic complexity per fully-qualified method name. */
    public Map<String, Integer> complexityMetrics() { return complexityMetrics; }

    /** Semantic fingerprints (SHA-256) per fully-qualified method name. */
    public Map<String, String> semanticFingerprints() { return semanticFingerprints; }

    /** When this snapshot was captured. */
    public Instant timestamp() { return timestamp; }

    /** Absolute path to the source root that was analyzed. */
    public String sourceRoot() { return sourceRoot; }

    // ─── Builder ─────────────────────────────────────────────────────

    /**
     * Creates a new builder pre-populated with defaults.
     *
     * @return a fresh builder instance
     */
    public static Builder builder() {
        return new Builder();
    }

    @Override
    public String toString() {
        return "BaselineSnapshot{" +
                "compileSuccess=" + compileSuccess +
                ", tests=" + testResults +
                ", apiSignatures=" + apiSignatures.size() +
                ", reflectionUsages=" + reflectionUsages.size() +
                ", concurrencyPatterns=" + concurrencyPatterns.size() +
                ", ioBoundaries=" + ioBoundaries.size() +
                ", complexityEntries=" + complexityMetrics.size() +
                ", fingerprints=" + semanticFingerprints.size() +
                ", timestamp=" + timestamp +
                ", sourceRoot='" + sourceRoot + '\'' +
                '}';
    }

    // ═════════════════════════════════════════════════════════════════
    //  Nested DTOs
    // ═════════════════════════════════════════════════════════════════

    /**
     * Aggregated results from a baseline test run.
     */
    public record TestResults(
            int totalTests,
            int passed,
            int failed,
            int skipped,
            List<String> failureMessages
    ) {
        public TestResults {
            failureMessages = failureMessages == null
                    ? List.of()
                    : List.copyOf(failureMessages);
        }

        /** Whether every executed test passed. */
        public boolean allPassed() {
            return failed == 0;
        }
    }

    /**
     * Represents a public API method signature.
     *
     * @param qualifiedClassName  fully-qualified class name
     * @param methodName          method name (or {@code "<init>"} for constructors)
     * @param returnType          return type as a string
     * @param parameterTypes      list of parameter type strings
     * @param modifiers           access modifiers as a bitmask
     * @param isConstructor       whether this is a constructor
     */
    public record MethodSignature(
            String qualifiedClassName,
            String methodName,
            String returnType,
            List<String> parameterTypes,
            int modifiers,
            boolean isConstructor
    ) {
        public MethodSignature {
            Objects.requireNonNull(qualifiedClassName, "qualifiedClassName");
            Objects.requireNonNull(methodName, "methodName");
            Objects.requireNonNull(returnType, "returnType");
            parameterTypes = parameterTypes == null
                    ? List.of()
                    : List.copyOf(parameterTypes);
        }

        /** Returns a human-readable signature string. */
        public String toDisplayString() {
            return qualifiedClassName + "#" + methodName +
                    "(" + String.join(", ", parameterTypes) + ")" +
                    " -> " + returnType;
        }
    }

    /**
     * A detected usage of Java reflection in the source tree.
     *
     * @param location       fully-qualified class + line number
     * @param reflectionCall the reflective API call (e.g. {@code Class.forName})
     * @param targetType     the target type being reflected upon, if resolvable
     * @param lineNumber     source line number
     */
    public record ReflectionUsage(
            String location,
            String reflectionCall,
            String targetType,
            int lineNumber
    ) {
        public ReflectionUsage {
            Objects.requireNonNull(location, "location");
            Objects.requireNonNull(reflectionCall, "reflectionCall");
        }
    }

    /**
     * A detected concurrency pattern.
     *
     * @param location    fully-qualified class + line
     * @param patternType the type of concurrency primitive (SYNCHRONIZED, LOCK, EXECUTOR, ATOMIC, etc.)
     * @param detail      human-readable detail
     * @param lineNumber  source line number
     */
    public record ConcurrencyPattern(
            String location,
            ConcurrencyType patternType,
            String detail,
            int lineNumber
    ) {
        public ConcurrencyPattern {
            Objects.requireNonNull(location, "location");
            Objects.requireNonNull(patternType, "patternType");
        }
    }

    /** Classification of concurrency primitives. */
    public enum ConcurrencyType {
        SYNCHRONIZED_BLOCK,
        SYNCHRONIZED_METHOD,
        REENTRANT_LOCK,
        READ_WRITE_LOCK,
        EXECUTOR_SERVICE,
        FORK_JOIN,
        ATOMIC_VARIABLE,
        VOLATILE_FIELD,
        CONCURRENT_COLLECTION,
        COMPLETABLE_FUTURE,
        THREAD_CREATION,
        OTHER
    }

    /**
     * A detected IO boundary operation.
     *
     * @param location   fully-qualified class + line
     * @param ioType     FILE, SOCKET, STREAM, DATABASE, NETWORK, OTHER
     * @param detail     human-readable detail of the IO operation
     * @param lineNumber source line number
     */
    public record IOBoundary(
            String location,
            IOType ioType,
            String detail,
            int lineNumber
    ) {
        public IOBoundary {
            Objects.requireNonNull(location, "location");
            Objects.requireNonNull(ioType, "ioType");
        }
    }

    /** Classification of IO boundary types. */
    public enum IOType {
        FILE,
        SOCKET,
        STREAM,
        DATABASE,
        NETWORK,
        CONSOLE,
        OTHER
    }

    // ═════════════════════════════════════════════════════════════════
    //  Builder
    // ═════════════════════════════════════════════════════════════════

    /**
     * Mutable builder for constructing {@link BaselineSnapshot} instances.
     */
    public static final class Builder {

        private boolean compileSuccess;
        private TestResults testResults = new TestResults(0, 0, 0, 0, List.of());
        private List<MethodSignature> apiSignatures = List.of();
        private List<ReflectionUsage> reflectionUsages = List.of();
        private List<ConcurrencyPattern> concurrencyPatterns = List.of();
        private List<IOBoundary> ioBoundaries = List.of();
        private Map<String, Integer> complexityMetrics = Map.of();
        private Map<String, String> semanticFingerprints = Map.of();
        private Instant timestamp = Instant.now();
        private String sourceRoot = "";

        private Builder() {}

        public Builder compileSuccess(boolean compileSuccess) {
            this.compileSuccess = compileSuccess;
            return this;
        }

        public Builder testResults(TestResults testResults) {
            this.testResults = Objects.requireNonNull(testResults);
            return this;
        }

        public Builder apiSignatures(List<MethodSignature> apiSignatures) {
            this.apiSignatures = Objects.requireNonNull(apiSignatures);
            return this;
        }

        public Builder reflectionUsages(List<ReflectionUsage> reflectionUsages) {
            this.reflectionUsages = Objects.requireNonNull(reflectionUsages);
            return this;
        }

        public Builder concurrencyPatterns(List<ConcurrencyPattern> concurrencyPatterns) {
            this.concurrencyPatterns = Objects.requireNonNull(concurrencyPatterns);
            return this;
        }

        public Builder ioBoundaries(List<IOBoundary> ioBoundaries) {
            this.ioBoundaries = Objects.requireNonNull(ioBoundaries);
            return this;
        }

        public Builder complexityMetrics(Map<String, Integer> complexityMetrics) {
            this.complexityMetrics = Objects.requireNonNull(complexityMetrics);
            return this;
        }

        public Builder semanticFingerprints(Map<String, String> semanticFingerprints) {
            this.semanticFingerprints = Objects.requireNonNull(semanticFingerprints);
            return this;
        }

        public Builder timestamp(Instant timestamp) {
            this.timestamp = Objects.requireNonNull(timestamp);
            return this;
        }

        public Builder sourceRoot(String sourceRoot) {
            this.sourceRoot = Objects.requireNonNull(sourceRoot);
            return this;
        }

        public Builder sourceRoot(Path sourceRoot) {
            this.sourceRoot = Objects.requireNonNull(sourceRoot).toAbsolutePath().toString();
            return this;
        }

        /**
         * Builds an immutable {@link BaselineSnapshot}.
         *
         * @return the constructed snapshot
         */
        public BaselineSnapshot build() {
            return new BaselineSnapshot(
                    compileSuccess, testResults, apiSignatures, reflectionUsages,
                    concurrencyPatterns, ioBoundaries, complexityMetrics,
                    semanticFingerprints, timestamp, sourceRoot);
        }
    }
}
