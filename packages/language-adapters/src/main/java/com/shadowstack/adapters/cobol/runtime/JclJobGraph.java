package com.shadowstack.adapters.cobol.runtime;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parsed JCL job graph (roadmap Phase 5) — JOB/STEP/DD dependencies for batch cutover.
 * INCLUDE MEMBER= and EXEC PROC= are expanded from an optional include root.
 * Simple IF/THEN/ELSE/ENDIF attaches {@code IF:…} cond expressions on steps (MVP).
 * PROC symbolic {@code &NAME}/{@code &&NAME} substitution is best-effort.
 * Not bit-identical IBM JCL/PROC semantics.
 */
public final class JclJobGraph {

    public record DdStatement(String ddName, String dsn, String disp) {}

    public record Step(String stepName, String program, List<DdStatement> dds, String cond) {
        public Step {
            dds = dds == null ? List.of() : List.copyOf(dds);
        }
    }

    private static final Pattern SYMBOLIC_REF = Pattern.compile("&{1,2}([A-Z@#$][A-Z0-9@#$]*)");
    private static final Pattern IF_THEN = Pattern.compile(
            "^//\\s*(?:\\S+\\s+)?IF\\b(.*)\\bTHEN\\s*$", Pattern.CASE_INSENSITIVE);
    private static final Pattern ELSE_CARD = Pattern.compile(
            "^//\\s*(?:\\S+\\s+)?ELSE\\s*$", Pattern.CASE_INSENSITIVE);
    private static final Pattern ENDIF_CARD = Pattern.compile(
            "^//\\s*(?:\\S+\\s+)?ENDIF\\s*$", Pattern.CASE_INSENSITIVE);

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
     * Parse JCL, expanding {@code // INCLUDE MEMBER=name} and {@code EXEC PROC=name}
     * from {@code includeRoot} ({@code name}, {@code name.jcl}, or {@code name.proc}).
     */
    public static JclJobGraph parse(String jclText, Path includeRoot) {
        Objects.requireNonNull(jclText, "jclText");
        List<String> expandGaps = new ArrayList<>();
        String expanded = expandIncludesAndProcs(jclText, includeRoot, expandGaps, 0);
        JclJobGraph graph = parseExpanded(expanded);
        if (expandGaps.isEmpty()) {
            return graph;
        }
        List<String> merged = new ArrayList<>(expandGaps);
        merged.addAll(graph.gaps());
        return new JclJobGraph(graph.jobName(), graph.steps(), merged);
    }

    private static String expandIncludesAndProcs(
            String jclText, Path includeRoot, List<String> gaps, int depth) {
        if (depth > 8) {
            gaps.add("JCL INCLUDE/PROC nesting too deep");
            return jclText;
        }
        StringBuilder out = new StringBuilder();
        for (String raw : jclText.split("\n", -1)) {
            String line = raw.stripTrailing();
            String u = line.toUpperCase(Locale.ROOT);
            if (line.startsWith("//") && u.contains(" INCLUDE ") && u.contains("MEMBER=")) {
                String member = extractKv(line, "MEMBER");
                if (member == null || member.isBlank()) {
                    gaps.add("JCL INCLUDE missing MEMBER: " + line.trim());
                    continue;
                }
                String body = loadMember(includeRoot, member, gaps, "INCLUDE");
                if (body == null) {
                    continue;
                }
                appendExpanded(out, expandIncludesAndProcs(body, includeRoot, gaps, depth + 1));
                continue;
            }
            if (line.startsWith("//") && isExecProc(u)) {
                String procName = extractKv(line, "PROC");
                if (procName == null || procName.isBlank()) {
                    gaps.add("JCL PROC missing name: " + line.trim());
                    continue;
                }
                String body = loadMember(includeRoot, procName, gaps, "PROC");
                if (body == null) {
                    continue;
                }
                Map<String, String> overrides = extractSymbolicAssignments(line, "PROC");
                ProcBody stripped = stripProcHeader(body);
                Map<String, String> symbolics = new LinkedHashMap<>(stripped.defaults());
                symbolics.putAll(overrides);
                String substituted = applySymbolics(stripped.body(), symbolics, gaps);
                appendExpanded(out, expandIncludesAndProcs(
                        substituted, includeRoot, gaps, depth + 1));
                continue;
            }
            out.append(raw).append('\n');
        }
        return out.toString();
    }

    /** {@code //name EXEC PROC=...} or {@code // EXEC PROC=...}. */
    private static boolean isExecProc(String upperLine) {
        return upperLine.contains(" EXEC ") && upperLine.contains("PROC=")
                && !upperLine.contains("PGM=");
    }

    /**
     * Resolve {@code includeRoot/name}, {@code name.jcl}, then {@code name.proc}.
     * Returns null and records a gap when unresolved.
     */
    private static String loadMember(
            Path includeRoot, String member, List<String> gaps, String kind) {
        if (includeRoot == null) {
            gaps.add("JCL " + kind + " unresolved (no include root): " + member);
            return null;
        }
        Path candidate = includeRoot.resolve(member);
        Path withJcl = includeRoot.resolve(member + ".jcl");
        Path withProc = includeRoot.resolve(member + ".proc");
        Path file = Files.isRegularFile(candidate) ? candidate
                : Files.isRegularFile(withJcl) ? withJcl
                : Files.isRegularFile(withProc) ? withProc : null;
        if (file == null) {
            gaps.add("JCL " + kind + " member not found: " + member);
            return null;
        }
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            gaps.add("JCL " + kind + " read failed: " + member + " (" + e.getMessage() + ")");
            return null;
        }
    }

