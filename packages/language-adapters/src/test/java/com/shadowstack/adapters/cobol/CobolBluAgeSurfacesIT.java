package com.shadowstack.adapters.cobol;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Blu Age deep-surface coverage: LINKAGE, PERFORM THRU, CICS/SQL emit, SORT.
 */
class CobolBluAgeSurfacesIT {

    @Test
    void perform_thru_emits_full_paragraph_range() {
        String cobol = """
                >>SOURCE FREE
                IDENTIFICATION DIVISION.
                PROGRAM-ID. THRUDEMO.
                PROCEDURE DIVISION.
                MAIN.
                    PERFORM A THRU C.
                    STOP RUN.
                A.
                    DISPLAY "A".
                B.
                    DISPLAY "B".
                C.
                    DISPLAY "C".
                """;
        var r = CobolToJavaTranslator.translate(cobol);
        String java = r.javaSource();
        assertTrue(java.contains("a();"), java);
        assertTrue(java.contains("b();"), java);
        assertTrue(java.contains("c();"), java);
        assertTrue(r.unsupportedGaps().stream().noneMatch(g -> g.contains("PERFORM THRU partial")),
                () -> String.valueOf(r.unsupportedGaps()));
    }

    @Test
    void linkage_section_binds_main_args() {
        String cobol = """
                >>SOURCE FREE
                IDENTIFICATION DIVISION.
                PROGRAM-ID. LINKDEMO.
                DATA DIVISION.
                LINKAGE SECTION.
                01 LK-ARG PIC X(8).
                PROCEDURE DIVISION USING LK-ARG.
                MAIN.
                    DISPLAY LK-ARG.
                    GOBACK.
                """;
        var r = CobolToJavaTranslator.translate(cobol);
        String java = r.javaSource();
        assertTrue(java.contains("__bindLinkage"), java);
        assertTrue(java.contains("__writebackLinkage"), java);
        assertTrue(java.contains("LK_ARG = __in") || java.contains("LK_ARG = args["), java);
    }

    @Test
    void exec_cics_link_emits_helper() {
        String cobol = """
                >>SOURCE FREE
                IDENTIFICATION DIVISION.
                PROGRAM-ID. CICDEMO.
                PROCEDURE DIVISION.
                MAIN.
                    EXEC CICS LINK PROGRAM(WORKER) END-EXEC.
                    STOP RUN.
                """;
        var r = CobolToJavaTranslator.translate(cobol);
        assertTrue(r.javaSource().contains("__cicsLink(\"WORKER\")"), r.javaSource());
        assertTrue(r.unsupportedGaps().stream().noneMatch(g -> g.contains("CICS verb (use CicsFacade)")),
                () -> String.valueOf(r.unsupportedGaps()));
    }

    @Test
    void sort_using_giving_emits_helper_and_runs(@TempDir Path tmp) throws Exception {
        String cobol = """
                >>SOURCE FREE
                IDENTIFICATION DIVISION.
                PROGRAM-ID. SORTDEMO.
                PROCEDURE DIVISION.
                MAIN.
                    SORT WORKFILE USING INDD GIVING OUTDD.
                    STOP RUN.
                """;
        var r = CobolToJavaTranslator.translate(cobol);
        assertTrue(r.javaSource().contains("__sortLines(\"INDD\", \"OUTDD\")"), r.javaSource());

        Path javaFile = tmp.resolve(r.className() + ".java");
        Files.writeString(javaFile, r.javaSource(), StandardCharsets.UTF_8);
        Files.writeString(tmp.resolve("INDD.dat"), "c\\nb\\na\\n".replace("\\n", "\n"), StandardCharsets.UTF_8);

        Process compile = new ProcessBuilder("javac", javaFile.getFileName().toString())
                .directory(tmp.toFile()).redirectErrorStream(true).start();
        String compileOut = new String(compile.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(compile.waitFor(60, TimeUnit.SECONDS));
        assertEquals(0, compile.exitValue(), () -> compileOut);

        Process run = new ProcessBuilder("java", r.className())
                .directory(tmp.toFile()).redirectErrorStream(true).start();
        String stdout = new String(run.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(run.waitFor(60, TimeUnit.SECONDS));
        assertEquals(0, run.exitValue(), () -> stdout);
        assertEquals("a\nb\nc\n", Files.readString(tmp.resolve("OUTDD.dat")));
    }

    @Test
    void exec_sql_emits_fail_closed_stub() {
        String cobol = """
                >>SOURCE FREE
                IDENTIFICATION DIVISION.
                PROGRAM-ID. SQLDEMO.
                PROCEDURE DIVISION.
                MAIN.
                    EXEC SQL SELECT 1 FROM DUAL END-EXEC.
                    STOP RUN.
                """;
        var r = CobolToJavaTranslator.translate(cobol);
        assertTrue(r.javaSource().contains("__sqlExec("), r.javaSource());
    }
}
