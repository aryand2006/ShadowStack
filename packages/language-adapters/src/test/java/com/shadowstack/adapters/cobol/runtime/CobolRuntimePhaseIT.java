package com.shadowstack.adapters.cobol.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class CobolRuntimePhaseIT {

    @Test
    void sequential_file_facade_round_trip(@TempDir Path tmp) throws Exception {
        CobolFileFacade files = new SequentialCobolFileFacade(tmp);
        files.openOutput("OUTDD");
        files.write("OUTDD", "HELLO".getBytes(StandardCharsets.UTF_8));
        files.close("OUTDD");

        files.openInput("OUTDD");
        byte[] buf = new byte[32];
        assertTrue(files.read("OUTDD", buf));
        assertEquals("HELLO", new String(buf, 0, 5, StandardCharsets.UTF_8));
        assertFalse(files.read("OUTDD", buf));
        files.close("OUTDD");
    }

    @Test
    void rewrite_after_read_on_io() throws Exception {
        Path tmp = Files.createTempDirectory("cobol-rewrite-");
        Files.writeString(tmp.resolve("IODD.dat"), "ORIGINAL\n", StandardCharsets.UTF_8);
        SequentialCobolFileFacade files = new SequentialCobolFileFacade(tmp);
        files.openIo("IODD");
        byte[] buf = new byte[16];
        assertTrue(files.read("IODD", buf));
        files.rewrite("IODD", "UPDATED!".getBytes(StandardCharsets.UTF_8));
        files.close("IODD");
        String body = Files.readString(tmp.resolve("IODD.dat"), StandardCharsets.UTF_8);
        assertTrue(body.startsWith("UPDATED!"), body);
    }

    @Test
    void rewrite_without_io_fail_closed() {
        CobolFileFacade files = new SequentialCobolFileFacade(Path.of("."));
        assertThrows(UnsupportedCobolFeatureException.class,
                () -> files.rewrite("X", new byte[0]));
    }

    @Test
    void cics_and_ims_default_fail_closed() {
        CicsFacade cics = new CicsFacade() {};
        ImsFacade ims = new ImsFacade() {};
        assertThrows(UnsupportedCobolFeatureException.class, () -> cics.link("PGM1"));
        assertThrows(UnsupportedCobolFeatureException.class, () -> ims.gu("PCB1", new byte[0]));
    }

    @Test
    void jcl_parser_builds_step_graph() {
        String jcl = """
                //PAYJOB JOB CLASS=A
                //STEP1 EXEC PGM=PAYROLL
                //INDD DD DSN=PAY.IN,DISP=SHR
                //STEP2 EXEC PGM=REPORT
                //OUTDD DD DSN=PAY.OUT,DISP=NEW
                //INCLUDE MEMBER=FOO
                """;
        JclJobGraph graph = JclJobGraph.parse(jcl);
        assertEquals("PAYJOB", graph.jobName());
        assertEquals(2, graph.steps().size());
        assertEquals("PAYROLL", graph.programByStep().get("STEP1"));
        assertFalse(graph.gaps().isEmpty(), "INCLUDE should be a gap");
        assertEquals(java.util.List.of("STEP1", "STEP2"), graph.executionOrder());
    }

    @Test
    void batch_sample_emits_file_helpers() throws Exception {
        String cobol = """
                >>SOURCE FREE
                IDENTIFICATION DIVISION.
                PROGRAM-ID. BATCHIO.
                ENVIRONMENT DIVISION.
                INPUT-OUTPUT SECTION.
                FILE-CONTROL.
                    SELECT OUTFILE ASSIGN TO OUTDD.
                DATA DIVISION.
                FILE SECTION.
                FD OUTFILE.
                01 OUTREC PIC X(20).
                WORKING-STORAGE SECTION.
                01 WS-LINE PIC X(20) VALUE "ROW1".
                PROCEDURE DIVISION.
                MAIN.
                    OPEN OUTPUT OUTFILE.
                    WRITE OUTREC FROM WS-LINE.
                    CLOSE OUTFILE.
                    STOP RUN.
                """;
        var r = com.shadowstack.adapters.cobol.CobolToJavaTranslator.translate(cobol);
        assertTrue(r.javaSource().contains("__openOutput"), r.javaSource());
        assertTrue(r.javaSource().contains("__write(\"OUTDD\""), r.javaSource());
        assertTrue(r.unsupportedGaps().isEmpty(), () -> String.valueOf(r.unsupportedGaps()));
    }
}
