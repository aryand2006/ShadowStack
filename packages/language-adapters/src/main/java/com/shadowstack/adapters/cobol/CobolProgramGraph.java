package com.shadowstack.adapters.cobol;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Lightweight COBOL program CALL graph for Phase 6 cross-program rehost.
 *
 * <p>Discovers {@code .cob}/{@code .cbl}/{@code .COB} sources under a root,
 * extracts {@code PROGRAM-ID} and simple {@code CALL 'X'} / {@code CALL X}
 * targets, and builds a directed program→callee graph. Unresolved CALL
 * targets (not among discovered PROGRAM-IDs) are recorded as gaps.</p>
 */
public final class CobolProgramGraph {

    private static final Pattern PROGRAM_ID = Pattern.compile(
            "(?i)PROGRAM-ID\\s*\\.?\\s*([A-Z0-9][A-Z0-9-]*)\\s*\\.?");
    private static final Pattern CALL_TARGET = Pattern.compile(
            "(?i)\\bCALL\\s+(?:'([^']+)'|\"([^\"]+)\"|([A-Z0-9][A-Z0-9-]*))");

    private final Map<String, Set<String>> edges = new LinkedHashMap<>();
    private final Map<String, Path> sources = new LinkedHashMap<>();
    private final List<String> gaps = new ArrayList<>();

    private CobolProgramGraph() {}

    /**
     * Scan {@code root} for COBOL programs and build the CALL graph.
     */
    public static CobolProgramGraph discover(Path root) throws IOException {
        Objects.requireNonNull(root, "root");
        CobolProgramGraph graph = new CobolProgramGraph();
        if (!Files.isDirectory(root)) {
            graph.gaps.add("source root is not a directory: " + root);
            return graph;
        }
        List<Path> files;
        try (Stream<Path> walk = Files.walk(root)) {
            files = walk
                    .filter(Files::isRegularFile)
                    .filter(CobolProgramGraph::isCobolSource)
                    .sorted()
                    .collect(Collectors.toList());
        }
        for (Path file : files) {
            String text = Files.readString(file, StandardCharsets.UTF_8);
            String programId = extractProgramId(text);
            if (programId == null) {
                graph.gaps.add("No PROGRAM-ID in " + root.relativize(file));
                continue;
            }
            graph.sources.put(programId, file);
            graph.edges.computeIfAbsent(programId, k -> new LinkedHashSet<>());
            for (String callee : extractCallTargets(text)) {
                graph.edges.get(programId).add(callee);
            }
        }
        // Record unresolved CALL targets after all PROGRAM-IDs are known.
        Set<String> known = graph.edges.keySet();
        for (Map.Entry<String, Set<String>> e : graph.edges.entrySet()) {
            for (String callee : e.getValue()) {
                if (!known.contains(callee)) {
                    String gap = "Unresolved CALL target: " + callee
                            + " (from " + e.getKey() + ")";
                    if (!graph.gaps.contains(gap)) {
                        graph.gaps.add(gap);
                    }
                }
            }
        }
        return graph;
    }

    public Set<String> programIds() {
        return Collections.unmodifiableSet(edges.keySet());
    }

    public Set<String> callees(String programId) {
        if (programId == null) return Set.of();
        Set<String> c = edges.get(programId.toUpperCase(Locale.ROOT));
        return c == null ? Set.of() : Collections.unmodifiableSet(c);
    }

    public Set<String> callers(String programId) {
        if (programId == null) return Set.of();
        String target = programId.toUpperCase(Locale.ROOT);
        Set<String> out = new LinkedHashSet<>();
        for (Map.Entry<String, Set<String>> e : edges.entrySet()) {
            if (e.getValue().contains(target)) {
                out.add(e.getKey());
            }
        }
        return Collections.unmodifiableSet(out);
    }

    /**
     * Kahn-style topological order of known programs (callers before callees).
     * Cycles fall back to discovery order for remaining nodes.
     */
    public List<String> topologicalHints() {
        Map<String, Integer> indegree = new LinkedHashMap<>();
        for (String p : edges.keySet()) {
            indegree.put(p, 0);
        }
        for (Map.Entry<String, Set<String>> e : edges.entrySet()) {
            for (String callee : e.getValue()) {
                if (indegree.containsKey(callee)) {
                    indegree.merge(callee, 1, Integer::sum);
                }
            }
        }
        ArrayDeque<String> ready = new ArrayDeque<>();
        for (Map.Entry<String, Integer> e : indegree.entrySet()) {
            if (e.getValue() == 0) ready.add(e.getKey());
        }
        List<String> order = new ArrayList<>();
        while (!ready.isEmpty()) {
            String n = ready.poll();
            order.add(n);
            for (String callee : edges.getOrDefault(n, Set.of())) {
                if (!indegree.containsKey(callee)) continue;
                int d = indegree.merge(callee, -1, Integer::sum);
                if (d == 0) ready.add(callee);
            }
        }
        for (String p : edges.keySet()) {
            if (!order.contains(p)) order.add(p);
        }
        return List.copyOf(order);
    }

    public List<String> gaps() {
        return List.copyOf(gaps);
    }

    /** Source path for a discovered PROGRAM-ID, or null. */
    public Path sourceOf(String programId) {
        if (programId == null) return null;
        return sources.get(programId.toUpperCase(Locale.ROOT));
    }

    private static boolean isCobolSource(Path path) {
        String name = path.getFileName().toString();
        int dot = name.lastIndexOf('.');
        if (dot < 0) return false;
        String ext = name.substring(dot + 1);
        return ext.equalsIgnoreCase("cob") || ext.equalsIgnoreCase("cbl");
    }

    static String extractProgramId(String source) {
        Matcher m = PROGRAM_ID.matcher(source);
        return m.find() ? m.group(1).toUpperCase(Locale.ROOT) : null;
    }

    static List<String> extractCallTargets(String source) {
        List<String> targets = new ArrayList<>();
        Matcher m = CALL_TARGET.matcher(source);
        while (m.find()) {
            String t = m.group(1) != null ? m.group(1)
                    : m.group(2) != null ? m.group(2) : m.group(3);
            if (t != null && !t.isBlank()) {
                targets.add(t.toUpperCase(Locale.ROOT));
            }
        }
        return targets;
    }
}