    private record ProcBody(String body, Map<String, String> defaults) {}

    /**
     * Drop a leading {@code //name PROC} definition card from an inlined procedure body
     * and capture default symbolic assignments from that card.
     */
    private static ProcBody stripProcHeader(String body) {
        StringBuilder kept = new StringBuilder();
        Map<String, String> defaults = new LinkedHashMap<>();
        boolean skippedHeader = false;
        for (String raw : body.split("\n", -1)) {
            String line = raw.stripTrailing();
            String u = line.toUpperCase(Locale.ROOT);
            if (!skippedHeader && line.startsWith("//") && isProcDefinitionCard(u)) {
                skippedHeader = true;
                defaults.putAll(extractSymbolicAssignments(line, null));
                continue;
            }
            if (line.startsWith("//") && u.matches("//\\S*\\s+PEND\\b.*")) {
                continue;
            }
            kept.append(raw).append('\n');
        }
        return new ProcBody(kept.toString(), defaults);
    }

    private static boolean isProcDefinitionCard(String upperLine) {
        // //NAME PROC ... or // PROC ... — not EXEC PROC=
        if (upperLine.contains("EXEC") || upperLine.contains("PROC=")) {
            return false;
        }
        return upperLine.matches("//\\S*\\s+PROC\\b.*") || upperLine.matches("//\\s*PROC\\b.*");
    }

    /**
     * Collect {@code KEY=VALUE} pairs from a JCL card, optionally skipping one key
     * (e.g. {@code PROC} on an EXEC PROC= line).
     */
    static Map<String, String> extractSymbolicAssignments(String line, String skipKey) {
        Map<String, String> out = new LinkedHashMap<>();
        String u = line.toUpperCase(Locale.ROOT);
        int i = 0;
        while (i < line.length()) {
            int eq = u.indexOf('=', i);
            if (eq < 0) {
                break;
            }
            int keyStart = eq - 1;
            while (keyStart >= 0) {
                char c = u.charAt(keyStart);
                if (Character.isLetterOrDigit(c) || c == '@' || c == '#' || c == '$') {
                    keyStart--;
                } else {
                    break;
                }
            }
            keyStart++;
            if (keyStart >= eq) {
                i = eq + 1;
                continue;
            }
            String key = u.substring(keyStart, eq);
            if (skipKey != null && key.equalsIgnoreCase(skipKey)) {
                i = eq + 1;
                continue;
            }
            // Skip accidental matches inside DSN= etc. only when key looks like a JCL keyword
            // used as a non-symbolic on PROC/EXEC — still allow as symbolic override for MVP.
            int start = eq + 1;
            int end = start;
            if (start < line.length() && line.charAt(start) == '(') {
                int depth = 0;
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
            } else {
                while (end < line.length()) {
                    char c = line.charAt(end);
                    if (c == ',' || c == ' ' || c == '\t') break;
                    end++;
                }
            }
            String val = line.substring(start, end).trim();
            if (!key.isBlank() && !val.isEmpty()) {
                out.put(key, val);
            }
            i = Math.max(end, eq + 1);
        }
        return out;
    }

