package com.shadowstack.analysis;

import com.shadowstack.analysis.BaselineSnapshot.*;
import com.shadowstack.analysis.ComplexityMetrics.ComplexityReport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Classifies modules and classes by refactoring risk level.
 *
 * <p>The {@code RiskClassifier} combines multiple risk signals into a
 * single deterministic score and classification. Risk levels determine
 * the degree of verification and review required before applying
 * refactoring transformations.</p>
 *
 * <h3>Risk levels</h3>
 * <ul>
 *   <li>{@link RiskLevel#SAFE} — score [0.0, 0.3): minimal verification needed.</li>
 *   <li>{@link RiskLevel#MODERATE_RISK} — score [0.3, 0.6): standard review + verification.</li>
 *   <li>{@link RiskLevel#HIGH_RISK} — score [0.6, 1.0]: multi-layer verification + sign-off.</li>
 * </ul>
 *
 * <h3>Scoring formula</h3>
 * <p>The composite risk score is a weighted sum of normalized signal scores:</p>
 * <pre>
 *   score = w_complexity * complexity_score
 *         + w_concurrency * concurrency_score
 *         + w_reflection * reflection_score
 *         + w_io * io_score
 *         + w_coverage * (1.0 - test_coverage_score)
 * </pre>
 * <p>All weights sum to 1.0. Each signal score is clamped to [0.0, 1.0].</p>
 *
 * <p>Thread-safe and stateless — all configuration is via immutable {@link WeightConfig}.</p>
 *
 * @see BaselineSnapshot
 * @see ComplexityMetrics
 */
public final class RiskClassifier {

    private static final Logger LOG = LoggerFactory.getLogger(RiskClassifier.class);

    private RiskClassifier() {
        throw new AssertionError("Utility class — do not instantiate");
    }

    // ─── Public API ──────────────────────────────────────────────────

    /**
     * Classifies risk for a single class within the project.
     *
     * @param className        fully-qualified class name
     * @param complexityReport complexity metrics for the class's source file
     * @param snapshot         the baseline snapshot (for reflection, concurrency, IO data)
     * @return an immutable {@link RiskAssessment}
     */
    public static RiskAssessment classify(
            String className,
            ComplexityReport complexityReport,
            BaselineSnapshot snapshot) {

        return classify(className, complexityReport, snapshot, WeightConfig.DEFAULT);
    }

    /**
     * Classifies risk with custom weight configuration.
     *
     * @param className        fully-qualified class name
     * @param complexityReport complexity metrics for the class's source file
     * @param snapshot         the baseline snapshot
     * @param weights          custom weight configuration
     * @return an immutable {@link RiskAssessment}
     */
    public static RiskAssessment classify(
            String className,
            ComplexityReport complexityReport,
            BaselineSnapshot snapshot,
            WeightConfig weights) {

        Objects.requireNonNull(className, "className must not be null");
        Objects.requireNonNull(complexityReport, "complexityReport must not be null");
        Objects.requireNonNull(snapshot, "snapshot must not be null");
        Objects.requireNonNull(weights, "weights must not be null");

        LOG.debug("Classifying risk for class: {}", className);

        // Compute individual signal scores
        SignalScore complexityScore = computeComplexityScore(className, complexityReport);
        SignalScore concurrencyScore = computeConcurrencyScore(className, snapshot);
        SignalScore reflectionScore = computeReflectionScore(className, snapshot);
        SignalScore ioScore = computeIOScore(className, snapshot);
        SignalScore coverageScore = computeCoverageScore(className, snapshot);

        // Compute weighted composite score
        double compositeScore =
                weights.complexityWeight() * complexityScore.score() +
                weights.concurrencyWeight() * concurrencyScore.score() +
                weights.reflectionWeight() * reflectionScore.score() +
                weights.ioWeight() * ioScore.score() +
                weights.coverageWeight() * coverageScore.score();

        // Clamp to [0.0, 1.0]
        compositeScore = Math.max(0.0, Math.min(1.0, compositeScore));

        // Determine risk level
        RiskLevel level = RiskLevel.fromScore(compositeScore);

        List<SignalScore> signals = List.of(
                complexityScore, concurrencyScore, reflectionScore, ioScore, coverageScore);

        RiskAssessment result = new RiskAssessment(
                className, level, compositeScore, signals, weights);

        LOG.info("Risk for {}: {} (score={})", className, level,
                String.format("%.3f", compositeScore));

        return result;
    }

    /**
     * Classifies risk for all classes discovered in the baseline snapshot.
     *
     * @param snapshot         the baseline snapshot
     * @param complexityByFile complexity reports keyed by file path
     * @return map of class name → risk assessment
     */
    public static Map<String, RiskAssessment> classifyAll(
            BaselineSnapshot snapshot,
            Map<String, ComplexityReport> complexityByFile) {

        Objects.requireNonNull(snapshot, "snapshot must not be null");
        Objects.requireNonNull(complexityByFile, "complexityByFile must not be null");

        Map<String, RiskAssessment> results = new LinkedHashMap<>();

        // Derive unique class names from API signatures
        Set<String> classNames = new LinkedHashSet<>();
        for (MethodSignature sig : snapshot.apiSignatures()) {
            classNames.add(sig.qualifiedClassName());
        }

        for (String className : classNames) {
            // Find the complexity report that contains methods for this class
            ComplexityReport report = findReportForClass(className, complexityByFile);
            if (report != null) {
                results.put(className, classify(className, report, snapshot));
            } else {
                // No complexity data — classify with empty report
                ComplexityReport empty = new ComplexityReport(
                        "<unknown>", Map.of(), Map.of(), Map.of(), Map.of(), 0, 0, 0);
                results.put(className, classify(className, empty, snapshot));
            }
        }

        return Collections.unmodifiableMap(results);
    }

    // ─── Signal computation ──────────────────────────────────────────

    /**
     * Computes the complexity risk signal.
     *
     * <p>Uses a combination of max cyclomatic complexity, average complexity,
     * max nesting depth, and method count. The score rises sharply above
     * industry-standard thresholds (cyclomatic > 10, nesting > 4).</p>
     */
    private static SignalScore computeComplexityScore(
            String className, ComplexityReport report) {

        double maxCC = report.maxCyclomaticComplexity();
        double avgCC = report.averageCyclomaticComplexity();
        double maxNesting = report.maxNestingDepth();
        int methodCount = report.methodCount();

        // Normalize each sub-signal to [0.0, 1.0]
        // Cyclomatic > 20 is very high risk; > 10 is moderate
        double ccScore = sigmoid(maxCC, 10.0, 0.3);

        // Average CC > 8 is concerning
        double avgCCScore = sigmoid(avgCC, 8.0, 0.3);

        // Nesting > 4 is a red flag
        double nestingScore = sigmoid(maxNesting, 4.0, 0.5);

        // Many methods in one class suggests complexity
        double methodCountScore = sigmoid(methodCount, 30.0, 0.2);

        // Weighted combination
        double score = 0.4 * ccScore + 0.25 * avgCCScore + 0.25 * nestingScore + 0.1 * methodCountScore;

        String detail = String.format(
                "maxCC=%.0f, avgCC=%.1f, maxNesting=%.0f, methods=%d",
                maxCC, avgCC, maxNesting, methodCount);

        return new SignalScore(SignalType.COMPLEXITY, score, detail);
    }

    /**
     * Computes the concurrency risk signal.
     *
     * <p>Any concurrency usage increases risk. Multiple different concurrency
     * primitives in the same class raise the score further.</p>
     */
    private static SignalScore computeConcurrencyScore(
            String className, BaselineSnapshot snapshot) {

        long concurrencyCount = snapshot.concurrencyPatterns().stream()
                .filter(p -> p.location().contains(className) || classMatchesLocation(className, p.location()))
                .count();

        // Count distinct concurrency types used
        long distinctTypes = snapshot.concurrencyPatterns().stream()
                .filter(p -> p.location().contains(className) || classMatchesLocation(className, p.location()))
                .map(ConcurrencyPattern::patternType)
                .distinct()
                .count();

        double score;
        if (concurrencyCount == 0) {
            score = 0.0;
        } else if (distinctTypes <= 1) {
            score = 0.3 + sigmoid(concurrencyCount, 5.0, 0.3) * 0.3;
        } else {
            // Multiple distinct concurrency primitives — high risk
            score = 0.5 + sigmoid(distinctTypes, 3.0, 0.5) * 0.5;
        }

        String detail = String.format(
                "patterns=%d, distinctTypes=%d", concurrencyCount, distinctTypes);

        return new SignalScore(SignalType.CONCURRENCY, Math.min(score, 1.0), detail);
    }

    /**
     * Computes the reflection risk signal.
     *
     * <p>Reflection usage is inherently risky — it bypasses compile-time
     * checks and makes automated analysis unreliable.</p>
     */
    private static SignalScore computeReflectionScore(
            String className, BaselineSnapshot snapshot) {

        long reflectionCount = snapshot.reflectionUsages().stream()
                .filter(r -> r.location().contains(className) || classMatchesLocation(className, r.location()))
                .count();

        double score;
        if (reflectionCount == 0) {
            score = 0.0;
        } else if (reflectionCount <= 2) {
            score = 0.4;
        } else if (reflectionCount <= 5) {
            score = 0.7;
        } else {
            score = 0.9;
        }

        String detail = String.format("reflectionUsages=%d", reflectionCount);

        return new SignalScore(SignalType.REFLECTION, score, detail);
    }

    /**
     * Computes the IO risk signal.
     *
     * <p>IO operations are side-effecting and may fail in unpredictable ways.
     * More IO operations increase the risk.</p>
     */
    private static SignalScore computeIOScore(
            String className, BaselineSnapshot snapshot) {

        long ioCount = snapshot.ioBoundaries().stream()
                .filter(io -> io.location().contains(className) || classMatchesLocation(className, io.location()))
                .count();

        long distinctTypes = snapshot.ioBoundaries().stream()
                .filter(io -> io.location().contains(className) || classMatchesLocation(className, io.location()))
                .map(IOBoundary::ioType)
                .distinct()
                .count();

        double score;
        if (ioCount == 0) {
            score = 0.0;
        } else {
            score = 0.2 + sigmoid(ioCount, 5.0, 0.3) * 0.5 + sigmoid(distinctTypes, 3.0, 0.5) * 0.3;
        }

        String detail = String.format("ioBoundaries=%d, distinctTypes=%d", ioCount, distinctTypes);

        return new SignalScore(SignalType.IO, Math.min(score, 1.0), detail);
    }

    /**
     * Computes the test coverage risk signal.
     *
     * <p>Low test coverage means fewer safety nets. If test data is unavailable,
     * assumes worst-case (zero coverage).</p>
     */
    private static SignalScore computeCoverageScore(
            String className, BaselineSnapshot snapshot) {

        TestResults tests = snapshot.testResults();

        double coverageProxy;
        if (tests.totalTests() == 0) {
            // No tests at all — maximum risk from lack of coverage
            coverageProxy = 1.0;
        } else if (tests.allPassed()) {
            // All tests pass — reduce risk inversely with test count
            coverageProxy = Math.max(0.0, 1.0 - sigmoid(tests.totalTests(), 20.0, 0.3));
        } else {
            // Some tests failing — higher risk
            double failRatio = (double) tests.failed() / tests.totalTests();
            coverageProxy = 0.5 + 0.5 * failRatio;
        }

        String detail = String.format(
                "totalTests=%d, passed=%d, failed=%d",
                tests.totalTests(), tests.passed(), tests.failed());

        return new SignalScore(SignalType.TEST_COVERAGE, coverageProxy, detail);
    }

    // ─── Utilities ───────────────────────────────────────────────────

    /**
     * Sigmoid-like function for smooth scoring.
     * Returns a value in [0, 1] that crosses 0.5 at x = midpoint.
     */
    private static double sigmoid(double x, double midpoint, double steepness) {
        return 1.0 / (1.0 + Math.exp(-steepness * (x - midpoint)));
    }

    /**
     * Heuristically checks if a class name matches a file-path-based location.
     */
    private static boolean classMatchesLocation(String className, String location) {
        // Convert class name dots to path separators for matching
        String classAsPath = className.replace('.', '/');
        return location.contains(classAsPath);
    }

    /**
     * Finds the complexity report that covers methods of the given class.
     */
    private static ComplexityReport findReportForClass(
            String className, Map<String, ComplexityReport> reports) {

        String classAsPath = className.replace('.', '/');
        for (var entry : reports.entrySet()) {
            if (entry.getKey().contains(classAsPath)) {
                return entry.getValue();
            }
        }
        // Fallback: check if any method key in any report starts with the class name
        for (ComplexityReport report : reports.values()) {
            for (String methodKey : report.cyclomaticByMethod().keySet()) {
                if (methodKey.startsWith(className) || methodKey.contains(className)) {
                    return report;
                }
            }
        }
        return null;
    }

    // ═════════════════════════════════════════════════════════════════
    //  DTOs and Enums
    // ═════════════════════════════════════════════════════════════════

    /**
     * Risk level classification.
     */
    public enum RiskLevel {
        /** Low risk — minimal verification needed. Score in [0.0, 0.3). */
        SAFE(0.0, 0.3, "Safe for automated refactoring with standard verification"),
        /** Moderate risk — standard review + verification. Score in [0.3, 0.6). */
        MODERATE_RISK(0.3, 0.6, "Requires review and multi-pass verification"),
        /** High risk — multi-layer verification + sign-off. Score in [0.6, 1.0]. */
        HIGH_RISK(0.6, 1.0, "Requires multi-layer verification and manual sign-off");

        private final double lowerBound;
        private final double upperBound;
        private final String description;

        RiskLevel(double lowerBound, double upperBound, String description) {
            this.lowerBound = lowerBound;
            this.upperBound = upperBound;
            this.description = description;
        }

        /** Lower bound (inclusive) of the score range. */
        public double lowerBound() { return lowerBound; }

        /** Upper bound (exclusive, except for HIGH_RISK which is inclusive at 1.0). */
        public double upperBound() { return upperBound; }

        /** Human-readable description. */
        public String description() { return description; }

        /**
         * Determines the risk level for a given composite score.
         *
         * @param score composite risk score in [0.0, 1.0]
         * @return the corresponding risk level
         */
        public static RiskLevel fromScore(double score) {
            if (score < 0.0 || score > 1.0) {
                throw new IllegalArgumentException("Score must be in [0.0, 1.0], got: " + score);
            }
            if (score >= HIGH_RISK.lowerBound) return HIGH_RISK;
            if (score >= MODERATE_RISK.lowerBound) return MODERATE_RISK;
            return SAFE;
        }

        /** Whether this level requires human review. */
        public boolean requiresReview() {
            return this == MODERATE_RISK || this == HIGH_RISK;
        }

        /** Whether this level requires full multi-layer verification. */
        public boolean requiresFullVerification() {
            return this == HIGH_RISK;
        }
    }

    /**
     * Types of risk signals that contribute to the composite score.
     */
    public enum SignalType {
        COMPLEXITY,
        CONCURRENCY,
        REFLECTION,
        IO,
        TEST_COVERAGE
    }

    /**
     * A single normalized risk signal score.
     *
     * @param type   the signal type
     * @param score  normalized score in [0.0, 1.0] (higher = riskier)
     * @param detail human-readable breakdown of what produced this score
     */
    public record SignalScore(
            SignalType type,
            double score,
            String detail
    ) {
        public SignalScore {
            Objects.requireNonNull(type, "type");
            if (score < 0.0 || score > 1.0) {
                throw new IllegalArgumentException("Signal score must be in [0.0, 1.0], got: " + score);
            }
            detail = detail == null ? "" : detail;
        }
    }

    /**
     * Weight configuration for the risk scoring formula.
     *
     * <p>All weights must be non-negative and should sum to 1.0 (they are
     * <em>not</em> normalized — the caller is responsible for ensuring correct
     * proportions).</p>
     *
     * @param complexityWeight  weight for complexity signal
     * @param concurrencyWeight weight for concurrency signal
     * @param reflectionWeight  weight for reflection signal
     * @param ioWeight          weight for IO signal
     * @param coverageWeight    weight for test coverage signal
     */
    public record WeightConfig(
            double complexityWeight,
            double concurrencyWeight,
            double reflectionWeight,
            double ioWeight,
            double coverageWeight
    ) {
        /** Default weights: complexity-heavy, balanced IO/concurrency, moderate coverage. */
        public static final WeightConfig DEFAULT = new WeightConfig(0.30, 0.25, 0.20, 0.15, 0.10);

        public WeightConfig {
            if (complexityWeight < 0 || concurrencyWeight < 0 || reflectionWeight < 0 ||
                    ioWeight < 0 || coverageWeight < 0) {
                throw new IllegalArgumentException("All weights must be non-negative");
            }
        }

        /** Returns the sum of all weights. */
        public double totalWeight() {
            return complexityWeight + concurrencyWeight + reflectionWeight + ioWeight + coverageWeight;
        }
    }

    /**
     * Complete risk assessment for a single class/module.
     *
     * @param className      fully-qualified class name
     * @param riskLevel      the determined risk level
     * @param compositeScore the weighted composite score in [0.0, 1.0]
     * @param signalScores   individual signal scores with breakdown
     * @param weights        the weight configuration used
     */
    public record RiskAssessment(
            String className,
            RiskLevel riskLevel,
            double compositeScore,
            List<SignalScore> signalScores,
            WeightConfig weights
    ) {
        public RiskAssessment {
            Objects.requireNonNull(className, "className");
            Objects.requireNonNull(riskLevel, "riskLevel");
            Objects.requireNonNull(weights, "weights");
            signalScores = signalScores == null ? List.of() : List.copyOf(signalScores);
        }

        /** Whether this class requires human review before refactoring. */
        public boolean requiresReview() {
            return riskLevel.requiresReview();
        }

        /** Whether this class requires full multi-layer verification. */
        public boolean requiresFullVerification() {
            return riskLevel.requiresFullVerification();
        }

        /** Returns the signal score for a specific type. */
        public Optional<SignalScore> signalOfType(SignalType type) {
            return signalScores.stream()
                    .filter(s -> s.type() == type)
                    .findFirst();
        }

        /** Returns a human-readable summary. */
        public String summary() {
            StringBuilder sb = new StringBuilder();
            sb.append(className).append(" → ").append(riskLevel.name());
            sb.append(String.format(" (score=%.3f)%n", compositeScore));
            for (SignalScore signal : signalScores) {
                sb.append(String.format("  %-15s: %.3f  [%s]%n",
                        signal.type(), signal.score(), signal.detail()));
            }
            return sb.toString();
        }
    }
}
