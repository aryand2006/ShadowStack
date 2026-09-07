package com.shadowstack.analysis;

import com.shadowstack.analysis.ComplexityMetrics.ComplexityReport;
import org.eclipse.jdt.core.dom.CompilationUnit;

import java.nio.file.Path;
import java.util.Map;

/**
 * Builds a context prior from {@link RiskClassifier} signals (complexity-first when
 * baseline concurrency/reflection/IO data is unavailable).
 */
public final class ContextPriorEstimator {

    private ContextPriorEstimator() {}

    /**
     * Estimate context risk for a Java compilation unit using complexity metrics
     * and an empty baseline (concurrency/reflection/IO default to zero).
     */
    public static double fromCompilationUnit(CompilationUnit cu, Path filePath) {
        if (cu == null || filePath == null) {
            return 0.45;
        }
        ComplexityReport report = ComplexityMetrics.analyze(cu, filePath);
        return fromComplexityReport(report, filePath.toString());
    }

    public static double fromComplexityReport(ComplexityReport report, String classOrFileHint) {
        if (report == null) {
            return 0.45;
        }
        String className = classOrFileHint != null && !classOrFileHint.isBlank()
                ? classOrFileHint.replace('\\', '/').replace(".java", "").replace('/', '.')
                : "Unknown";
        BaselineSnapshot empty = BaselineSnapshot.builder()
                .compileSuccess(true)
                .sourceRoot("")
                .build();
        RiskClassifier.RiskAssessment assessment = RiskClassifier.classify(
                className,
                report,
                empty,
                // No baseline tests → don't treat missing coverage as max risk.
                new RiskClassifier.WeightConfig(0.45, 0.20, 0.20, 0.15, 0.0));
        return RiskPosterior.clamp(assessment.compositeScore());
    }

    /**
     * Lookup a previously computed file→context score, else default MEDIUM-ish.
     */
    public static double lookup(Map<String, Double> byFile, String filePath, double fallback) {
        if (byFile == null || filePath == null) {
            return fallback;
        }
        Double hit = byFile.get(filePath.replace('\\', '/'));
        return hit != null ? RiskPosterior.clamp(hit) : fallback;
    }
}
