package com.shadowstack.adapters.cobol;

import com.shadowstack.adapters.LanguageAdapter;
import com.shadowstack.adapters.model.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Live COBOL→Java semantic rehost IT: translate emits compilable Java and
 * {@code javac} hard-gates verification (never cobc).
 */
class CobolTranslateIT {

    /** Compact free-format program covering DISPLAY/MOVE/COMPUTE/IF/PERFORM. */
    static final String TRANSLATE_DEMO =
            ">>SOURCE FREE\n" +
            "IDENTIFICATION DIVISION.\n" +
            "PROGRAM-ID. HELLOSS.\n" +
            "DATA DIVISION.\n" +
            "WORKING-STORAGE SECTION.\n" +
            "01 WS-MSG PIC X(20) VALUE \"SHADOWSTACK\".\n" +
            "01 WS-N PIC 9(3) VALUE 2.\n" +
            "01 WS-TOTAL PIC 9(5) VALUE 0.\n" +
            "PROCEDURE DIVISION.\n" +
            "MAIN.\n" +
            "    MOVE \"READY\" TO WS-MSG.\n" +
            "    COMPUTE WS-TOTAL = WS-N * 10.\n" +
            "    IF WS-TOTAL = 20\n" +
            "        PERFORM SHOW-MSG\n" +
            "    ELSE\n" +
            "        DISPLAY \"NO\".\n" +
            "    DISPLAY WS-MSG.\n" +
            "    STOP RUN.\n" +
            "SHOW-MSG.\n" +
            "    DISPLAY WS-MSG.\n";

    @Test
    void translator_emits_compilable_java_class() {
        CobolToJavaTranslator.Result r = CobolToJavaTranslator.translate(TRANSLATE_DEMO);
        assertTrue(r.isTransformative());
        assertEquals("TranslatedHELLOSS", r.className());
        assertTrue(r.javaSource().contains("public class TranslatedHELLOSS"));
        assertTrue(r.javaSource().contains("public static void main"));
        assertTrue(r.javaSource().contains("System.out.println"));
        assertTrue(r.javaSource().contains("WS_MSG = \"READY\"")
                || r.javaSource().contains("WS_MSG = \"READY\";"));
        assertTrue(r.javaSource().contains("showMsg()"));
    }

