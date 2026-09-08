package com.shadowstack.adapters.cobol.runtime;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Parsed JCL job graph (roadmap Phase 5) — JOB/STEP/DD dependencies for batch cutover.
 * INCLUDE MEMBER= is expanded from an optional include root; PROC remains a gap.
 */
public final class JclJobGraph {

    public record DdStatement(String ddName, String dsn, String disp) {}

    public record Step(String stepName, String program, List<DdStatement> dds, String cond) {
        public Step {
            dds = dds == null ? List.of() : List.copyOf(dds);
        }
    }

    private final String jobName;
    private final List<Step> steps;
    private final List<String> gaps;

    public JclJobGraph(String jobName, List<Step> steps, List<String> gaps) {
        this.jobName = Objects.requireNonNullElse(jobName, "UNKNOWN");
        this.steps = steps == null ? List.of() : List.copyOf(steps);
        this.gaps = gaps == null ? List.of() : List.copyOf(gaps);
    }

    public String jobName() { return jobName; }
    public List<Step> steps() { return steps; }
    public List<String> gaps() { return gaps; }

    public static JclJobGraph parse(String jclText) {
        return parse(jclText, null);
    }

    /**
     * Parse JCL, expanding {@code // INCLUDE MEMBER=name} from {@code includeRoot}.
     */
    public static JclJobGraph parse(String jclText, Path includeRoot) {
        Objects.requireNonNull(jclText, "jclText");
        List<String> expandGaps = new ArrayList<>();
        String expanded = expandIncludes(jclText, includeRoot, expandGaps, 0);
        JclJobGraph graph = parseExpanded(expanded);
        if (expandGaps.isEmpty()) {
            return graph;
        }
        List<String> merged = new ArrayList<>(expandGaps);
        merged.addAll(graph.gaps());
        return new JclJobGraph(graph.jobName(), graph.steps(), merged);
    }

    private static String expandIncludes(
            String jclText, Path includeRoot, List<String> gaps, int depth) {
        if (depth > 8) {
            gaps.add("JCL INCLUDE nesting too deep");
            return jclText;
        }
        StringBuilder out = new StringBuilder();
        for (String raw : jclText.split("\n", -1)) {
            String line = raw.stripTrailing();
            String u = line.toUpperCase();
            if (line.startsWith("//") && u.contains(" INCLUDE ") && u.contains("MEMBER=")) {
                String member = extractKv(line, "MEMBER");
                if (member == null || member.isBlank()) {
                    gaps.add("JCL INCLUDE missing MEMBER: " + line.trim());
                    continue;
                }
                if (includeRoot == null) {
                    gaps.add("JCL INCLUDE unresolved (no include root): " + member);
                    continue;
                }
                Path candidate = includeRoot.resolve(member);
                Path withJcl = includeRoot.resolve(member + ".jcl");
                Path file = Files.isRegularFile(candidate) ? candidate
                        : Files.isRegularFile(withJcl) ? withJcl : null;
                if (file == null) {
                    gaps.add("JCL INCLUDE member not found: " + member);
                    continue;
                }
                try {
                    String body = Files.readString(file, StandardCharsets.UTF_8);
                    out.append(expandIncludes(body, includeRoot, gaps, depth + 1));
                    if (out.length() > 0 && out.charAt(out.length() - 1) != '\n') {
                        out.append('\n');
                    }
                } catch (IOException e) {
                    gaps.add("JCL INCLUDE read failed: " + member + " (" + e.getMessage() + ")");
                }
                continue;
            }
            out.append(raw).append('\n');
        }
        return out.toString();
    }

