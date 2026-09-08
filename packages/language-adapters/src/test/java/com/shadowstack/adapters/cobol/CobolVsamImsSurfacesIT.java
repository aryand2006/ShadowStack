package com.shadowstack.adapters.cobol;

import com.shadowstack.adapters.cobol.runtime.IndexedInMemoryFileFacade;
import com.shadowstack.adapters.cobol.runtime.JclJobGraph;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class CobolVsamImsSurfacesIT {

    @Test
    void indexed_select_emits_idx_helpers_and_round_trips(@TempDir Path tmp) throws Exception {
        String cobol = """
                >>SOURCE FREE
                IDENTIFICATION DIVISION.
                PROGRAM-ID. IDXDEMO.
                ENVIRONMENT DIVISION.
                INPUT-OUTPUT SECTION.
                FILE-CONTROL.
                    SELECT IDXFILE ASSIGN TO IDXDD
                        ORGANIZATION IS INDEXED
                        RECORD KEY IS WS-KEY.
                DATA DIVISION.
                FILE SECTION.
                FD IDXFILE.
                01 IDXREC PIC X(32).
                WORKING-STORAGE SECTION.
                01 WS-KEY PIC X(8) VALUE "ALPHA".
                01 WS-VAL PIC X(16) VALUE "PAYLOAD-1".
                PROCEDURE DIVISION.
                MAIN.
                    OPEN OUTPUT IDXFILE.
                    WRITE IDXREC FROM WS-VAL.
                    CLOSE IDXFILE.
                    OPEN INPUT IDXFILE.
                    READ IDXFILE KEY IS WS-KEY INTO WS-VAL.
                    DISPLAY WS-VAL.
                    CLOSE IDXFILE.
                    STOP RUN.
                """;
        var r = CobolToJavaTranslator.translate(cobol);
        assertTrue(r.javaSource().contains("__idxOpen"), r.javaSource());
        assertTrue(r.javaSource().contains("__idxWrite"), r.javaSource());
        assertTrue(r.unsupportedGaps().stream().noneMatch(g -> g.contains("VSAM")),
                () -> String.valueOf(r.unsupportedGaps()));

        Path javaFile = tmp.resolve(r.className() + ".java");
        Files.writeString(javaFile, r.javaSource(), StandardCharsets.UTF_8);
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
        assertTrue(stdout.contains("PAYLOAD-1"), stdout);
        assertTrue(Files.isRegularFile(tmp.resolve("IDXDD.idxdat")));
    }

    @Test
    void dynamic_call_emits_class_for_name() {
        String cobol = """
                >>SOURCE FREE
                IDENTIFICATION DIVISION.
                PROGRAM-ID. DYNDEMO.
                DATA DIVISION.
                WORKING-STORAGE SECTION.
                01 WS-PGM PIC X(8) VALUE "WORKER".
                PROCEDURE DIVISION.
                MAIN.
                    CALL WS-PGM.
                    STOP RUN.
                """;
        var r = CobolToJavaTranslator.translate(cobol);
        assertTrue(r.javaSource().contains("Class.forName"), r.javaSource());
        assertTrue(r.javaSource().contains("Translated\" + __dyn"), r.javaSource());
    }

    @Test
    void nested_program_id_recorded_as_gap() {
        String cobol = """
                >>SOURCE FREE
                IDENTIFICATION DIVISION.
                PROGRAM-ID. OUTER.
                PROCEDURE DIVISION.
                MAIN.
                    DISPLAY "OUTER".
                    STOP RUN.
                IDENTIFICATION DIVISION.
                PROGRAM-ID. INNER.
                PROCEDURE DIVISION.
                MAIN2.
                    DISPLAY "INNER".
                    STOP RUN.
                """;
        var r = CobolToJavaTranslator.translate(cobol);
        assertTrue(r.unsupportedGaps().stream().anyMatch(g -> g.contains("Nested PROGRAM-ID")),
                () -> String.valueOf(r.unsupportedGaps()));
    }

    @Test
    void exec_dli_gu_emits_ims_helper() {
        String cobol = """
                >>SOURCE FREE
                IDENTIFICATION DIVISION.
                PROGRAM-ID. IMSDEMO.
                DATA DIVISION.
                WORKING-STORAGE SECTION.
                01 WS-SEG PIC X(20).
                PROCEDURE DIVISION.
                MAIN.
                    EXEC DLI GU PCB(PCB1) INTO(WS-SEG) END-EXEC.
                    STOP RUN.
                """;
        var r = CobolToJavaTranslator.translate(cobol);
        assertTrue(r.javaSource().contains("__imsGu(\"PCB1\")"), r.javaSource());
    }

    @Test
    void indexed_facade_key_read(@TempDir Path tmp) {
        IndexedInMemoryFileFacade files = new IndexedInMemoryFileFacade(tmp);
        files.openOutput("KDD");
        files.writeKey("KDD", "A1", "ONE".getBytes(StandardCharsets.UTF_8));
        files.close("KDD");
        files.openInput("KDD");
        byte[] buf = new byte[8];
        assertTrue(files.readKey("KDD", "A1", buf));
        assertEquals("ONE", new String(buf, 0, 3, StandardCharsets.UTF_8));
        files.close("KDD");
    }

    @Test
    void jcl_include_expands_member(@TempDir Path tmp) throws Exception {
        Files.writeString(tmp.resolve("STEPLIB.jcl"),
                "//STEP010 EXEC PGM=BATCHIO\n//OUTFILE DD DSN=X,DISP=NEW\n",
                StandardCharsets.UTF_8);
        String jcl = "//PAYJOB JOB CLASS=A\n// INCLUDE MEMBER=STEPLIB\n";
        JclJobGraph graph = JclJobGraph.parse(jcl, tmp);
        assertEquals("PAYJOB", graph.jobName());
        assertEquals(1, graph.steps().size());
        assertEquals("BATCHIO", graph.steps().get(0).program());
        assertTrue(graph.gaps().stream().noneMatch(g -> g.contains("INCLUDE unresolved")),
                () -> String.valueOf(graph.gaps()));
    }
}
