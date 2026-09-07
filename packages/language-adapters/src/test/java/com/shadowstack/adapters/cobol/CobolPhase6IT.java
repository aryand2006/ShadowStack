package com.shadowstack.adapters.cobol;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 6: CALL graph, project translate, READ AT END, golden run.
 */
class CobolPhase6IT {

    static final String DRIVER =
            ">>SOURCE FREE\n" +
            "IDENTIFICATION DIVISION.\n" +
            "PROGRAM-ID. DRIVER.\n" +
            "DATA DIVISION.\n" +
            "WORKING-STORAGE SECTION.\n" +
            "01 WS-MSG PIC X(16) VALUE \"DRIVER\".\n" +
            "01 WS-ARG PIC 9(4) VALUE 42.\n" +
            "PROCEDURE DIVISION.\n" +
            "MAIN.\n" +
            "    DISPLAY WS-MSG.\n" +
            "    CALL 'WORKER' USING WS-ARG.\n" +
            "    DISPLAY \"DRIVER-DONE\".\n" +
            "    STOP RUN.\n";

    static final String WORKER =
            ">>SOURCE FREE\n" +
            "IDENTIFICATION DIVISION.\n" +
            "PROGRAM-ID. WORKER.\n" +
            "DATA DIVISION.\n" +
            "WORKING-STORAGE SECTION.\n" +
            "01 WS-MSG PIC X(16) VALUE \"WORKER-OK\".\n" +
            "PROCEDURE DIVISION.\n" +
            "MAIN.\n" +
            "    DISPLAY WS-MSG.\n" +
            "    GOBACK.\n";

    static final String READ_AT_END_PROG =
            ">>SOURCE FREE\n" +
            "IDENTIFICATION DIVISION.\n" +
            "PROGRAM-ID. READEND.\n" +
            "ENVIRONMENT DIVISION.\n" +
            "INPUT-OUTPUT SECTION.\n" +
            "FILE-CONTROL.\n" +
            "    SELECT INFILE ASSIGN TO INDD.\n" +
            "DATA DIVISION.\n" +
            "WORKING-STORAGE SECTION.\n" +
            "01 WS-EOF PIC X VALUE \"N\".\n" +
            "PROCEDURE DIVISION.\n" +
            "MAIN.\n" +
            "    OPEN INPUT INFILE.\n" +
            "    READ INFILE AT END\n" +
            "        MOVE \"Y\" TO WS-EOF\n" +
            "        DISPLAY \"EOF\"\n" +
            "    END-READ.\n" +
            "    CLOSE INFILE.\n" +
            "    STOP RUN.\n";

    @Test
    void two_program_call_emits_translated_worker_main(@TempDir Path tmp) throws Exception {
        Files.writeString(tmp.resolve("DRIVER.cob"), DRIVER, StandardCharsets.UTF_8);
        Files.writeString(tmp.resolve("WORKER.cob"), WORKER, StandardCharsets.UTF_8);

        CobolProjectTranslator.Summary summary = CobolProjectTranslator.translateAll(tmp);
        assertEquals(2, summary.programCount());
        assertTrue(summary.results().containsKey("DRIVER"));
        assertTrue(summary.results().containsKey("WORKER"));

        CobolToJavaTranslator.Result driver = summary.results().get("DRIVER");
        assertTrue(driver.javaSource().contains("TranslatedWORKER.main"),
                () -> driver.javaSource());
        assertTrue(driver.javaSource().contains("String.valueOf(WS_ARG)"),
                () -> driver.javaSource());
        assertTrue(driver.resolvedCalls().contains("WORKER"),
                () -> String.valueOf(driver.resolvedCalls()));
        assertTrue(Files.isRegularFile(tmp.resolve("TranslatedDRIVER.java")));
        assertTrue(Files.isRegularFile(tmp.resolve("TranslatedWORKER.java")));

        // Leaf callees first in translation order.
        assertTrue(summary.translationOrder().indexOf("WORKER")
                        <= summary.translationOrder().indexOf("DRIVER"),
                () -> String.valueOf(summary.translationOrder()));
    }

    @Test
    void program_graph_finds_driver_to_worker_edge(@TempDir Path tmp) throws Exception {
        Files.writeString(tmp.resolve("DRIVER.cob"), DRIVER, StandardCharsets.UTF_8);
        Files.writeString(tmp.resolve("WORKER.cob"), WORKER, StandardCharsets.UTF_8);

        CobolProgramGraph graph = CobolProgramGraph.discover(tmp);
        assertTrue(graph.programIds().contains("DRIVER"));
        assertTrue(graph.programIds().contains("WORKER"));
        assertTrue(graph.callees("DRIVER").contains("WORKER"));
        assertTrue(graph.callers("WORKER").contains("DRIVER"));
        assertTrue(graph.gaps().stream().noneMatch(g -> g.contains("WORKER")),
                () -> String.valueOf(graph.gaps()));
    }

