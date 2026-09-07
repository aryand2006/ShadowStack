package com.shadowstack.adapters.cobol.runtime;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Parsed JCL job graph (roadmap Phase 5) — JOB/STEP/DD dependencies for batch cutover.
 * Exotic JCL constructs should be recorded as gaps, not silently ignored.
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

    /**
     * Minimal parser for classic JCL cards: //JOB, //STEP EXEC PGM=, //DD DSN=, and comments.
     * PROC/INCLUDE/IF/THEN and complex COND are recorded as gaps.
     */
    public static JclJobGraph parse(String jclText) {
        Objects.requireNonNull(jclText, "jclText");
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
            if (u.contains(" PROC ") || u.startsWith("//") && u.contains(" INCLUDE ")) {
                gaps.add("JCL PROC/INCLUDE: " + line.trim());
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

    private static String extractKv(String line, String key) {
        String u = line.toUpperCase();
        String k = key.toUpperCase() + "=";
        int idx = u.indexOf(k);
        if (idx < 0) return null;
        int start = idx + k.length();
        int end = start;
        while (end < line.length()) {
            char c = line.charAt(end);
            if (c == ',' || c == ' ' || c == '\'') break;
            end++;
        }
        String val = line.substring(start, end).trim();
        if (val.startsWith("'") && val.endsWith("'") && val.length() >= 2) {
            val = val.substring(1, val.length() - 1);
        }
        return val.isEmpty() ? null : val;
    }
}
