package com.shadowstack.adapters.cobol;

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
 * Project-level COBOL→Java translate: expand copybooks, order by CALL graph
 * (leaf callees first when possible), write {@code Translated*.java} beside sources.
 */
public final class CobolProjectTranslator {

    private CobolProjectTranslator() {}

    /**
     * Translate all COBOL programs under {@code sourceRoot}.
     *
     * @return summary with per-program results and graph gaps
     */
    public static Summary translateAll(Path sourceRoot) throws IOException {
        Objects.requireNonNull(sourceRoot, "sourceRoot");
        CobolProgramGraph graph = CobolProgramGraph.discover(sourceRoot);
        // Leaf callees first = reverse of caller→callee topological order.
        List<String> topo = graph.topologicalHints();
        List<String> order = new ArrayList<>(topo);
        Collections.reverse(order);

        Map<String, CobolToJavaTranslator.Result> results = new LinkedHashMap<>();
        List<String> writeErrors = new ArrayList<>();

        // Also translate any discovered source even if not in order (safety).
        for (String programId : order) {
            Path cob = graph.sourceOf(programId);
            if (cob == null || !Files.isRegularFile(cob)) continue;
            translateAndWrite(sourceRoot, cob, programId, results, writeErrors);
        }
        // Catch programs that somehow weren't in topo (should not happen).
        for (String programId : graph.programIds()) {
            if (results.containsKey(programId)) continue;
            Path cob = graph.sourceOf(programId);
            if (cob == null) continue;
            translateAndWrite(sourceRoot, cob, programId, results, writeErrors);
        }

        List<String> allGaps = new ArrayList<>(graph.gaps());
        allGaps.addAll(writeErrors);
        return new Summary(List.copyOf(order), Map.copyOf(results),
                List.copyOf(allGaps), graph);
    }

    private static void translateAndWrite(
            Path sourceRoot,
            Path cob,
            String programId,
            Map<String, CobolToJavaTranslator.Result> results,
            List<String> writeErrors) {
        try {
            String source = Files.readString(cob, StandardCharsets.UTF_8);
            CobolToJavaTranslator.Result r =
                    CobolToJavaTranslator.translate(source, sourceRoot);
            Path javaOut = cob.getParent().resolve(r.className() + ".java");
            Files.writeString(javaOut, r.javaSource(), StandardCharsets.UTF_8);
            results.put(programId, r);
        } catch (IOException ex) {
            writeErrors.add("Failed to translate/write " + programId + ": " + ex.getMessage());
        }
    }

    /** Outcome of a project-wide translate. */
    public record Summary(
            List<String> translationOrder,
            Map<String, CobolToJavaTranslator.Result> results,
            List<String> gaps,
            CobolProgramGraph graph) {
        public Summary {
            translationOrder = translationOrder == null ? List.of() : List.copyOf(translationOrder);
            results = results == null ? Map.of() : Map.copyOf(results);
            gaps = gaps == null ? List.of() : List.copyOf(gaps);
        }

        public int programCount() {
            return results.size();
        }
    }
}