    /**
     * Replace {@code &&NAME} then {@code &NAME} using supplied symbolics (case-insensitive keys).
     * Remaining {@code &NAME} refs become soft gap notes.
     */
    static String applySymbolics(String body, Map<String, String> symbolics, List<String> gaps) {
        if (body == null || body.isEmpty()) {
            return body;
        }
        Map<String, String> upper = new LinkedHashMap<>();
        if (symbolics != null) {
            for (Map.Entry<String, String> e : symbolics.entrySet()) {
                if (e.getKey() != null && e.getValue() != null) {
                    upper.put(e.getKey().toUpperCase(Locale.ROOT), e.getValue());
                }
            }
        }
        String result = body;
        // Longer keys first so &FOOBAR does not eat &FOO.
        List<String> keys = new ArrayList<>(upper.keySet());
        keys.sort((a, b) -> Integer.compare(b.length(), a.length()));
        for (String key : keys) {
            String val = upper.get(key);
            result = replaceIgnoreCase(result, "&&" + key, val);
            result = replaceIgnoreCase(result, "&" + key, val);
        }
        Matcher m = SYMBOLIC_REF.matcher(result.toUpperCase(Locale.ROOT));
        Set<String> unresolved = new LinkedHashSet<>();
        while (m.find()) {
            String name = m.group(1);
            if (!upper.containsKey(name)) {
                unresolved.add(name);
            }
        }
        for (String name : unresolved) {
            gaps.add("JCL unresolved symbolic &" + name);
        }
        return result;
    }

    private static String replaceIgnoreCase(String haystack, String needle, String replacement) {
        if (haystack == null || needle == null || needle.isEmpty()) {
            return haystack;
        }
        String h = haystack;
        String n = needle;
        StringBuilder sb = new StringBuilder();
        int i = 0;
        String hu = h.toUpperCase(Locale.ROOT);
        String nu = n.toUpperCase(Locale.ROOT);
        while (i < h.length()) {
            int idx = hu.indexOf(nu, i);
            if (idx < 0) {
                sb.append(h, i, h.length());
                break;
            }
            sb.append(h, i, idx);
            sb.append(replacement);
            i = idx + n.length();
        }
        return sb.toString();
    }

    private static void appendExpanded(StringBuilder out, String body) {
        out.append(body);
        if (out.length() > 0 && out.charAt(out.length() - 1) != '\n') {
            out.append('\n');
        }
    }

    private static final class IfFrame {
        final String thenCond;
        final String elseCond;
        boolean inElse;

        IfFrame(String thenCond, String elseCond) {
            this.thenCond = thenCond;
            this.elseCond = elseCond;
            this.inElse = false;
        }

        String activeCond() {
            return inElse ? elseCond : thenCond;
        }
    }