    @Test
    void semantic_rehost_apply_and_javac_pass(@TempDir Path tmp) throws Exception {
        Path cob = tmp.resolve("HELLOSS.cob");
        Files.writeString(cob, TRANSLATE_DEMO, StandardCharsets.UTF_8);

        CobolAdapter adapter = new CobolAdapter();
        SemanticModel model = adapter.buildSemanticModel(tmp);
        RefactorCandidate candidate = adapter.listRefactorCandidates(
                        model, LanguageAdapter.RefactorRuleSet.empty()).stream()
                .filter(c -> CobolAdapter.RULE_SEMANTIC_REHOST.equals(c.ruleId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("semantic rehost candidate missing"));

        assertEquals(CobolAdapter.MODE_TRANSLATE, candidate.astContext().get("mode"));
        assertTrue(candidate.proposedAfterSnippet().contains("public class TranslatedHELLOSS"),
                "detect after-snippet must be real Java");

        PatchResult patch = adapter.applyRefactor(candidate, tmp);
        assertTrue(patch.success(), () -> String.valueOf(patch.errorMessage()));
        assertEquals(CobolAdapter.MODE_TRANSLATE, patch.metadata().get("mode"));
        assertEquals("cobol-to-java-semantic-rehost", patch.metadata().get("capability"));

        Path javaFile = tmp.resolve("TranslatedHELLOSS.java");
        assertTrue(Files.isRegularFile(javaFile), "sibling .java must be written");
        String java = Files.readString(javaFile, StandardCharsets.UTF_8);
        assertTrue(java.contains("System.out.println"));
        assertTrue(Files.readString(cob, StandardCharsets.UTF_8).contains("PROGRAM-ID"),
                "COBOL source must remain intact");

        VerificationResult vr = adapter.verifyPatch(
                patch, tmp, LanguageAdapter.VerificationConfig.defaults());
        assertTrue(vr.compileSuccess(), () -> String.valueOf(vr.layerResults()));
        assertEquals(VerificationResult.Verdict.PASS, vr.verdict(),
                () -> String.valueOf(vr.layerResults()));
        assertTrue(vr.layerResults().stream()
                        .anyMatch(l -> "compilation".equals(l.layerName()) && l.passed()
                                && l.details() != null && l.details().contains("javac")),
                () -> String.valueOf(vr.layerResults()));
    }

    @Test
    void translate_line_rule_also_emits_java_and_javac_passes(@TempDir Path tmp)
            throws Exception {
        Path cob = tmp.resolve("HELLOSS.cob");
        Files.writeString(cob, TRANSLATE_DEMO, StandardCharsets.UTF_8);
        CobolAdapter adapter = new CobolAdapter();
        SemanticModel model = adapter.buildSemanticModel(tmp);
        RefactorCandidate display = adapter.listRefactorCandidates(
                        model, LanguageAdapter.RefactorRuleSet.empty()).stream()
                .filter(c -> "cobol.display_to_print".equals(c.ruleId()))
                .findFirst()
                .orElseThrow();

        PatchResult patch = adapter.applyRefactor(display, tmp);
        assertTrue(patch.success());
        assertTrue(patch.affectedFiles().stream().anyMatch(f -> f.endsWith(".java")));

        VerificationResult vr = adapter.verifyPatch(
                patch, tmp, LanguageAdapter.VerificationConfig.defaults());
        assertTrue(vr.compileSuccess(), () -> String.valueOf(vr.layerResults()));
        assertEquals(VerificationResult.Verdict.PASS, vr.verdict(),
                () -> String.valueOf(vr.layerResults()));
    }

    @Test
    void javac_missing_hard_fails(@TempDir Path tmp) throws Exception {
        Path cob = tmp.resolve("HELLOSS.cob");
        Files.writeString(cob, TRANSLATE_DEMO, StandardCharsets.UTF_8);
        CobolAdapter adapter = new CobolAdapter();
        SemanticModel model = adapter.buildSemanticModel(tmp);
        RefactorCandidate candidate = adapter.listRefactorCandidates(
                        model, LanguageAdapter.RefactorRuleSet.empty()).stream()
                .filter(c -> CobolAdapter.RULE_SEMANTIC_REHOST.equals(c.ruleId()))
                .findFirst()
                .orElseThrow();
        PatchResult patch = adapter.applyRefactor(candidate, tmp);
        assertTrue(patch.success());

        String prev = System.getProperty("shadowstack.verify.javac");
        System.setProperty("shadowstack.verify.javac", "/nonexistent/shadowstack-javac");
        try {
            VerificationResult vr = adapter.verifyPatch(
                    patch, tmp, LanguageAdapter.VerificationConfig.defaults());
            assertEquals(VerificationResult.Verdict.FAIL, vr.verdict(),
                    () -> String.valueOf(vr.layerResults()));
            assertFalse(vr.compileSuccess());
            assertTrue(vr.layerResults().stream()
                            .anyMatch(l -> "compilation".equals(l.layerName()) && !l.passed()),
                    () -> String.valueOf(vr.layerResults()));
        } finally {
            if (prev == null) System.clearProperty("shadowstack.verify.javac");
            else System.setProperty("shadowstack.verify.javac", prev);
        }
    }

    @Test
    void payroll_example_translates(@TempDir Path tmp) throws Exception {
        Path repoPayroll = resolvePayrollExample();
        org.junit.jupiter.api.Assumptions.assumeTrue(
                repoPayroll != null && Files.isRegularFile(repoPayroll),
                "examples/legacy-cobol/PAYROLL.cob not found — skip");
        String src = Files.readString(repoPayroll, StandardCharsets.UTF_8);
        Path cob = tmp.resolve("PAYROLL.cob");
        Files.writeString(cob, src, StandardCharsets.UTF_8);

        CobolAdapter adapter = new CobolAdapter();
        List<RefactorCandidate> candidates = adapter.listRefactorCandidates(
                adapter.buildSemanticModel(tmp), LanguageAdapter.RefactorRuleSet.empty());
        RefactorCandidate rehost = candidates.stream()
                .filter(c -> CobolAdapter.RULE_SEMANTIC_REHOST.equals(c.ruleId()))
                .findFirst()
                .orElseThrow();
        PatchResult patch = adapter.applyRefactor(rehost, tmp);
        assertTrue(patch.success());
        VerificationResult vr = adapter.verifyPatch(
                patch, tmp, LanguageAdapter.VerificationConfig.defaults());
        assertEquals(VerificationResult.Verdict.PASS, vr.verdict(),
                () -> String.valueOf(vr.layerResults()));
        String java = Files.readString(tmp.resolve("TranslatedPAYROLL.java"));
        assertTrue(java.contains("GROSS_PAY = HOURS_WORKED * HOURLY_RATE"));
        assertTrue(java.contains("System.out.println"));
    }

    private static Path resolvePayrollExample() {
        Path cwd = Path.of("").toAbsolutePath().normalize();
        Path[] candidates = {
                cwd.resolve("examples/legacy-cobol/PAYROLL.cob"),
                cwd.resolve("../examples/legacy-cobol/PAYROLL.cob"),
                cwd.resolve("../../examples/legacy-cobol/PAYROLL.cob"),
                cwd.resolve("../../../examples/legacy-cobol/PAYROLL.cob")
        };
        for (Path p : candidates) {
            Path n = p.normalize();
            if (Files.isRegularFile(n)) return n;
        }
        return null;
    }
}
