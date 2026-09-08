package com.shadowstack.adapters.cobol.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Focused coverage for BMS façade MVP and JCL EXEC PROC= expand.
 * Not bit-identical IBM BMS/JCL.
 */
class CobolBmsJclProcIT {

    @Test
    void bms_facade_defaults_fail_closed() {
        BmsFacade bms = new BmsFacade() {};
        assertThrows(UnsupportedCobolFeatureException.class,
                () -> bms.sendMap("MAP1", "DATA"));
        assertThrows(UnsupportedCobolFeatureException.class,
                () -> bms.receiveMap("MAP1"));
    }

    @Test
    void bms_in_memory_send_receive_round_trip() {
        BmsFacade bms = new InMemoryBmsFacade();
        bms.sendMap("CUSTMAP", "NAME=ADA");
        assertEquals("NAME=ADA", bms.receiveMap("CUSTMAP"));
        assertEquals("", bms.receiveMap("MISSING"));
    }

    @Test
    void jcl_exec_proc_expands_from_proc_file(@TempDir Path tmp) throws Exception {
        Files.writeString(tmp.resolve("PAYPROC.proc"), """
                //PAYPROC PROC
                //STEP010 EXEC PGM=PAYROLL
                //INDD DD DSN=PAY.IN,DISP=SHR
                """, StandardCharsets.UTF_8);
        String jcl = "//PAYJOB JOB CLASS=A\n//STEPX EXEC PROC=PAYPROC\n";
        JclJobGraph graph = JclJobGraph.parse(jcl, tmp);
        assertEquals("PAYJOB", graph.jobName());
        assertEquals(1, graph.steps().size());
        assertEquals("PAYROLL", graph.steps().get(0).program());
        assertEquals("STEP010", graph.steps().get(0).stepName());
        assertTrue(graph.gaps().stream().noneMatch(g -> g.contains("PROC")),
                () -> String.valueOf(graph.gaps()));
    }

    @Test
    void jcl_named_exec_proc_expands_from_jcl_member(@TempDir Path tmp) throws Exception {
        Files.writeString(tmp.resolve("REPORT"), """
                //STEP020 EXEC PGM=REPORT
                //OUTDD DD DSN=PAY.OUT,DISP=NEW
                """, StandardCharsets.UTF_8);
        String jcl = "//JOB1 JOB\n//CALLREP EXEC PROC=REPORT\n";
        JclJobGraph graph = JclJobGraph.parse(jcl, tmp);
        assertEquals(1, graph.steps().size());
        assertEquals("REPORT", graph.programByStep().get("STEP020"));
    }

    @Test
    void jcl_unresolved_proc_is_gap(@TempDir Path tmp) {
        String jcl = "//JOB1 JOB\n//S1 EXEC PROC=MISSING\n";
        JclJobGraph graph = JclJobGraph.parse(jcl, tmp);
        assertTrue(graph.steps().isEmpty());
        assertTrue(graph.gaps().stream().anyMatch(g -> g.contains("PROC") && g.contains("MISSING")),
                () -> String.valueOf(graph.gaps()));
    }

    @Test
    void jcl_include_still_expands(@TempDir Path tmp) throws Exception {
        Files.writeString(tmp.resolve("STEPLIB.jcl"),
                "//STEP010 EXEC PGM=BATCHIO\n",
                StandardCharsets.UTF_8);
        String jcl = "//PAYJOB JOB CLASS=A\n// INCLUDE MEMBER=STEPLIB\n";
        JclJobGraph graph = JclJobGraph.parse(jcl, tmp);
        assertEquals(1, graph.steps().size());
        assertEquals("BATCHIO", graph.steps().get(0).program());
    }
}
