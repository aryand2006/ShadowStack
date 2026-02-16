package com.shadowstack.verify.layers;

import com.shadowstack.refactor.model.PatchUnit;
import com.shadowstack.verify.VerificationLayer;
import com.shadowstack.verify.model.VerificationContext;
import com.shadowstack.verify.model.VerificationLayerResult;
import com.shadowstack.verify.model.Verdict;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * Verification layer that performs golden master (snapshot) comparison of program outputs.
 *
 * <p>Golden master testing captures the output of the program under known inputs before
 * the transformation, then verifies that the transformed code produces identical outputs.
 * This is especially valuable for characterization testing when formal specifications
 * are unavailable.</p>
 *
 * <h3>Process</h3>
 * <ol>
 *   <li>Load golden master snapshots from the verification context</li>
 *   <li>Execute the transformed code with the same inputs</li>
 *   <li>Compare outputs byte-for-byte (and via hash for large outputs)</li>
 *   <li>Report any differences with detailed diff information</li>
 * </ol>
 */
public class GoldenMasterVerifier implements VerificationLayer {

    private static final Logger log = LoggerFactory.getLogger(GoldenMasterVerifier.class);
    private static final String LAYER_ID = "golden_master_verifier";

    @Override
    public String layerId() {
        return LAYER_ID;
    }

    @Override
    public VerificationLayerResult verify(PatchUnit patch, VerificationContext context) {
        Instant start = Instant.now();
        log.info("GoldenMasterVerifier: comparing golden master snapshots for patch {} in '{}'",
                patch.getPatchId(), patch.getSourceFile());

        VerificationLayerResult.Builder result = VerificationLayerResult.builder(LAYER_ID);

        Map<String, String> goldenMasters = context.getGoldenMasterSnapshots();
        if (goldenMasters == null || goldenMasters.isEmpty()) {
            log.info("  No golden master snapshots available — skipping");
            return result
                    .verdict(Verdict.WARN)
                    .riskContribution(0.05)
                    .summary("No golden master snapshots available for comparison")
                    .addDiagnostic("Consider generating characterization tests for better coverage")
                    .executionTime(Duration.between(start, Instant.now()))
                    .build();
        }

        int totalSnapshots = goldenMasters.size();
        int matchCount = 0;
        int mismatchCount = 0;
        List<SnapshotComparison> comparisons = new ArrayList<>();

        for (Map.Entry<String, String> entry : goldenMasters.entrySet()) {
            String snapshotId = entry.getKey();
            String expectedOutput = entry.getValue();

            log.debug("  Comparing snapshot '{}' ({} bytes)", snapshotId, expectedOutput.length());

            // Get the actual output for this snapshot
            // In production, this would execute the code and capture output.
            // Here we look for the actual output in the context configuration.
            String actualOutputKey = "goldenMaster.actual." + snapshotId;
            String actualOutput = context.getConfig(actualOutputKey, null);

            if (actualOutput == null) {
                log.debug("  No actual output for snapshot '{}' — attempting file-based comparison",
                        snapshotId);
                actualOutput = loadActualOutput(snapshotId, context);
            }

            if (actualOutput == null) {
                comparisons.add(new SnapshotComparison(snapshotId, ComparisonResult.MISSING,
                        "No actual output available"));
                continue;
            }

            // Compare outputs
            if (expectedOutput.equals(actualOutput)) {
                matchCount++;
                comparisons.add(new SnapshotComparison(snapshotId, ComparisonResult.MATCH,
                        "Exact match (%d bytes)".formatted(expectedOutput.length())));
                log.debug("  Snapshot '{}': exact match", snapshotId);
            } else {
                // Check hash comparison for binary or large outputs
                String expectedHash = sha256(expectedOutput);
                String actualHash = sha256(actualOutput);

                if (expectedHash.equals(actualHash)) {
                    matchCount++;
                    comparisons.add(new SnapshotComparison(snapshotId, ComparisonResult.MATCH,
                            "Hash match (SHA-256: %s)".formatted(expectedHash.substring(0, 16))));
                } else {
                    mismatchCount++;

                    // Compute diff summary
                    String diffSummary = computeDiffSummary(expectedOutput, actualOutput);
                    comparisons.add(new SnapshotComparison(snapshotId, ComparisonResult.MISMATCH,
                            diffSummary));
                    result.addDiagnostic("Snapshot '%s' MISMATCH: %s".formatted(snapshotId, diffSummary));
                    log.warn("  Snapshot '{}': MISMATCH — {}", snapshotId, diffSummary);
                }
            }
        }

        result.addDetail("totalSnapshots", totalSnapshots);
        result.addDetail("matchCount", matchCount);
        result.addDetail("mismatchCount", mismatchCount);
        result.addDetail("missingCount", totalSnapshots - matchCount - mismatchCount);
        result.addDetail("comparisons", comparisons.stream()
                .map(c -> Map.of("id", c.snapshotId, "result", c.result.name(), "detail", c.detail))
                .toList());

        log.info("  Golden master results: {}/{} match, {} mismatch, {} missing",
                matchCount, totalSnapshots, mismatchCount,
                totalSnapshots - matchCount - mismatchCount);

        if (mismatchCount > 0) {
            return result
                    .verdict(Verdict.FAIL)
                    .riskContribution(0.20)
                    .summary("%d/%d golden master snapshot(s) MISMATCH".formatted(mismatchCount, totalSnapshots))
                    .executionTime(Duration.between(start, Instant.now()))
                    .build();
        }

        int missingCount = totalSnapshots - matchCount;
        if (missingCount > 0) {
            return result
                    .verdict(Verdict.WARN)
                    .riskContribution(0.05)
                    .summary("%d/%d golden master matches, %d missing actual outputs"
                            .formatted(matchCount, totalSnapshots, missingCount))
                    .executionTime(Duration.between(start, Instant.now()))
                    .build();
        }

        return result
                .verdict(Verdict.PASS)
                .riskContribution(0.0)
                .summary("All %d golden master snapshots match".formatted(totalSnapshots))
                .executionTime(Duration.between(start, Instant.now()))
                .build();
    }

