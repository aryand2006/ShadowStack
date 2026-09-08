package com.shadowstack.adapters.cobol;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Completes remaining implementable Blu Age gaps: nested PROGRAM-ID emit,
 * CALL BY REFERENCE heap, BMS SEND/RECEIVE MAP, deep FD OCCURS.
 */
class CobolCompleteGapsIT {

    @Test
    void nested_program_id_emits_sibling_classes() {
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
        String java = r.javaSource();
        assertTrue(java.contains("public class TranslatedOUTER"), java);
        assertTrue(java.contains("class TranslatedINNER"), java);
        assertTrue(r.resolvedCalls().stream().anyMatch(c -> c.contains("NESTED:INNER")),
                () -> String.valueOf(r.resolvedCalls()));
        assertTrue(r.unsupportedGaps().stream().noneMatch(g -> g.contains("Nested PROGRAM-ID")),
                () -> String.valueOf(r.unsupportedGaps()));
    }

    @Test
    void call_by_reference_uses_shared_heap_and_writeback(@TempDir Path tmp) throws Exception {
        String driver = """
                >>SOURCE FREE
                IDENTIFICATION DIVISION.
                PROGRAM-ID. REFDRV.
                DATA DIVISION.
                WORKING-STORAGE SECTION.
                01 WS-ARG PIC X(8) VALUE "BEFORE".
                PROCEDURE DIVISION.
                MAIN.
                    CALL 'REFWKR' USING BY REFERENCE WS-ARG.
                    DISPLAY WS-ARG.
                    STOP RUN.
                """;
        String worker = """
                >>SOURCE FREE
                IDENTIFICATION DIVISION.
                PROGRAM-ID. REFWKR.
                DATA DIVISION.
                LINKAGE SECTION.
                01 LK-ARG PIC X(8).
                PROCEDURE DIVISION USING LK-ARG.
                MAIN.
                    MOVE "AFTER" TO LK-ARG.
                    GOBACK.
                """;
        Files.writeString(tmp.resolve("REFDRV.cob"), driver, StandardCharsets.UTF_8);
        Files.writeString(tmp.resolve("REFWKR.cob"), worker, StandardCharsets.UTF_8);

        CobolProjectTranslator.Summary summary = CobolProjectTranslator.translateAll(tmp);
        var drv = summary.results().get("REFDRV");
        var wkr = summary.results().get("REFWKR");
        assertNotNull(drv);
        assertNotNull(wkr);
        assertTrue(drv.javaSource().contains("__refPut"), drv.javaSource());
        assertTrue(drv.javaSource().contains("__refGet"), drv.javaSource());
        assertTrue(drv.javaSource().contains("__ref:WS_ARG") || drv.javaSource().contains("\"__ref:WS_ARG\""),
                drv.javaSource());
        assertTrue(wkr.javaSource().contains("__refGet"), wkr.javaSource());
        assertTrue(wkr.javaSource().contains("__writebackLinkage"), wkr.javaSource());

        Path drvJava = tmp.resolve("TranslatedREFDRV.java");
        Path wkrJava = tmp.resolve("TranslatedREFWKR.java");
        assertTrue(Files.isRegularFile(drvJava));
        assertTrue(Files.isRegularFile(wkrJava));

        Process compile = new ProcessBuilder("javac",
                drvJava.getFileName().toString(), wkrJava.getFileName().toString())
                .directory(tmp.toFile()).redirectErrorStream(true).start();
        String compileOut = new String(compile.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(compile.waitFor(60, TimeUnit.SECONDS));
        assertEquals(0, compile.exitValue(), compileOut);

        Process run = new ProcessBuilder("java", "TranslatedREFDRV")
                .directory(tmp.toFile()).redirectErrorStream(true).start();
        String runOut = new String(run.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(run.waitFor(30, TimeUnit.SECONDS));
        assertEquals(0, run.exitValue(), runOut);
        assertTrue(runOut.contains("AFTER"), runOut);
    }

    @Test
    void call_by_content_does_not_write_back_caller() {
        String cobol = """
                >>SOURCE FREE
                IDENTIFICATION DIVISION.
                PROGRAM-ID. CONTENTD.
                DATA DIVISION.
                WORKING-STORAGE SECTION.
                01 WS-ARG PIC X(8) VALUE "KEEPME".
                PROCEDURE DIVISION.
                MAIN.
                    CALL 'ANYONE' USING BY CONTENT WS-ARG.
                    STOP RUN.
                """;
        var r = CobolToJavaTranslator.translate(cobol);
        assertTrue(r.javaSource().contains("BY CONTENT: caller copy not updated"), r.javaSource());
        assertFalse(r.javaSource().contains("__refPut(\"WS_ARG\""), r.javaSource());
    }

    @Test
    void exec_cics_send_receive_map_emits_bms_helpers() {
        String cobol = """
                >>SOURCE FREE
                IDENTIFICATION DIVISION.
                PROGRAM-ID. BMSDEMO.
                DATA DIVISION.
                WORKING-STORAGE SECTION.
                01 WS-MAP PIC X(20) VALUE "HELLO".
                PROCEDURE DIVISION.
                MAIN.
                    EXEC CICS SEND MAP(CUSTMAP) FROM(WS-MAP) END-EXEC.
                    EXEC CICS RECEIVE MAP(CUSTMAP) INTO(WS-MAP) END-EXEC.
                    STOP RUN.
                """;
        var r = CobolToJavaTranslator.translate(cobol);
        String java = r.javaSource();
        assertTrue(java.contains("__bmsSendMap(\"CUSTMAP\""), java);
        assertTrue(java.contains("__bmsReceiveMap(\"CUSTMAP\")"), java);
        assertTrue(java.contains("private static void __bmsSendMap"), java);
    }

    @Test
    void deep_fd_occurs_binds_subordinates_and_sums_length() {
        String cobol = """
                >>SOURCE FREE
                IDENTIFICATION DIVISION.
                PROGRAM-ID. DEEPFD.
                ENVIRONMENT DIVISION.
                INPUT-OUTPUT SECTION.
                FILE-CONTROL.
                    SELECT OUTFILE ASSIGN TO OUTDD.
                DATA DIVISION.
                FILE SECTION.
                FD OUTFILE.
                01 OUTREC.
                   05 OUT-CODE PIC X(4).
                   05 OUT-AMT PIC 9(4) OCCURS 3 TIMES.
                PROCEDURE DIVISION.
                MAIN.
                    OPEN OUTPUT OUTFILE.
                    WRITE OUTREC.
                    CLOSE OUTFILE.
                    STOP RUN.
                """;
        var r = CobolToJavaTranslator.translate(cobol);
        assertTrue(r.isTransformative());
        String java = r.javaSource();
        assertTrue(java.contains("__write(\"OUTDD\""), java);
        assertTrue(java.contains("OUT_CODE"), java);
        assertTrue(java.contains("OUT_AMT"), java);
        assertTrue(java.contains("OCCURS 3") || java.contains("new int[3]"), java);
    }
}
