package com.shadowstack.adapters.cobol.runtime;

import com.shadowstack.adapters.cobol.CobolToJavaTranslator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class JclJobRunnerIT {

    static final String BATCH_COBOL = """
            >>SOURCE FREE
            IDENTIFICATION DIVISION.
            PROGRAM-ID. BATCHIO.
            ENVIRONMENT DIVISION.
            INPUT-OUTPUT SECTION.
            FILE-CONTROL.
                SELECT OUTFILE ASSIGN TO OUTFILE.
            DATA DIVISION.
            FILE SECTION.
            FD OUTFILE.
            01 OUTREC PIC X(24).
            WORKING-STORAGE SECTION.
            01 WS-LINE PIC X(24) VALUE "JCL-RUNNER-OK".
            PROCEDURE DIVISION.
            MAIN.
                OPEN OUTPUT OUTFILE.
                WRITE OUTREC FROM WS-LINE.
                CLOSE OUTFILE.
                DISPLAY WS-LINE.
                STOP RUN.
            """;

    @Test
    void runs_paydemo_step_when_translated_class_present(@TempDir Path tmp) throws Exception {
        CobolToJavaTranslator.Result r = CobolToJavaTranslator.translate(BATCH_COBOL);
        Path javaFile = tmp.resolve(r.className() + ".java");
        Files.writeString(javaFile, r.javaSource(), StandardCharsets.UTF_8);
        Process compile = new ProcessBuilder("javac", javaFile.getFileName().toString())
                .directory(tmp.toFile())
                .redirectErrorStream(true)
                .start();
        String compileOut = new String(compile.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(compile.waitFor(60, TimeUnit.SECONDS));
        assertEquals(0, compile.exitValue(), () -> compileOut);

        Path work = tmp.resolve("work");
        Files.createDirectories(work);
        String jcl = """
                //PAYDEMO JOB CLASS=A
                //STEP010 EXEC PGM=BATCHIO
                //OUTFILE DD DSN=SHADOW.BATCH.OUT,DISP=(NEW,CATLG)
                """;
        JclJobGraph graph = JclJobGraph.parse(jcl);
        JclJobRunner runner = new JclJobRunner(
                work, tmp, JclJobRunner.defaultTranslatedResolver(tmp));
        JclJobRunner.RunResult result = runner.run(graph);
        assertEquals(1, result.steps().size());
        assertTrue(result.steps().get(0).ran());
        assertEquals(0, result.steps().get(0).exitCode(),
                () -> result.steps().get(0).stdout());
        assertTrue(Files.isRegularFile(work.resolve("OUTFILE.dat")));
        assertTrue(Files.readString(work.resolve("OUTFILE.dat")).contains("JCL-RUNNER-OK"));
    }

    @Test
    void missing_program_is_fail_closed(@TempDir Path tmp) throws Exception {
        JclJobGraph graph = JclJobGraph.parse("//J JOB\n//S EXEC PGM=MISSING\n");
        JclJobRunner runner = new JclJobRunner(
                tmp, tmp, JclJobRunner.defaultTranslatedResolver(tmp));
        JclJobRunner.RunResult result = runner.run(graph);
        assertFalse(result.allSucceeded());
        assertFalse(result.steps().get(0).ran());
        assertTrue(result.steps().get(0).gaps().stream()
                .anyMatch(g -> g.contains("Missing translated program")));
    }
}