    /**
     * Attempts to load actual output from a file in the project.
     */
    private String loadActualOutput(String snapshotId, VerificationContext context) {
        if (context.getProjectRoot() == null) return null;

        Path snapshotDir = context.getProjectRoot().resolve(".shadowstack/golden-masters");
        Path actualFile = snapshotDir.resolve(snapshotId + ".actual");

        if (Files.exists(actualFile)) {
            try {
                return Files.readString(actualFile, StandardCharsets.UTF_8);
            } catch (IOException e) {
                log.warn("Failed to read actual output file '{}': {}", actualFile, e.getMessage());
            }
        }
        return null;
    }

    /**
     * Computes a human-readable diff summary between expected and actual outputs.
     */
    private String computeDiffSummary(String expected, String actual) {
        String[] expectedLines = expected.split("\n", -1);
        String[] actualLines = actual.split("\n", -1);

        int minLen = Math.min(expectedLines.length, actualLines.length);
        int firstDiffLine = -1;

        for (int i = 0; i < minLen; i++) {
            if (!expectedLines[i].equals(actualLines[i])) {
                firstDiffLine = i + 1;
                break;
            }
        }

        if (firstDiffLine == -1) {
            if (expectedLines.length != actualLines.length) {
                return "Line count differs: expected %d, actual %d"
                        .formatted(expectedLines.length, actualLines.length);
            }
            return "Outputs differ but no specific line identified";
        }

        int totalDiffLines = 0;
        for (int i = 0; i < minLen; i++) {
            if (!expectedLines[i].equals(actualLines[i])) {
                totalDiffLines++;
            }
        }
        totalDiffLines += Math.abs(expectedLines.length - actualLines.length);

        return "First difference at line %d; %d line(s) differ (expected %d lines, actual %d lines)"
                .formatted(firstDiffLine, totalDiffLines, expectedLines.length, actualLines.length);
    }

    /**
     * Computes SHA-256 hash of a string.
     */
    private String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            return "hash-unavailable";
        }
    }

    private enum ComparisonResult { MATCH, MISMATCH, MISSING }

    private record SnapshotComparison(String snapshotId, ComparisonResult result, String detail) {}
}