    private static JclJobGraph parseExpanded(String jclText) {
        String jobName = "UNKNOWN";
        List<Step> steps = new ArrayList<>();
        List<String> gaps = new ArrayList<>();
        String currentStep = null;
        String currentPgm = null;
        String currentCond = null;
        List<DdStatement> dds = new ArrayList<>();

        for (String raw : jclText.split("\n", -1)) {
            String line = raw.stripTrailing();
            if (line.isBlank()) continue;
            if (line.startsWith("//*") || line.startsWith("/*")) continue;
            String u = line.toUpperCase();
            if (u.contains(" PROC ")) {
                gaps.add("JCL PROC: " + line.trim());
                continue;
            }
            if (u.contains(" INCLUDE ")) {
                gaps.add("JCL INCLUDE unresolved: " + line.trim());
                continue;
            }
            if (u.matches("//\\S+\\s+IF\\s+.*") || u.contains(" THEN ") || u.trim().equals("//ENDIF")) {
                gaps.add("JCL IF/THEN: " + line.trim());
                continue;
            }
            if (u.matches("//\\S+\\s+JOB\\b.*") || u.matches("//\\s+JOB\\b.*")) {
                jobName = extractName(line, "JOB");
                continue;
            }
            if (u.contains(" EXEC ") && u.contains("PGM=")) {
                if (currentStep != null) {
                    steps.add(new Step(currentStep, currentPgm, dds, currentCond));
                }
                currentStep = extractName(line, "EXEC");
                currentPgm = extractKv(line, "PGM");
                currentCond = extractKv(line, "COND");
                dds = new ArrayList<>();
                continue;
            }
            if (u.contains(" DD ") || u.matches("//\\S+\\s+DD\\b.*")) {
                String ddName = extractName(line, "DD");
                dds.add(new DdStatement(ddName, extractKv(line, "DSN"), extractKv(line, "DISP")));
                continue;
            }
            if (line.startsWith("//")) {
                gaps.add("JCL unrecognized: " + line.trim());
            }
        }
        if (currentStep != null) {
            steps.add(new Step(currentStep, currentPgm, dds, currentCond));
        }
        return new JclJobGraph(jobName, steps, gaps);
    }

    /** Topological order is declaration order for this MVP (no IF/THEN graph yet). */
    public List<String> executionOrder() {
        List<String> order = new ArrayList<>();
        for (Step s : steps) {
            order.add(s.stepName());
        }
        return Collections.unmodifiableList(order);
    }

    public Map<String, String> programByStep() {
        Map<String, String> m = new LinkedHashMap<>();
        for (Step s : steps) {
            m.put(s.stepName(), s.program());
        }
        return Collections.unmodifiableMap(m);
    }

    private static String extractName(String line, String card) {
        String trimmed = line.trim();
        if (!trimmed.startsWith("//")) return "UNKNOWN";
        String rest = trimmed.substring(2).trim();
        int sp = rest.indexOf(' ');
        if (sp < 0) return rest.isEmpty() ? "UNKNOWN" : rest;
        String name = rest.substring(0, sp).trim();
        if (name.equalsIgnoreCase(card) || name.isEmpty()) {
            return "ANONYMOUS";
        }
        return name;
    }

    static String extractKv(String line, String key) {
        String u = line.toUpperCase();
        String needle = key.toUpperCase() + "=";
        int idx = u.indexOf(needle);
        if (idx < 0) return null;
        int start = idx + needle.length();
        int end = start;
        while (end < line.length()) {
            char c = line.charAt(end);
            if (c == ',' || c == ' ' || c == '\t') break;
            end++;
        }
        String val = line.substring(start, end).trim();
        if (val.startsWith("(") && val.endsWith(")") && val.length() > 2) {
            // keep DISP=(NEW,CATLG) style as-is when fully parenthesized without nested scan
        }
        // Re-scan for parenthesized DISP values
        if (key.equalsIgnoreCase("DISP") && start < line.length() && line.charAt(start) == '(') {
            int depth = 0;
            end = start;
            while (end < line.length()) {
                char c = line.charAt(end);
                if (c == '(') depth++;
                if (c == ')') {
                    depth--;
                    if (depth == 0) {
                        end++;
                        break;
                    }
                }
                end++;
            }
            val = line.substring(start, end).trim();
        }
        return val.isEmpty() ? null : val;
    }
}
