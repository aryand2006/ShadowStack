package com.shadowstack.adapters.cobol;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 2 deepen: FD/01 record layouts, WRITE via record→FD→ASSIGN, OPEN I-O + REWRITE.
 */
class CobolPhase2FdIT {

    static final String BATCH =
            ">>SOURCE FREE\n" +
            "IDENTIFICATION DIVISION.\n" +
            "PROGRAM-ID. BATCHIO.\n" +
            "ENVIRONMENT DIVISION.\n" +
            "INPUT-OUTPUT SECTION.\n" +
            "FILE-CONTROL.\n" +
            "    SELECT OUTFILE ASSIGN TO OUTDD.\n" +
            "DATA DIVISION.\n" +
            "FILE SECTION.\n" +
            "FD OUTFILE.\n" +
            "01 OUTREC PIC X(32).\n" +
            "WORKING-STORAGE SECTION.\n" +
            "01 WS-LINE PIC X(32) VALUE \"SHADOWSTACK-BATCH\".\n" +
            "PROCEDURE DIVISION.\n" +
            "MAIN.\n" +
            "    OPEN OUTPUT OUTFILE.\n" +
            "    WRITE OUTREC FROM WS-LINE.\n" +
            "    CLOSE OUTFILE.\n" +
            "    DISPLAY WS-LINE.\n" +
            "    STOP RUN.\n";

    static final String REWRITE_PROG =
            ">>SOURCE FREE\n" +
            "IDENTIFICATION DIVISION.\n" +
            "PROGRAM-ID. REWDEMO.\n" +
            "ENVIRONMENT DIVISION.\n" +
            "INPUT-OUTPUT SECTION.\n" +
            "FILE-CONTROL.\n" +
            "    SELECT IOFILE ASSIGN TO IODD.\n" +
            "DATA DIVISION.\n" +
            "FILE SECTION.\n" +
            "FD IOFILE.\n" +
            "01 IOREC PIC X(16).\n" +
            "WORKING-STORAGE SECTION.\n" +
            "01 WS-NEW PIC X(16) VALUE \"UPDATED\".\n" +
            "PROCEDURE DIVISION.\n" +
            "MAIN.\n" +
            "    OPEN I-O IOFILE.\n" +
            "    READ IOFILE INTO IOREC.\n" +
            "    REWRITE IOREC FROM WS-NEW.\n" +
            "    CLOSE IOFILE.\n" +
            "    STOP RUN.\n";

    @Test
    void fd_record_write_resolves_to_assign_dd() {
        CobolToJavaTranslator.Result r = CobolToJavaTranslator.translate(BATCH);
        assertTrue(r.isTransformative());
        String java = r.javaSource();
        assertTrue(java.contains("__openOutput(\"OUTDD\""), java);
        assertTrue(java.contains("__write(\"OUTDD\""), java);
        assertTrue(java.contains("OUTREC") || java.contains("PIC X(32)"), java);
        assertFalse(java.contains("__write(\"OUTREC\""), java);
    }

    @Test
    void pic_byte_length_x32() {
        assertEquals(32, CobolToJavaTranslator.picByteLength("X(32)"));
        assertEquals(4, CobolToJavaTranslator.picByteLength("9(4)"));
    }

    @Test
    void open_io_and_rewrite_emit_helpers() {
        CobolToJavaTranslator.Result r = CobolToJavaTranslator.translate(REWRITE_PROG);
        String java = r.javaSource();
        assertTrue(java.contains("__openIo(\"IODD\")"), java);
        assertTrue(java.contains("__rewrite(\"IODD\""), java);
        assertTrue(java.contains("__readIo"), java);
        assertFalse(r.unsupportedGaps().stream().anyMatch(g -> g.contains("REWRITE")),
                () -> String.valueOf(r.unsupportedGaps()));
    }

    @Test
    void batch_javac_writes_outdd_dat(@TempDir Path tmp) throws Exception {
        CobolToJavaTranslator.Result r = CobolToJavaTranslator.translate(BATCH);
        Path javaFile = tmp.resolve(r.className() + ".java");
        Files.writeString(javaFile, r.javaSource(), StandardCharsets.UTF_8);

        Process compile = new ProcessBuilder("javac", javaFile.getFileName().toString())
                .directory(tmp.toFile())
                .redirectErrorStream(true)
                .start();
        String compileOut = new String(compile.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(compile.waitFor(60, TimeUnit.SECONDS));
        assertEquals(0, compile.exitValue(), () -> "javac failed:\n" + compileOut);

        Process run = new ProcessBuilder("java", r.className())
                .directory(tmp.toFile())
                .redirectErrorStream(true)
                .start();
        String stdout = new String(run.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(run.waitFor(60, TimeUnit.SECONDS));
        assertEquals(0, run.exitValue(), () -> "java failed:\n" + stdout);

        Path dat = tmp.resolve("OUTDD.dat");
        assertTrue(Files.isRegularFile(dat), "expected OUTDD.dat");
        String body = Files.readString(dat, StandardCharsets.UTF_8);
        assertTrue(body.contains("SHADOWSTACK-BATCH"), body);
    }
}
