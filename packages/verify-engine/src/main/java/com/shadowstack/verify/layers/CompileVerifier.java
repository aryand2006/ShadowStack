package com.shadowstack.verify.layers;

import com.shadowstack.refactor.model.PatchUnit;
import com.shadowstack.verify.VerificationLayer;
import com.shadowstack.verify.model.VerificationContext;
import com.shadowstack.verify.model.VerificationLayerResult;
import com.shadowstack.verify.model.Verdict;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.tools.*;
import java.io.*;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Verification layer that compiles both the original and transformed source code
 * and compares compilation outcomes.
 *
 * <p>This layer ensures that the refactored code compiles without errors. It also
 * detects new warnings introduced by the transformation, which may indicate subtle
 * behavioral changes.</p>
 *
 * <h3>Checks Performed</h3>
 * <ul>
 *   <li>Original source compiles successfully (baseline validation)</li>
 *   <li>Transformed source compiles successfully</li>
 *   <li>No new compilation errors introduced</li>
 *   <li>New warnings are flagged but don't cause failure</li>
 * </ul>
 */
public class CompileVerifier implements VerificationLayer {

    private static final Logger log = LoggerFactory.getLogger(CompileVerifier.class);
    private static final String LAYER_ID = "compile_verifier";

    @Override
    public String layerId() {
        return LAYER_ID;
    }

    @Override
    public VerificationLayerResult verify(PatchUnit patch, VerificationContext context) {
        Instant start = Instant.now();
        log.info("CompileVerifier: checking compilation for patch {} in '{}'",
                patch.getPatchId(), patch.getSourceFile());

        VerificationLayerResult.Builder result = VerificationLayerResult.builder(LAYER_ID);

        String originalSource = context.getOriginalSource();
        String transformedSource = context.getTransformedSource();

        if (originalSource == null || transformedSource == null) {
            log.warn("CompileVerifier: source code not available in context");
            return result
                    .verdict(Verdict.WARN)
                    .riskContribution(0.10)
                    .summary("Source code not available for compilation check")
                    .addDiagnostic("originalSource or transformedSource is null in VerificationContext")
                    .executionTime(Duration.between(start, Instant.now()))
                    .build();
        }

        // Compile original source
        CompilationResult originalResult = compileSource(
                patch.getSourceFile(), originalSource, context.getClasspath());
        result.addDetail("originalCompileSuccess", originalResult.success);
        result.addDetail("originalErrors", originalResult.errors.size());
        result.addDetail("originalWarnings", originalResult.warnings.size());
        log.info("  Original compilation: success={}, errors={}, warnings={}",
                originalResult.success, originalResult.errors.size(), originalResult.warnings.size());

        // If original doesn't compile, we have a baseline problem
        if (!originalResult.success) {
            log.warn("  Original source does not compile — baseline invalid");
            for (String err : originalResult.errors) {
                result.addDiagnostic("Original compile error: " + err);
            }
            return result
                    .verdict(Verdict.WARN)
                    .riskContribution(0.05)
                    .summary("Original source has compilation errors — baseline invalid")
                    .executionTime(Duration.between(start, Instant.now()))
                    .build();
        }

        // Compile transformed source
        CompilationResult transformedResult = compileSource(
                patch.getSourceFile(), transformedSource, context.getClasspath());
        result.addDetail("transformedCompileSuccess", transformedResult.success);
        result.addDetail("transformedErrors", transformedResult.errors.size());
        result.addDetail("transformedWarnings", transformedResult.warnings.size());
        log.info("  Transformed compilation: success={}, errors={}, warnings={}",
                transformedResult.success, transformedResult.errors.size(), transformedResult.warnings.size());

        if (!transformedResult.success) {
            log.error("  Transformed source DOES NOT COMPILE");
            for (String err : transformedResult.errors) {
                result.addDiagnostic("Transformed compile error: " + err);
                log.error("    Error: {}", err);
            }
            return result
                    .verdict(Verdict.FAIL)
                    .riskContribution(0.40)
                    .summary("Transformed source has compilation errors")
                    .executionTime(Duration.between(start, Instant.now()))
                    .build();
        }

        // Check for new warnings
        Set<String> newWarnings = new LinkedHashSet<>(transformedResult.warnings);
        newWarnings.removeAll(originalResult.warnings);
        result.addDetail("newWarnings", newWarnings.size());

        if (!newWarnings.isEmpty()) {
            log.info("  {} new warning(s) introduced by transformation", newWarnings.size());
            for (String warn : newWarnings) {
                result.addDiagnostic("New warning: " + warn);
            }
            return result
                    .verdict(Verdict.WARN)
                    .riskContribution(0.05)
                    .summary("Transformation introduces %d new warning(s)".formatted(newWarnings.size()))
                    .executionTime(Duration.between(start, Instant.now()))
                    .build();
        }

        log.info("  Both versions compile cleanly");
        return result
                .verdict(Verdict.PASS)
                .riskContribution(0.0)
                .summary("Both original and transformed source compile successfully with no new warnings")
                .executionTime(Duration.between(start, Instant.now()))
                .build();
    }

    /**
     * Compiles Java source code using the system Java compiler.
     */
    private CompilationResult compileSource(String fileName, String sourceCode, List<Path> classpath) {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            log.error("No Java compiler available (JDK required, not JRE)");
            return new CompilationResult(false,
                    List.of("No Java compiler available"), List.of());
        }

        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        StandardJavaFileManager stdFileManager = compiler.getStandardFileManager(diagnostics, null, null);

        // Create in-memory source file
        JavaFileObject sourceFile = new InMemoryJavaSource(fileName, sourceCode);

        // Build compiler options
        List<String> options = new ArrayList<>();
        if (classpath != null && !classpath.isEmpty()) {
            String cp = classpath.stream()
                    .map(Path::toString)
                    .collect(Collectors.joining(File.pathSeparator));
            options.addAll(List.of("-classpath", cp));
        }
        options.addAll(List.of("-source", "21", "-target", "21"));

        // Compile
        JavaCompiler.CompilationTask task = compiler.getTask(
                null, stdFileManager, diagnostics, options, null, List.of(sourceFile));

        boolean success = task.call();

        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        for (Diagnostic<? extends JavaFileObject> diag : diagnostics.getDiagnostics()) {
            String message = "%s (line %d): %s".formatted(
                    diag.getKind(), diag.getLineNumber(), diag.getMessage(null));
            if (diag.getKind() == Diagnostic.Kind.ERROR) {
                errors.add(message);
            } else if (diag.getKind() == Diagnostic.Kind.WARNING
                    || diag.getKind() == Diagnostic.Kind.MANDATORY_WARNING) {
                warnings.add(message);
            }
        }

        try {
            stdFileManager.close();
        } catch (IOException e) {
            log.debug("Error closing file manager: {}", e.getMessage());
        }

        return new CompilationResult(success, errors, warnings);
    }

    private record CompilationResult(boolean success, List<String> errors, List<String> warnings) {}

    /**
     * In-memory Java source file for compilation.
     */
    private static final class InMemoryJavaSource extends SimpleJavaFileObject {
        private final String code;

        InMemoryJavaSource(String name, String code) {
            super(URI.create("string:///" + name.replace('.', '/') + Kind.SOURCE.extension),
                    Kind.SOURCE);
            this.code = code;
        }

        @Override
        public CharSequence getCharContent(boolean ignoreEncodingErrors) {
            return code;
        }
    }
}