    private static JclJobGraph parseExpanded(String jclText) {
        String jobName = "UNKNOWN";
        List<Step> steps = new ArrayList<>();
        List<String> gaps = new ArrayList<>();
        String currentStep = null;
        String currentPgm = null;
        String currentCond = null;
        List<DdStatement> dds = new ArrayList<>();
        Deque<IfFrame> ifStack = new ArrayDeque<>();

        for (String raw : jclText.split("\n", -1)) {
            String line = raw.stripTrailing();
            if (line.isBlank()) continue;
            if (line.startsWith("//*") || line.startsWith("/*")) continue;
            String u = line.toUpperCase(Locale.ROOT);

            if (line.startsWith("//") && isIfThenCard(u)) {
                String expr = extractIfExpression(line);
                if (expr == null) {
                    gaps.add("JCL IF/THEN unparseable: " + line.trim());
                } else {
                    String thenCond = "IF:" + expr;
                    ifStack.push(new IfFrame(thenCond, "IF:" + invertSimpleRc(expr)));
                }
                continue;
            }
            if (line.startsWith("//") && ELSE_CARD.matcher(u).matches()) {
                if (ifStack.isEmpty()) {
                    gaps.add("JCL ELSE without IF: " + line.trim());
                } else {
                    ifStack.peek().inElse = true;
                }
                continue;
            }
            if (line.startsWith("//") && ENDIF_CARD.matcher(u).matches()) {
                if (ifStack.isEmpty()) {
                    gaps.add("JCL ENDIF without IF: " + line.trim());
                } else {
                    ifStack.pop();
                }
                continue;
            }

            if (isExecProc(u) || (line.startsWith("//") && isProcDefinitionCard(u))
                    || (u.contains(" PROC ") && !u.contains("PGM="))) {
                gaps.add("JCL PROC: " + line.trim());
                continue;
            }
            if (u.contains(" INCLUDE ")) {
                gaps.add("JCL INCLUDE unresolved: " + line.trim());
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
                String execCond = extractKv(line, "COND");
                String ifCond = activeIfCond(ifStack);
                currentCond = ifCond != null ? ifCond : execCond;
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
        if (!ifStack.isEmpty()) {
            gaps.add("JCL IF without ENDIF (" + ifStack.size() + " open)");
        }
        return new JclJobGraph(jobName, steps, gaps);
    }

    private static boolean isIfThenCard(String upperLine) {
        return IF_THEN.matcher(upperLine).matches();
    }

    /** Active IF-branch cond, or null when not inside an IF. */
    private static String activeIfCond(Deque<IfFrame> ifStack) {
        if (ifStack.isEmpty()) {
            return null;
        }
        // Nested IFs: innermost frame wins for MVP.
        return ifStack.peek().activeCond();
    }

    /**
     * From {@code // IF (RC = 0) THEN} extract normalized {@code RC=0}.
     */
    static String extractIfExpression(String line) {
        Matcher m = IF_THEN.matcher(line.trim());
        if (!m.matches()) {
            return null;
        }
        String raw = m.group(1).trim();
        if (raw.startsWith("(") && raw.endsWith(")")) {
            raw = raw.substring(1, raw.length() - 1).trim();
        } else if (raw.startsWith("(") && raw.contains(")")) {
            raw = raw.substring(1, raw.lastIndexOf(')')).trim();
        }
        if (raw.isEmpty()) {
            return null;
        }
        return normalizeIfExpr(raw);
    }

    private static String normalizeIfExpr(String expr) {
        return expr.replaceAll("\\s*=\\s*", "=")
                .replaceAll("\\s*!=\\s*", "!=")
                .replaceAll("\\s+", "")
                .toUpperCase(Locale.ROOT);
    }

    /**
     * Invert simple {@code RC=n} / {@code RC!=n} forms; otherwise wrap with {@code NOT(…)}.
     */
    static String invertSimpleRc(String expr) {
        if (expr == null || expr.isBlank()) {
            return "NOT()";
        }
        String e = expr.trim();
        if (e.matches("(?i).+!=.+")) {
            int idx = e.indexOf("!=");
            return e.substring(0, idx) + "=" + e.substring(idx + 2);
        }
        if (e.matches("(?i).+=.+")) {
            int idx = e.indexOf('=');
            return e.substring(0, idx) + "!=" + e.substring(idx + 1);
        }
        return "NOT(" + e + ")";
    }

    /** Declaration order for this MVP (IF conds annotate steps; all steps listed). */
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
        String u = line.toUpperCase(Locale.ROOT);
        String needle = key.toUpperCase(Locale.ROOT) + "=";
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
