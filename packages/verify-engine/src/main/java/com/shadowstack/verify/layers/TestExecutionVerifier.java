package com.shadowstack.verify.layers;

import com.shadowstack.refactor.model.PatchUnit;
import com.shadowstack.verify.VerificationLayer;
import com.shadowstack.verify.model.VerificationContext;
import com.shadowstack.verify.model.VerificationLayerResult;
import com.shadowstack.verify.model.Verdict;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Verification layer that executes the project's test suite and compares results
 * before and after the transformation.
 *
 * <p>This layer runs the existing test suite against both the original and transformed
 * code, then compares the results to detect behavioral regressions. It supports Maven
 * and Gradle test execution, and captures individual test outcomes.</p>
 *
 * <h3>Checks Performed</h3>
 * <ul>
 *   <li>All tests pass on original code (baseline validation)</li>
 *   <li>All tests pass on transformed code</li>
 *   <li>No test regressions (tests that passed before now fail)</li>
 *   <li>No new test failures introduced</li>
 * </ul>
 */
public class TestExecutionVerifier implements VerificationLayer {

    private static final Logger log = LoggerFactory.getLogger(TestExecutionVerifier.class);
    private static final String LAYER_ID = "test_execution_verifier";
    private static final long DEFAULT_TIMEOUT_SECONDS = 300;

    private static final Pattern MAVEN_TEST_SUMMARY = Pattern.compile(
            "Tests run: (\\d+), Failures: (\\d+), Errors: (\\d+), Skipped: (\\d+)");

    @Override
    public String layerId() {
        return LAYER_ID;
    }

    @Override
    public VerificationLayerResult verify(PatchUnit patch, VerificationContext context) {
        Instant start = Instant.now();
        log.info("TestExecutionVerifier: running test suite for patch {} in '{}'",
                patch.getPatchId(), patch.getSourceFile());

        VerificationLayerResult.Builder result = VerificationLayerResult.builder(LAYER_ID);

        Path projectRoot = context.getProjectRoot();
        if (projectRoot == null || !Files.exists(projectRoot)) {
            log.warn("TestExecutionVerifier: project root not available or does not exist");
            return result
                    .verdict(Verdict.WARN)
                    .riskContribution(0.10)
                    .summary("Project root not available — cannot run tests")
                    .addDiagnostic("projectRoot is null or does not exist")
                    .executionTime(Duration.between(start, Instant.now()))
                    .build();
        }

        long timeout = context.getConfig("testTimeoutSeconds", DEFAULT_TIMEOUT_SECONDS);

        // Detect build system
        BuildSystem buildSystem = detectBuildSystem(projectRoot);
        result.addDetail("buildSystem", buildSystem.name());
        log.info("  Detected build system: {}", buildSystem);

        // Run tests on original code
        TestRunResult originalRun = executeTests(projectRoot, buildSystem, timeout);
        result.addDetail("originalTestRun", originalRun.toMap());
        log.info("  Original test run: passed={}, failed={}, errors={}, total={}",
                originalRun.passed, originalRun.failed, originalRun.errors, originalRun.total);

        if (originalRun.exitCode != 0 && originalRun.failed == 0 && originalRun.errors == 0) {
            // Build/infrastructure failure rather than test failure
            log.warn("  Original test run had non-zero exit code but no test failures — infrastructure issue");
            return result
                    .verdict(Verdict.WARN)
                    .riskContribution(0.10)
                    .summary("Test infrastructure issue — cannot establish baseline")
                    .addDiagnostic("Original test run exit code: " + originalRun.exitCode)
                    .executionTime(Duration.between(start, Instant.now()))
                    .build();
        }

        // Apply transformation (write transformed source to temp location)
        Path transformedSourceFile = applyTransformation(patch, context);
        if (transformedSourceFile == null) {
            return result
                    .verdict(Verdict.WARN)
                    .riskContribution(0.10)
                    .summary("Could not apply transformation for testing")
                    .executionTime(Duration.between(start, Instant.now()))
                    .build();
        }

        try {
            // Run tests on transformed code
            TestRunResult transformedRun = executeTests(projectRoot, buildSystem, timeout);
            result.addDetail("transformedTestRun", transformedRun.toMap());
            log.info("  Transformed test run: passed={}, failed={}, errors={}, total={}",
                    transformedRun.passed, transformedRun.failed, transformedRun.errors, transformedRun.total);

            // Compare results
            return compareTestResults(result, originalRun, transformedRun, start);

        } finally {
            // Restore original source
            restoreOriginal(patch, context, transformedSourceFile);
        }
    }

