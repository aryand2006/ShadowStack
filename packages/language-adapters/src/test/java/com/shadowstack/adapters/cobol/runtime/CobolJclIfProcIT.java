package com.shadowstack.adapters.cobol.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MVP coverage for JCL IF/THEN/ELSE/ENDIF cond annotation and PROC symbolics.
 * Not bit-identical IBM JCL.
 */
class CobolJclIfProcIT {

    @Test
    void if_then_else_attaches_cond_on_steps() {
        String jcl = """
                //PAYJOB JOB CLASS=A
                //STEP010 EXEC PGM=INIT
                // IF (RC = 0) THEN
                //STEP020 EXEC PGM=PAYROLL
                //INDD DD DSN=PAY.IN,DISP=SHR
                // ELSE
                //STEP030 EXEC PGM=ABEND
                // ENDIF
                //STEP040 EXEC PGM=FINAL
                """;
        JclJobGraph graph = JclJobGraph.parse(jcl);
        assertEquals("PAYJOB", graph.jobName());
        assertEquals(4, graph.steps().size());
        assertNull(graph.steps().get(0).cond());
        assertEquals("IF:RC=0", graph.steps().get(1).cond());
        assertEquals("PAYROLL", graph.steps().get(1).program());
        assertEquals("IF:RC!=0", graph.steps().get(2).cond());
        assertEquals("ABEND", graph.steps().get(2).program());
        assertNull(graph.steps().get(3).cond());
        assertTrue(graph.gaps().stream().noneMatch(g -> g.contains("IF/THEN")),
                () -> String.valueOf(graph.gaps()));
        assertEquals(
                java.util.List.of("STEP010", "STEP020", "STEP030", "STEP040"),
                graph.executionOrder());
    }

    @Test
    void named_if_endif_without_else() {
        String jcl = """
                //J1 JOB
                //CHK01 IF (RC = 4) THEN
                //S2 EXEC PGM=RETRY
                //CHK01 ENDIF
                """;
        JclJobGraph graph = JclJobGraph.parse(jcl);
        assertEquals(1, graph.steps().size());
        assertEquals("IF:RC=4", graph.steps().get(0).cond());
        assertEquals("RETRY", graph.steps().get(0).program());
    }

    @Test
    void invert_simple_rc_helpers() {
        assertEquals("RC=0", JclJobGraph.extractIfExpression("// IF (RC = 0) THEN"));
        assertEquals("RC!=0", JclJobGraph.invertSimpleRc("RC=0"));
        assertEquals("RC=4", JclJobGraph.invertSimpleRc("RC!=4"));
        assertEquals("NOT(ABEND)", JclJobGraph.invertSimpleRc("ABEND"));
    }

    @Test
    void proc_symbolic_overrides_substitute_in_body(@TempDir Path tmp) throws Exception {
        Files.writeString(tmp.resolve("PAYPROC.proc"), """
                //PAYPROC PROC PGMNAME=DEFAULT,DSNIN=PAY.DEFAULT
                //STEP010 EXEC PGM=&PGMNAME
                //INDD DD DSN=&DSNIN,DISP=SHR
                // PEND
                """, StandardCharsets.UTF_8);
        String jcl = "//PAYJOB JOB\n//CALL EXEC PROC=PAYPROC,PGMNAME=PAYROLL,DSNIN=PAY.IN\n";
        JclJobGraph graph = JclJobGraph.parse(jcl, tmp);
        assertEquals(1, graph.steps().size());
        assertEquals("PAYROLL", graph.steps().get(0).program());
        assertEquals("PAY.IN", graph.steps().get(0).dds().get(0).dsn());
        assertTrue(graph.gaps().stream().noneMatch(g -> g.contains("unresolved symbolic")),
                () -> String.valueOf(graph.gaps()));
    }

    @Test
    void proc_default_symbolics_and_double_ampersand(@TempDir Path tmp) throws Exception {
        Files.writeString(tmp.resolve("UTIL.proc"), """
                //UTIL PROC PROG=IEFBR14
                //S1 EXEC PGM=&&PROG
                """, StandardCharsets.UTF_8);
        String jcl = "//J JOB\n//X EXEC PROC=UTIL\n";
        JclJobGraph graph = JclJobGraph.parse(jcl, tmp);
        assertEquals(1, graph.steps().size());
        assertEquals("IEFBR14", graph.steps().get(0).program());
    }

    @Test
    void unresolved_symbolic_is_soft_gap(@TempDir Path tmp) throws Exception {
        Files.writeString(tmp.resolve("BAD.proc"), """
                //BAD PROC
                //S1 EXEC PGM=&MISSING
                """, StandardCharsets.UTF_8);
        String jcl = "//J JOB\n//X EXEC PROC=BAD\n";
        JclJobGraph graph = JclJobGraph.parse(jcl, tmp);
        assertTrue(graph.gaps().stream().anyMatch(g -> g.contains("unresolved symbolic &MISSING")),
                () -> String.valueOf(graph.gaps()));
        // Step still present with unsubstituted program token
        assertEquals(1, graph.steps().size());
        assertEquals("&MISSING", graph.steps().get(0).program());
    }
}
