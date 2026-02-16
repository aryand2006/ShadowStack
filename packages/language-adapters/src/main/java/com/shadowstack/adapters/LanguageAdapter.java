package com.shadowstack.adapters;

import com.shadowstack.adapters.model.*;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Core abstraction for language-specific parsing, semantic analysis, refactoring,
 * and verification in the ShadowStack modernization engine.
 *
 * <p>Each supported language provides an implementation of this interface that
 * bridges the language's native toolchain (parser, type resolver, AST rewriter)
 * into ShadowStack's language-agnostic pipeline.</p>
 *
 * <h3>Contract</h3>
 * <ol>
 *   <li>{@link #parse} produces a raw AST-level {@link SemanticModel}.</li>
 *   <li>{@link #buildSemanticModel} enriches it with call graphs, data flow,
 *       type resolution, and metrics — using sensible defaults.</li>
 *   <li>{@link #listRefactorCandidates} identifies transformation opportunities.</li>
 *   <li>{@link #applyRefactor} executes a single transformation and produces a diff.</li>
 *   <li>{@link #verifyPatch} runs multi-layer verification on the result.</li>
 * </ol>
 *
 * <p>Implementations must be thread-safe for concurrent invocations of
 * different source roots. Thread safety for the <em>same</em> source root
 * is not required.</p>
 */
public interface LanguageAdapter {

    /**
     * Returns the canonical language identifier (e.g., "java", "cobol", "python").
     *
     * @return non-null, lowercase language identifier
     */
    String languageId();

    /**
     * Returns the language version this adapter targets (e.g., "21", "85", "3.12").
     *
     * @return non-null version string
     */
    String languageVersion();

    /**
     * Parses source files under the given root and produces a {@link SemanticModel}
     * according to the supplied configuration.
     *
     * @param sourceRoot the root directory of the source tree to parse
     * @param config     adapter configuration controlling parse behavior
     * @return a semantic model representing the parsed source
     * @throws NullPointerException  if any argument is null
     * @throws AdapterException      if parsing fails
     */
    SemanticModel parse(Path sourceRoot, LanguageAdapterConfig config);

    /**
     * Convenience method that parses and builds a fully enriched semantic model
     * using default configuration.
     *
     * <p>Equivalent to calling {@link #parse(Path, LanguageAdapterConfig)}
     * with {@link LanguageAdapterConfig#defaults()}.</p>
     *
     * @param sourceRoot the root directory of the source tree
     * @return a fully enriched semantic model
     * @throws NullPointerException  if sourceRoot is null
     * @throws AdapterException      if parsing or analysis fails
     */
    SemanticModel buildSemanticModel(Path sourceRoot);

    /**
     * Identifies potential refactoring candidates in the given semantic model
     * according to the provided rule set.
     *
     * @param model the semantic model to analyze
     * @param rules the set of refactoring rules to apply
     * @return an unmodifiable list of candidates, possibly empty
     * @throws NullPointerException  if any argument is null
     * @throws AdapterException      if analysis fails
     */
    List<RefactorCandidate> listRefactorCandidates(SemanticModel model, RefactorRuleSet rules);

    /**
     * Applies a single refactoring candidate to the source tree and produces
     * a patch result containing the unified diff and AST hashes.
     *
     * @param candidate  the refactoring to apply
     * @param sourceRoot the root directory of the source tree
     * @return the result of applying the refactoring
     * @throws NullPointerException  if any argument is null
     * @throws AdapterException      if the refactoring cannot be applied
     */
    PatchResult applyRefactor(RefactorCandidate candidate, Path sourceRoot);

    /**
     * Runs multi-layer verification on a patch result.
     *
     * @param patch      the patch to verify
     * @param sourceRoot the root directory of the source tree (post-patch)
     * @param config     verification configuration
     * @return the verification result with verdict
     * @throws NullPointerException  if any argument is null
     * @throws AdapterException      if verification fails unexpectedly
     */
    VerificationResult verifyPatch(PatchResult patch, Path sourceRoot, VerificationConfig config);

    // ── Configuration types ──────────────────────────────────────────────

    /**
     * Configuration for the language adapter's parse and analysis behavior.
     *
     * @param resolveBindings  whether to resolve type bindings (slower but richer model)
     * @param includeTests     whether to include test source directories
     * @param sourceEncoding   character encoding of source files (default: UTF-8)
     * @param classpathEntries additional classpath entries for type resolution
     * @param sourcepathEntries additional sourcepath entries for cross-project resolution
     * @param excludePatterns  glob patterns for files/directories to exclude
     * @param properties       additional adapter-specific key-value properties
     */
    record LanguageAdapterConfig(
            boolean resolveBindings,
            boolean includeTests,
            String sourceEncoding,
            List<String> classpathEntries,
            List<String> sourcepathEntries,
            List<String> excludePatterns,
            Map<String, String> properties
    ) {
        public LanguageAdapterConfig {
            sourceEncoding = sourceEncoding != null ? sourceEncoding : "UTF-8";
            classpathEntries = classpathEntries != null ? List.copyOf(classpathEntries) : List.of();
            sourcepathEntries = sourcepathEntries != null ? List.copyOf(sourcepathEntries) : List.of();
            excludePatterns = excludePatterns != null ? List.copyOf(excludePatterns) : List.of();
            properties = properties != null ? Map.copyOf(properties) : Map.of();
        }

        /**
         * Returns a default configuration with bindings resolved, tests excluded,
         * UTF-8 encoding, and no additional classpath/sourcepath.
         *
         * @return default configuration
         */
        public static LanguageAdapterConfig defaults() {
            return new LanguageAdapterConfig(
                    true, false, "UTF-8",
                    List.of(), List.of(), List.of(), Map.of());
        }

        /**
         * Returns a configuration for full analysis including tests.
         *
         * @return full analysis configuration
         */
        public static LanguageAdapterConfig fullAnalysis() {
            return new LanguageAdapterConfig(
                    true, true, "UTF-8",
                    List.of(), List.of(), List.of(), Map.of());
        }
    }

    /**
     * Configuration for the verification pipeline.
     *
     * @param runCompilation         whether to attempt compilation of the patched code
     * @param runTests               whether to execute tests after patching
     * @param compareAstStructure    whether to perform AST structural comparison
     * @param compareBytecode        whether to compare bytecode descriptors
     * @param checkApiSurface        whether to verify API surface compatibility
     * @param runGoldenMaster        whether to compare against golden master outputs
     * @param goldenMasterPath       path to golden master data (required if runGoldenMaster is true)
     * @param testCommand            custom test command (e.g., "mvn test -pl module")
     * @param timeoutSeconds         maximum time for the entire verification pipeline
     * @param properties             additional verification-specific properties
     */
    record VerificationConfig(
            boolean runCompilation,
            boolean runTests,
            boolean compareAstStructure,
            boolean compareBytecode,
            boolean checkApiSurface,
            boolean runGoldenMaster,
            String goldenMasterPath,
            String testCommand,
            int timeoutSeconds,
            Map<String, String> properties
    ) {
        public VerificationConfig {
            if (timeoutSeconds <= 0) timeoutSeconds = 300;
            properties = properties != null ? Map.copyOf(properties) : Map.of();
        }

        /**
         * Returns a default verification config that runs all checks except
         * golden master comparison.
         *
         * @return default verification configuration
         */
        public static VerificationConfig defaults() {
            return new VerificationConfig(
                    true, true, true, true, true,
                    false, null, null, 300, Map.of());
        }

        /**
         * Returns a quick verification config that only checks compilation
         * and AST structure.
         *
         * @return quick verification configuration
         */
        public static VerificationConfig quick() {
            return new VerificationConfig(
                    true, false, true, false, false,
                    false, null, null, 60, Map.of());
        }
    }

    // ── Refactoring rule set ─────────────────────────────────────────────

    /**
     * A set of refactoring rules that can identify transformation candidates
     * in a semantic model.
     *
     * <p>Implementations should be stateless and thread-safe. Each rule
     * examines the semantic model and produces zero or more
     * {@link RefactorCandidate} instances.</p>
     */
    interface RefactorRuleSet {

        /**
         * Returns all rule identifiers in this set.
         *
         * @return unmodifiable list of rule IDs
         */
        List<String> ruleIds();

        /**
         * Evaluates all rules in this set against the given semantic model.
         *
         * @param model the semantic model to analyze
         * @return list of refactoring candidates found
         */
        List<RefactorCandidate> evaluate(SemanticModel model);

        /**
         * Returns an empty rule set that produces no candidates.
         *
         * @return an empty rule set
         */
        static RefactorRuleSet empty() {
            return new RefactorRuleSet() {
                @Override
                public List<String> ruleIds() { return List.of(); }
                @Override
                public List<RefactorCandidate> evaluate(SemanticModel model) { return List.of(); }
            };
        }
    }

    // ── Adapter exception ────────────────────────────────────────────────

    /**
     * Checked exception thrown by adapter operations when a recoverable
     * failure occurs during parsing, refactoring, or verification.
     */
    class AdapterException extends RuntimeException {
        private final String phase;

        /**
         * @param phase   the pipeline phase that failed (e.g., "parse", "refactor", "verify")
         * @param message human-readable error description
         */
        public AdapterException(String phase, String message) {
            super("[" + phase + "] " + message);
            this.phase = Objects.requireNonNull(phase);
        }

        /**
         * @param phase   the pipeline phase that failed
         * @param message human-readable error description
         * @param cause   the underlying cause
         */
        public AdapterException(String phase, String message, Throwable cause) {
            super("[" + phase + "] " + message, cause);
            this.phase = Objects.requireNonNull(phase);
        }

        /** @return the pipeline phase that failed */
        public String phase() { return phase; }
    }
}