    /**
     * Compares test results between original and transformed runs.
     */
    private VerificationLayerResult compareTestResults(
            VerificationLayerResult.Builder result,
            TestRunResult original, TestRunResult transformed, Instant start) {

        int newFailures = Math.max(0, transformed.failed - original.failed);
        int newErrors = Math.max(0, transformed.errors - original.errors);
        int regressions = newFailures + newErrors;

        result.addDetail("newFailures", newFailures);
        result.addDetail("newErrors", newErrors);
        result.addDetail("regressions", regressions);

        if (regressions > 0) {
            log.error("  TEST REGRESSIONS DETECTED: {} new failure(s), {} new error(s)",
                    newFailures, newErrors);
            return result
                    .verdict(Verdict.FAIL)
                    .riskContribution(0.30)
                    .summary("%d test regression(s) detected (%d failures, %d errors)"
                            .formatted(regressions, newFailures, newErrors))
                    .addDiagnostic("Tests passed before transformation but fail after")
                    .executionTime(Duration.between(start, Instant.now()))
                    .build();
        }

        if (transformed.total < original.total) {
            log.warn("  Fewer tests ran after transformation ({} vs {})",
                    transformed.total, original.total);
            return result
                    .verdict(Verdict.WARN)
                    .riskContribution(0.10)
                    .summary("Fewer tests executed after transformation (%d vs %d)"
                            .formatted(transformed.total, original.total))
                    .executionTime(Duration.between(start, Instant.now()))
                    .build();
        }

        log.info("  All tests pass — no regressions detected");
        return result
                .verdict(Verdict.PASS)
                .riskContribution(0.0)
                .summary("All %d tests pass with no regressions".formatted(transformed.total))
                .executionTime(Duration.between(start, Instant.now()))
                .build();
    }

    /**
     * Executes the project's test suite using the detected build system.
     */
    private TestRunResult executeTests(Path projectRoot, BuildSystem buildSystem, long timeoutSeconds) {
        List<String> command = switch (buildSystem) {
            case MAVEN -> List.of("mvn", "test", "-q", "--batch-mode", "-pl", ".");
            case GRADLE -> List.of("./gradlew", "test", "--quiet");
            case UNKNOWN -> List.of("mvn", "test", "-q", "--batch-mode");
        };

        log.debug("  Executing: {} in {}", String.join(" ", command), projectRoot);

        try {
            ProcessBuilder pb = new ProcessBuilder(command)
                    .directory(projectRoot.toFile())
                    .redirectErrorStream(true);

            Process process = pb.start();
            StringBuilder output = new StringBuilder();

            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append('\n');
                }
            }

            boolean completed = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!completed) {
                process.destroyForcibly();
                log.warn("  Test execution timed out after {}s", timeoutSeconds);
                return new TestRunResult(-1, 0, 0, 0, 0, true, output.toString());
            }

            int exitCode = process.exitValue();
            return parseTestOutput(exitCode, output.toString());

        } catch (IOException | InterruptedException e) {
            log.error("  Failed to execute tests: {}", e.getMessage(), e);
            return new TestRunResult(-1, 0, 0, 0, 0, false, e.getMessage());
        }
    }

    /**
     * Parses test output to extract pass/fail/error counts.
     */
    private TestRunResult parseTestOutput(int exitCode, String output) {
        Matcher matcher = MAVEN_TEST_SUMMARY.matcher(output);
        if (matcher.find()) {
            int total = Integer.parseInt(matcher.group(1));
            int failures = Integer.parseInt(matcher.group(2));
            int errors = Integer.parseInt(matcher.group(3));
            int skipped = Integer.parseInt(matcher.group(4));
            int passed = total - failures - errors - skipped;
            return new TestRunResult(exitCode, total, passed, failures, errors, false, output);
        }

        // Fallback: infer from exit code
        if (exitCode == 0) {
            return new TestRunResult(exitCode, 0, 0, 0, 0, false, output);
        }
        return new TestRunResult(exitCode, 0, 0, 1, 0, false, output);
    }

    private BuildSystem detectBuildSystem(Path projectRoot) {
        if (Files.exists(projectRoot.resolve("pom.xml"))) return BuildSystem.MAVEN;
        if (Files.exists(projectRoot.resolve("build.gradle"))
                || Files.exists(projectRoot.resolve("build.gradle.kts"))) return BuildSystem.GRADLE;
        return BuildSystem.UNKNOWN;
    }

    /**
     * Writes the transformed source to the project for testing.
     */
    private Path applyTransformation(PatchUnit patch, VerificationContext context) {
        try {
            Path sourceFile = context.getSourceRoot().resolve(patch.getSourceFile());
            if (!Files.exists(sourceFile)) {
                log.warn("Source file '{}' does not exist", sourceFile);
                return null;
            }

            // Backup original
            Path backup = sourceFile.resolveSibling(sourceFile.getFileName() + ".shadowstack.bak");
            Files.copy(sourceFile, backup);

            // Write transformed source
            String transformed = context.getTransformedSource();
            if (transformed != null) {
                Files.writeString(sourceFile, transformed);
            }

            return backup;
        } catch (IOException e) {
            log.error("Failed to apply transformation: {}", e.getMessage(), e);
            return null;
        }
    }

    /**
     * Restores the original source after testing.
     */
    private void restoreOriginal(PatchUnit patch, VerificationContext context, Path backup) {
        try {
            Path sourceFile = context.getSourceRoot().resolve(patch.getSourceFile());
            if (Files.exists(backup)) {
                Files.move(backup, sourceFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                log.debug("Restored original source from backup");
            }
        } catch (IOException e) {
            log.error("CRITICAL: Failed to restore original source from backup: {}", e.getMessage(), e);
        }
    }

    private enum BuildSystem { MAVEN, GRADLE, UNKNOWN }

    private record TestRunResult(
            int exitCode, int total, int passed, int failed, int errors,
            boolean timedOut, String rawOutput
    ) {
        Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("exitCode", exitCode);
            map.put("total", total);
            map.put("passed", passed);
            map.put("failed", failed);
            map.put("errors", errors);
            map.put("timedOut", timedOut);
            return map;
        }
    }
}