    @Test
    void example_driver_worker_on_disk_when_present() throws Exception {
        Path driver = resolveExample("DRIVER.cob");
        Path worker = resolveExample("WORKER.cob");
        org.junit.jupiter.api.Assumptions.assumeTrue(driver != null && worker != null);
        Path root = driver.getParent();
        CobolProgramGraph graph = CobolProgramGraph.discover(root);
        assertTrue(graph.callees("DRIVER").contains("WORKER"));
    }

    @Test
    void golden_helloss_javac_and_java_stdout() throws Exception {
        String src = CobolTranslateIT.TRANSLATE_DEMO;
        CobolToJavaTranslator.Result r = CobolToJavaTranslator.translate(src);
        assertTrue(r.isTransformative());

        Path tmp = Files.createTempDirectory("cobol-phase6-golden-");
        Path javaFile = tmp.resolve(r.className() + ".java");
        Files.writeString(javaFile, r.javaSource(), StandardCharsets.UTF_8);

        String javac = System.getProperty("shadowstack.verify.javac", "javac");
        Process compile = new ProcessBuilder(javac, javaFile.getFileName().toString())
                .directory(tmp.toFile())
                .redirectErrorStream(true)
                .start();
        String compileOut = new String(compile.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(compile.waitFor(60, TimeUnit.SECONDS));
        assertEquals(0, compile.exitValue(), () -> "javac failed:\n" + compileOut);

        String javaBin = System.getProperty("shadowstack.verify.java", "java");
        Process run = new ProcessBuilder(javaBin, r.className())
                .directory(tmp.toFile())
                .redirectErrorStream(true)
                .start();
        String stdout = new String(run.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(run.waitFor(60, TimeUnit.SECONDS));
        assertEquals(0, run.exitValue(), () -> "java failed:\n" + stdout);
        assertTrue(stdout.contains("READY"),
                "expected READY in stdout, got:\n" + stdout);

        Path golden = resolveExample("golden/HELLOSS.stdout.txt");
        if (golden != null) {
            String expected = Files.readString(golden, StandardCharsets.UTF_8).trim();
            assertTrue(stdout.contains(expected),
                    "golden token missing: " + expected + "\nstdout:\n" + stdout);
        }
    }

    @Test
    void read_at_end_emits_if_not_ok(@TempDir Path tmp) throws Exception {
        CobolToJavaTranslator.Result r = CobolToJavaTranslator.translate(READ_AT_END_PROG);
        assertTrue(r.javaSource().contains("if (!__ok_"),
                () -> r.javaSource());
        assertTrue(r.javaSource().contains("__ok_INDD") || r.javaSource().contains("__ok_INFILE"),
                "ASSIGN INDD or logical INFILE expected:\n" + r.javaSource());
        assertTrue(r.javaSource().contains("__openInput(\"INDD\")")
                        || r.javaSource().contains("__read(\"INDD\""),
                "SELECT ASSIGN TO INDD should drive dd name:\n" + r.javaSource());
        assertJavacOk(r.javaSource(), r.className());
    }

    @Test
    void unresolved_call_target_is_graph_gap(@TempDir Path tmp) throws Exception {
        String orphan = DRIVER.replace("CALL 'WORKER'", "CALL 'MISSING'");
        Files.writeString(tmp.resolve("DRIVER.cob"), orphan, StandardCharsets.UTF_8);
        CobolProgramGraph graph = CobolProgramGraph.discover(tmp);
        assertTrue(graph.gaps().stream().anyMatch(g -> g.contains("MISSING")),
                () -> String.valueOf(graph.gaps()));
    }

    private static void assertJavacOk(String javaSource, String className) throws Exception {
        Path tmp = Files.createTempDirectory("cobol-phase6-javac-");
        Path javaFile = tmp.resolve(className + ".java");
        Files.writeString(javaFile, javaSource, StandardCharsets.UTF_8);
        String javac = System.getProperty("shadowstack.verify.javac", "javac");
        Process p = new ProcessBuilder(javac, javaFile.toString())
                .directory(tmp.toFile())
                .redirectErrorStream(true)
                .start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(p.waitFor(60, TimeUnit.SECONDS), "javac timed out");
        assertEquals(0, p.exitValue(), () -> "javac failed:\n" + out + "\n---\n" + javaSource);
    }

    private static Path resolveExample(String relativeUnderLegacyCobol) {
        Path cwd = Path.of("").toAbsolutePath().normalize();
        Path[] candidates = {
                cwd.resolve("examples/legacy-cobol/" + relativeUnderLegacyCobol),
                cwd.resolve("../examples/legacy-cobol/" + relativeUnderLegacyCobol),
                cwd.resolve("../../examples/legacy-cobol/" + relativeUnderLegacyCobol),
                cwd.resolve("../../../examples/legacy-cobol/" + relativeUnderLegacyCobol)
        };
        for (Path p : candidates) {
            Path n = p.normalize();
            if (Files.isRegularFile(n)) return n;
        }
        return null;
    }
}
