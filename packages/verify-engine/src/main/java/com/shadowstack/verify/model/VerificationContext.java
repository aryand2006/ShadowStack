package com.shadowstack.verify.model;

import java.nio.file.Path;
import java.util.*;

/**
 * Context provided to verification layers for executing their checks.
 *
 * <p>Contains paths to source files, compiled classes, test suites, and configuration
 * needed by the various verification layers to perform their analysis.</p>
 */
public final class VerificationContext {

    private final Path projectRoot;
    private final Path sourceRoot;
    private final Path outputRoot;
    private final Path testSourceRoot;
    private final Path testOutputRoot;
    private final List<Path> classpath;
    private final List<Path> testClasspath;
    private final String originalSource;
    private final String transformedSource;
    private final Path originalClassFile;
    private final Path transformedClassFile;
    private final Map<String, String> goldenMasterSnapshots;
    private final Map<String, Object> configuration;

    private VerificationContext(Builder builder) {
        this.projectRoot = builder.projectRoot;
        this.sourceRoot = builder.sourceRoot;
        this.outputRoot = builder.outputRoot;
        this.testSourceRoot = builder.testSourceRoot;
        this.testOutputRoot = builder.testOutputRoot;
        this.classpath = builder.classpath != null ? List.copyOf(builder.classpath) : List.of();
        this.testClasspath = builder.testClasspath != null ? List.copyOf(builder.testClasspath) : List.of();
        this.originalSource = builder.originalSource;
        this.transformedSource = builder.transformedSource;
        this.originalClassFile = builder.originalClassFile;
        this.transformedClassFile = builder.transformedClassFile;
        this.goldenMasterSnapshots = builder.goldenMasterSnapshots != null
                ? Map.copyOf(builder.goldenMasterSnapshots) : Map.of();
        // Mutable so the pipeline can publish upstream layer signals for SemanticRiskScorer.
        this.configuration = builder.configuration != null
                ? new java.util.concurrent.ConcurrentHashMap<>(builder.configuration)
                : new java.util.concurrent.ConcurrentHashMap<>();
    }

    public Path getProjectRoot() { return projectRoot; }
    public Path getSourceRoot() { return sourceRoot; }
    public Path getOutputRoot() { return outputRoot; }
    public Path getTestSourceRoot() { return testSourceRoot; }
    public Path getTestOutputRoot() { return testOutputRoot; }
    public List<Path> getClasspath() { return classpath; }
    public List<Path> getTestClasspath() { return testClasspath; }
    public String getOriginalSource() { return originalSource; }
    public String getTransformedSource() { return transformedSource; }
    public Path getOriginalClassFile() { return originalClassFile; }
    public Path getTransformedClassFile() { return transformedClassFile; }
    public Map<String, String> getGoldenMasterSnapshots() { return goldenMasterSnapshots; }
    public Map<String, Object> getConfiguration() { return configuration; }

    @SuppressWarnings("unchecked")
    public <T> T getConfig(String key, T defaultValue) {
        Object value = configuration.get(key);
        return value != null ? (T) value : defaultValue;
    }

    /** Publish a signal for downstream aggregators (e.g. SemanticRiskScorer). */
    public void putConfig(String key, Object value) {
        if (key != null) {
            if (value == null) {
                configuration.remove(key);
            } else {
                configuration.put(key, value);
            }
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private Path projectRoot;
        private Path sourceRoot;
        private Path outputRoot;
        private Path testSourceRoot;
        private Path testOutputRoot;
        private List<Path> classpath;
        private List<Path> testClasspath;
        private String originalSource;
        private String transformedSource;
        private Path originalClassFile;
        private Path transformedClassFile;
        private Map<String, String> goldenMasterSnapshots;
        private Map<String, Object> configuration;

        public Builder projectRoot(Path p) { this.projectRoot = p; return this; }
        public Builder sourceRoot(Path p) { this.sourceRoot = p; return this; }
        public Builder outputRoot(Path p) { this.outputRoot = p; return this; }
        public Builder testSourceRoot(Path p) { this.testSourceRoot = p; return this; }
        public Builder testOutputRoot(Path p) { this.testOutputRoot = p; return this; }
        public Builder classpath(List<Path> cp) { this.classpath = cp; return this; }
        public Builder testClasspath(List<Path> cp) { this.testClasspath = cp; return this; }
        public Builder originalSource(String s) { this.originalSource = s; return this; }
        public Builder transformedSource(String s) { this.transformedSource = s; return this; }
        public Builder originalClassFile(Path p) { this.originalClassFile = p; return this; }
        public Builder transformedClassFile(Path p) { this.transformedClassFile = p; return this; }
        public Builder goldenMasterSnapshots(Map<String, String> s) { this.goldenMasterSnapshots = s; return this; }
        public Builder configuration(Map<String, Object> c) { this.configuration = c; return this; }

        public VerificationContext build() {
            return new VerificationContext(this);
        }
    }
}
