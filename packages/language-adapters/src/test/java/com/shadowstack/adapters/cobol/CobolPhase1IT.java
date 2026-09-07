package com.shadowstack.adapters.cobol;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 1 COBOL dialect depth: OCCURS, PERFORM UNTIL, COMP-3, COPY, gaps.
 */
class CobolPhase1IT {

    static final String OCCURS_LOOP =
            ">>SOURCE FREE\n" +
            "IDENTIFICATION DIVISION.\n" +
            "PROGRAM-ID. OCCLOOP.\n" +
            "DATA DIVISION.\n" +
            "WORKING-STORAGE SECTION.\n" +
            "01 WS-I PIC 9(2) VALUE 1.\n" +
            "01 WS-N PIC 9(2) VALUE 3.\n" +
            "01 WS-TAB.\n" +
            "   05 WS-CELL PIC 9(3) OCCURS 3 TIMES.\n" +
            "PROCEDURE DIVISION.\n" +
            "MAIN.\n" +
            "    MOVE 10 TO WS-CELL(1).\n" +
            "    MOVE 20 TO WS-CELL(2).\n" +
            "    MOVE 30 TO WS-CELL(3).\n" +
            "    PERFORM BUMP UNTIL WS-I > WS-N.\n" +
            "    DISPLAY WS-CELL(1).\n" +
            "    STOP RUN.\n" +
            "BUMP.\n" +
            "    ADD 1 TO WS-CELL(WS-I).\n" +
            "    ADD 1 TO WS-I.\n";

    static final String COMP3_PROG =
            ">>SOURCE FREE\n" +
            "IDENTIFICATION DIVISION.\n" +
            "PROGRAM-ID. COMP3D.\n" +
            "DATA DIVISION.\n" +
            "WORKING-STORAGE SECTION.\n" +
            "01 WS-AMT PIC S9(7)V99 COMP-3 VALUE 12.50.\n" +
            "PROCEDURE DIVISION.\n" +
            "MAIN.\n" +
            "    DISPLAY WS-AMT.\n" +
            "    STOP RUN.\n";

    static final String OPEN_READ_GAPS =
            ">>SOURCE FREE\n" +
            "IDENTIFICATION DIVISION.\n" +
            "PROGRAM-ID. FILEGAP.\n" +
            "DATA DIVISION.\n" +
            "WORKING-STORAGE SECTION.\n" +
            "01 WS-MSG PIC X(8) VALUE \"HI\".\n" +
            "PROCEDURE DIVISION.\n" +
            "MAIN.\n" +
            "    OPEN INPUT INFILE.\n" +
            "    READ INFILE.\n" +
            "    REWRITE OUTREC.\n" +
            "    SORT WORKFILE.\n" +
            "    DISPLAY WS-MSG.\n" +
            "    STOP RUN.\n";

    @Test
    void occurs_and_subscript_loop_compiles_under_javac() throws Exception {
        CobolToJavaTranslator.Result r = CobolToJavaTranslator.translate(OCCURS_LOOP);
        assertTrue(r.isTransformative());
        assertTrue(r.javaSource().contains("int[]") || r.javaSource().contains("WS_CELL"),
                () -> r.javaSource());
        assertTrue(r.javaSource().contains("WS_CELL[") || r.javaSource().contains("WS_CELL ["),
                "subscript rewrite expected:\n" + r.javaSource());
        assertTrue(r.javaSource().contains("while ("),
                "PERFORM UNTIL → while expected:\n" + r.javaSource());
        assertFalse(r.javaSource().contains("// COBOL: PERFORM UNTIL"),
                "must not leave PERFORM UNTIL as comment:\n" + r.javaSource());
        assertJavacOk(r.javaSource(), r.className());
    }

    @Test
    void perform_until_emits_while_not_comment() {
        CobolToJavaTranslator.Result r = CobolToJavaTranslator.translate(OCCURS_LOOP);
        assertTrue(r.javaSource().contains("while (!(") || r.javaSource().contains("while ("),
                () -> r.javaSource());
        assertFalse(r.javaSource().contains("// COBOL: PERFORM UNTIL"));
    }

