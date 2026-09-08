package com.shadowstack.adapters.cobol.runtime;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * Phase 5 MVP — run a parsed {@link JclJobGraph} against local Translated* Java
 * programs. Maps DD DSN → {@code workDir/<ddName>.dat}.
 * Evaluates simple {@code COND=(code,op)} against prior step RC.
 * PROC/INCLUDE/catalog remain gaps. Fail-closed on missing programs.
 */
public final class JclJobRunner {

    public record StepResult(
            String stepName,
            String program,
            boolean ran,
            int exitCode,
            String stdout,
            List<String> gaps) {
        public StepResult {
            gaps = gaps == null ? List.of() : List.copyOf(gaps);
        }
    }

    public record RunResult(String jobName, List<StepResult> steps, boolean allSucceeded) {
        public RunResult {
            steps = steps == null ? List.of() : List.copyOf(steps);
        }
    }

    private final Path workDir;
    private final Path classPathRoot;
    private final Function<String, Path> programClassResolver;
    private final long stepTimeoutSeconds;

    public JclJobRunner(Path workDir, Path classPathRoot, Function<String, Path> programClassResolver) {
        this(workDir, classPathRoot, programClassResolver, 60);
    }

    public JclJobRunner(
            Path workDir,
            Path classPathRoot,
            Function<String, Path> programClassResolver,
            long stepTimeoutSeconds) {
        this.workDir = Objects.requireNonNull(workDir, "workDir").toAbsolutePath().normalize();
        this.classPathRoot = Objects.requireNonNull(classPathRoot, "classPathRoot")
                .toAbsolutePath().normalize();
        this.programClassResolver = Objects.requireNonNull(programClassResolver, "programClassResolver");
        this.stepTimeoutSeconds = Math.max(1, stepTimeoutSeconds);
    }

    /**
     * Default resolver: {@code Translated<PROGRAM>} class next to {@code classPathRoot}.
     */
    public static Function<String, Path> defaultTranslatedResolver(Path classPathRoot) {
        Path root = classPathRoot.toAbsolutePath().normalize();
        return program -> {
            if (program == null || program.isBlank()) return null;
            String className = "Translated" + program.trim().toUpperCase(Locale.ROOT).replace('-', '_');
            Path classFile = root.resolve(className + ".class");
            return Files.isRegularFile(classFile) ? classFile : null;
        };
    }

    public RunResult run(JclJobGraph graph) throws IOException, InterruptedException {
        Objects.requireNonNull(graph, "graph");
        Files.createDirectories(workDir);
        List<StepResult> results = new ArrayList<>();
        boolean allOk = true;
        int lastRc = 0;
        for (JclJobGraph.Step step : graph.steps()) {
            if (shouldSkipByCond(step.cond(), lastRc)) {
                results.add(new StepResult(step.stepName(), step.program(), false, lastRc, "",
                        List.of("Skipped by COND=" + step.cond() + " (priorRC=" + lastRc + ")")));
                continue;
            }
            StepResult sr = runStep(step);
            results.add(sr);
            if (sr.ran()) {
                lastRc = sr.exitCode();
            }
            if (!sr.ran() || sr.exitCode() != 0) {
                allOk = false;
            }
        }
        return new RunResult(graph.jobName(), results, allOk && graph.gaps().isEmpty());
    }

    /**
     * Classic JCL COND=(code,operator) — skip step when priorRC operator code is true.
     * Operators: GT GE LT LE EQ NE. Only the first simple pair is evaluated.
     */
    static boolean shouldSkipByCond(String cond, int priorRc) {
        if (cond == null || cond.isBlank()) {
            return false;
        }
        String c = cond.trim().toUpperCase(Locale.ROOT);
        if (c.startsWith("(") && c.contains(")")) {
            c = c.substring(c.indexOf('(') + 1, c.indexOf(')')).trim();
        }
        String[] parts = c.split(",");
        if (parts.length < 2) {
            return false;
        }
        try {
            int code = Integer.parseInt(parts[0].trim());
            String op = parts[1].trim();
            return switch (op) {
                case "GT" -> priorRc > code;
                case "GE" -> priorRc >= code;
                case "LT" -> priorRc < code;
                case "LE" -> priorRc <= code;
                case "EQ" -> priorRc == code;
                case "NE" -> priorRc != code;
                default -> false;
            };
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private StepResult runStep(JclJobGraph.Step step) throws IOException, InterruptedException {
        List<String> gaps = new ArrayList<>();
        Map<String, Path> ddFiles = materializeDds(step, gaps);
        Path classFile = programClassResolver.apply(step.program());
        if (classFile == null || !Files.isRegularFile(classFile)) {
            gaps.add("Missing translated program for PGM=" + step.program());
            return new StepResult(step.stepName(), step.program(), false, -1, "", gaps);
        }
        String className = classFile.getFileName().toString().replaceFirst("\\.class$", "");
        List<String> cmd = List.of(
                System.getProperty("shadowstack.verify.java", "java"),
                "-cp", classPathRoot.toString(),
                className);
        ProcessBuilder pb = new ProcessBuilder(cmd)
                .directory(workDir.toFile())
                .redirectErrorStream(true);
        // Expose DD paths as env for future façade wiring; generated helpers use cwd/*.dat.
        for (Map.Entry<String, Path> e : ddFiles.entrySet()) {
            pb.environment().put("DD_" + e.getKey(), e.getValue().toString());
        }
        Process p = pb.start();
        boolean finished = p.waitFor(stepTimeoutSeconds, TimeUnit.SECONDS);
        if (!finished) {
            p.destroyForcibly();
            gaps.add("Step timed out after " + stepTimeoutSeconds + "s: " + step.stepName());
            return new StepResult(step.stepName(), step.program(), true, -1, "", gaps);
        }
        String stdout = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        return new StepResult(step.stepName(), step.program(), true, p.exitValue(), stdout, gaps);
    }

    private Map<String, Path> materializeDds(JclJobGraph.Step step, List<String> gaps) throws IOException {
        Map<String, Path> map = new LinkedHashMap<>();
        for (JclJobGraph.DdStatement dd : step.dds()) {
            String ddName = dd.ddName() == null ? "UNKNOWN" : dd.ddName().toUpperCase(Locale.ROOT);
            if ("SYSOUT".equals(ddName) || "*".equals(dd.dsn())) {
                gaps.add("SYSOUT/* DD ignored for local runner: " + ddName);
                continue;
            }
            Path local = workDir.resolve(ddName + ".dat");
            map.put(ddName, local);
            String disp = dd.disp() == null ? "" : dd.disp().toUpperCase(Locale.ROOT);
            if (disp.contains("NEW") && !Files.exists(local)) {
                Files.createFile(local);
            }
            // DISP=SHR / OLD — leave existing content; do not invent catalog data.
            if ((disp.contains("SHR") || disp.contains("OLD")) && !Files.exists(local)) {
                gaps.add("DD " + ddName + " expects existing dataset locally: " + local.getFileName());
            }
        }
        return map;
    }
}