    @Test
    void comp3_maps_to_bigdecimal_field() throws Exception {
        CobolToJavaTranslator.Result r = CobolToJavaTranslator.translate(COMP3_PROG);
        assertTrue(r.javaSource().contains("BigDecimal"), () -> r.javaSource());
        assertTrue(r.javaSource().contains("import java.math.BigDecimal")
                        || r.javaSource().contains("java.math.BigDecimal"),
                () -> r.javaSource());
        assertTrue(r.javaSource().contains("WS_AMT"), () -> r.javaSource());
        assertJavacOk(r.javaSource(), r.className());
    }

    @Test
    void copy_expansion_fixture(@TempDir Path tmp) throws Exception {
        Path copybooks = tmp.resolve("copybooks");
        Files.createDirectories(copybooks);
        Path repoCopy = resolveExample("copybooks/WSCOMN.cpy");
        org.junit.jupiter.api.Assumptions.assumeTrue(
                repoCopy != null, "WSCOMN.cpy fixture missing");
        Files.writeString(copybooks.resolve("WSCOMN.cpy"),
                Files.readString(repoCopy, StandardCharsets.UTF_8), StandardCharsets.UTF_8);
        Path prog = tmp.resolve("COPYDEMO.cob");
        Path repoProg = resolveExample("COPYDEMO.cob");
        assumeOrWrite(prog, repoProg,
                ">>SOURCE FREE\nIDENTIFICATION DIVISION.\nPROGRAM-ID. COPYDEMO.\n"
                        + "DATA DIVISION.\nWORKING-STORAGE SECTION.\nCOPY WSCOMN.\n"
                        + "01 WS-MSG PIC X(8) VALUE \"OK\".\n"
                        + "PROCEDURE DIVISION.\nMAIN.\n    DISPLAY WS-MSG.\n    STOP RUN.\n");

        CobolToJavaTranslator.Result r =
                CobolToJavaTranslator.translate(Files.readString(prog), tmp);
        assertTrue(r.javaSource().contains("WS_COMMON_FLAG")
                        || r.javaSource().contains("WS_COMMON_COUNT")
                        || r.javaSource().contains("WS_COMMON_AMT"),
                "expanded copybook fields expected:\n" + r.javaSource());
        assertTrue(r.unsupportedGaps().stream().noneMatch(g -> g.startsWith("Missing copybook")),
                () -> String.valueOf(r.unsupportedGaps()));
        assertJavacOk(r.javaSource(), r.className());
    }

    @Test
    void gaps_non_empty_for_rewrite_sort_not_open_read() throws Exception {
        CobolToJavaTranslator.Result r = CobolToJavaTranslator.translate(OPEN_READ_GAPS);
        assertTrue(r.javaSource().contains("__openInput") || r.javaSource().contains("__read"),
                "Phase 2 OPEN/READ should emit file helpers:\n" + r.javaSource());
        assertTrue(r.unsupportedGaps().stream().anyMatch(g -> g.contains("SORT") || g.contains("REWRITE record")),
                () -> String.valueOf(r.unsupportedGaps()));
        assertTrue(r.unsupportedGaps().stream().noneMatch(g -> g.equals("unsupported verb: OPEN")),
                "OPEN should not remain an unsupported gap");
        assertJavacOk(r.javaSource(), r.className());
    }

    @Test
    void retail_example_translates_when_present() throws Exception {
        Path retail = resolveExample("RETAIL.cob");
        org.junit.jupiter.api.Assumptions.assumeTrue(retail != null);
        CobolToJavaTranslator.Result r =
                CobolToJavaTranslator.translate(Files.readString(retail));
        assertTrue(r.isTransformative());
        assertTrue(r.javaSource().contains("while ("), () -> r.javaSource());
        assertJavacOk(r.javaSource(), r.className());
    }

    private static void assumeOrWrite(Path dest, Path repo, String fallback) throws Exception {
        if (repo != null && Files.isRegularFile(repo)) {
            Files.writeString(dest, Files.readString(repo, StandardCharsets.UTF_8),
                    StandardCharsets.UTF_8);
        } else {
            Files.writeString(dest, fallback, StandardCharsets.UTF_8);
        }
    }

    private static void assertJavacOk(String javaSource, String className) throws Exception {
        Path tmp = Files.createTempDirectory("cobol-phase1-javac-");
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
