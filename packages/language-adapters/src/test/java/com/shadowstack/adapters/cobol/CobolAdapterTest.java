package com.shadowstack.adapters.cobol;

import com.shadowstack.adapters.LanguageAdapter;
import com.shadowstack.adapters.model.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end exercise of the CobolAdapter on a fixed-format payroll program.
 * Verifies division/paragraph extraction, fixed-to-free conversion, and the
 * legacy-pattern refactor rules (preserving + translate tracks).
 */
class CobolAdapterTest {

    /** cobc -fsyntax-only clean fixed-format sample. */
    private static final String FIXED_FORMAT_PROGRAM =
            "000100 IDENTIFICATION DIVISION.\n" +
            "000200 PROGRAM-ID. SAMPLE.\n" +
            "000300 DATA DIVISION.\n" +
            "000400 WORKING-STORAGE SECTION.\n" +
            "000500 01 GREETING PIC X(10) VALUE \"HELLO\".\n" +
            "000600 01 WS-COUNT PIC 9(3) VALUE 1.\n" +
            "000650 01 WS-FLAG PIC X VALUE \"N\".\n" +
            "000660    88 WS-FLAG-ON VALUE \"Y\".\n" +
            "000700 PROCEDURE DIVISION.\n" +
            "000800 MAIN-PARA.\n" +
            "000900     ACCEPT GREETING.\n" +
            "001000     MOVE \"READY\" TO GREETING.\n" +
            "001100     ADD 1 TO WS-COUNT.\n" +
            "001200     SUBTRACT 1 FROM WS-COUNT.\n" +
            "001250     MULTIPLY 2 BY WS-COUNT.\n" +
            "001260     DIVIDE 2 INTO WS-COUNT.\n" +
            "001270     INITIALIZE GREETING.\n" +
            "001280     STRING \"HI\" DELIMITED BY SIZE INTO GREETING.\n" +
            "001290     SET WS-FLAG-ON TO TRUE.\n" +
            "001300     COMPUTE WS-COUNT = WS-COUNT * 2.\n" +
            "001400     PERFORM SHOW-GREETING.\n" +
            "001500     DISPLAY GREETING.\n" +
            "001600     GO TO END-PARA.\n" +
            "001700 SHOW-GREETING.\n" +
            "001800     DISPLAY GREETING.\n" +
            "001900 END-PARA.\n" +
            "001950     EXIT PROGRAM.\n" +
            "002000     STOP RUN.\n";

    @Test
    void parses_fixed_format_program(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("SAMPLE.cob");
        Files.writeString(file, FIXED_FORMAT_PROGRAM, StandardCharsets.UTF_8);

        CobolAdapter adapter = new CobolAdapter();
        assertEquals("cobol", adapter.languageId());
        assertEquals("85", adapter.languageVersion());

        SemanticModel model = adapter.buildSemanticModel(tmp);
        assertEquals(1, model.classCount(), "one PROGRAM-ID should map to one class");
        assertTrue(model.methodCount() >= 2, "MAIN-PARA + END-PARA must be paragraphs");
        SemanticModel.ClassInfo program = model.classes().get(0);
        assertEquals("SAMPLE", program.simpleName(), "program name must be extracted");
        assertFalse(model.fields().isEmpty(), "GREETING data item must be discovered");
    }

    @Test
    void detects_legacy_patterns(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("SAMPLE.cob");
        Files.writeString(file, FIXED_FORMAT_PROGRAM, StandardCharsets.UTF_8);

        CobolAdapter adapter = new CobolAdapter();
        SemanticModel model = adapter.buildSemanticModel(tmp);
        List<RefactorCandidate> candidates = adapter.listRefactorCandidates(
                model, LanguageAdapter.RefactorRuleSet.empty());

        Set<String> ruleIds = candidates.stream()
                .map(RefactorCandidate::ruleId).collect(Collectors.toSet());
        assertTrue(ruleIds.contains("cobol.fixed_to_free"), "fixed-to-free rule must fire");
        assertTrue(ruleIds.contains("cobol.stop_run_to_goback"), "STOP RUN rule must fire");
        assertTrue(ruleIds.contains("cobol.goto_to_perform"), "GO TO rule must fire");
        assertTrue(ruleIds.contains("cobol.exit_program_to_goback"), "EXIT PROGRAM→GOBACK must fire");
        assertTrue(ruleIds.contains("cobol.set_true_88"), "SET TO TRUE preserving must fire");
        assertTrue(ruleIds.contains("cobol.display_to_print"), "DISPLAY translate rule must fire");
        assertTrue(ruleIds.contains("cobol.move_to_assign"), "MOVE rule must fire");
        assertTrue(ruleIds.contains("cobol.compute_to_assign"), "COMPUTE rule must fire");
        assertTrue(ruleIds.contains("cobol.perform_to_call"), "PERFORM rule must fire");
        assertTrue(ruleIds.contains("cobol.add_to_assign"), "ADD rule must fire");
        assertTrue(ruleIds.contains("cobol.subtract_to_assign"), "SUBTRACT rule must fire");
        assertTrue(ruleIds.contains("cobol.accept_to_input"), "ACCEPT rule must fire");
        assertTrue(ruleIds.contains("cobol.multiply_to_assign"), "MULTIPLY rule must fire");
        assertTrue(ruleIds.contains("cobol.divide_to_assign"), "DIVIDE rule must fire");
        assertTrue(ruleIds.contains("cobol.initialize_to_clear"), "INITIALIZE rule must fire");
        assertTrue(ruleIds.contains("cobol.exit_program_to_return"), "EXIT PROGRAM translate must fire");
        assertTrue(ruleIds.contains("cobol.string_to_concat"), "STRING rule must fire");
        assertTrue(ruleIds.contains("cobol.set_to_true"), "SET TO TRUE translate must fire");

        assertTrue(candidates.stream()
                        .filter(c -> CobolAdapter.isPreservingRule(c.ruleId()))
                        .allMatch(c -> CobolAdapter.MODE_PRESERVING.equals(
                                c.astContext().get("mode"))),
                "preserving candidates must tag mode=preserving");
        assertTrue(candidates.stream()
                        .filter(c -> !CobolAdapter.isPreservingRule(c.ruleId()))
                        .allMatch(c -> CobolAdapter.MODE_TRANSLATE.equals(
                                c.astContext().get("mode"))),
                "translate candidates must tag mode=translate");
    }

    @Test
    void converts_fixed_to_free_format(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("SAMPLE.cob");
        Files.writeString(file, FIXED_FORMAT_PROGRAM, StandardCharsets.UTF_8);

        CobolAdapter adapter = new CobolAdapter();
        SemanticModel model = adapter.buildSemanticModel(tmp);

        RefactorCandidate candidate = adapter.listRefactorCandidates(
                model, LanguageAdapter.RefactorRuleSet.empty()).stream()
                .filter(c -> "cobol.fixed_to_free".equals(c.ruleId()))
                .findFirst().orElseThrow();

        PatchResult patch = adapter.applyRefactor(candidate, tmp);
        assertTrue(patch.success(), "fixed-to-free patch must succeed");

        String converted = Files.readString(file);
        assertTrue(converted.startsWith(">>SOURCE FREE"),
                "free-format must insert >>SOURCE FREE indicator");
        assertFalse(converted.contains("000100 IDENTIFICATION"),
                "free-format must drop the leading sequence area");
        assertTrue(converted.contains("IDENTIFICATION DIVISION"));
        assertTrue(converted.contains("PROGRAM-ID. SAMPLE."));
        assertTrue(converted.contains("STOP RUN."));

        VerificationResult vr = adapter.verifyPatch(patch, tmp,
                LanguageAdapter.VerificationConfig.defaults());
        assertTrue(vr.compileSuccess(),
                () -> "post-conversion cobc must PASS: " + vr.layerResults());
    }

    @Test
    void inspectReplacing_detects(@TempDir Path tmp) throws Exception {
        assertDetects(tmp, "inspect.cob",
                "       INSPECT WS-NAME REPLACING ALL \" \" BY \"0\".\n",
                "cobol.inspect_replacing");
    }

    @Test
    void unstring_detects(@TempDir Path tmp) throws Exception {
        assertDetects(tmp, "unstring.cob",
                "       UNSTRING WS-CSV DELIMITED BY \",\" INTO WS-A WS-B.\n",
                "cobol.unstring_to_split");
    }

    @Test
    void openClose_detect(@TempDir Path tmp) throws Exception {
        Set<String> openIds = detectIds(tmp.resolve("open"), "open.cob",
                "       OPEN INPUT CUST-FILE.\n");
        Set<String> closeIds = detectIds(tmp.resolve("close"), "close.cob",
                "       CLOSE CUST-FILE.\n");
        assertTrue(openIds.contains("cobol.open_to_stream"), openIds.toString());
        assertTrue(closeIds.contains("cobol.close_to_close"), closeIds.toString());
    }

    @Test
    void readWrite_detect(@TempDir Path tmp) throws Exception {
        Set<String> readIds = detectIds(tmp.resolve("read"), "read.cob",
                "       READ CUST-FILE INTO WS-REC.\n");
        Set<String> writeIds = detectIds(tmp.resolve("write"), "write.cob",
                "       WRITE CUST-REC FROM WS-REC.\n");
        assertTrue(readIds.contains("cobol.read_to_read"), readIds.toString());
        assertTrue(writeIds.contains("cobol.write_to_write"), writeIds.toString());
    }

    @Test
    void callContinue_detect(@TempDir Path tmp) throws Exception {
        Set<String> callIds = detectIds(tmp.resolve("call"), "call.cob",
                "       CALL \"CALC\".\n");
        Set<String> contIds = detectIds(tmp.resolve("continue"), "continue.cob",
                "       CONTINUE\n");
        assertTrue(callIds.contains("cobol.call_to_invoke"), callIds.toString());
        assertTrue(contIds.contains("cobol.continue_to_empty"), contIds.toString());
    }

    @Test
    void nextSentence_and_evaluateTrue_preserving(@TempDir Path tmp) throws Exception {
        String src =
                ">>SOURCE FREE\n" +
                "IDENTIFICATION DIVISION.\n" +
                "PROGRAM-ID. EV.\n" +
                "DATA DIVISION.\n" +
                "WORKING-STORAGE SECTION.\n" +
                "01 WS-X PIC 9 VALUE 1.\n" +
                "PROCEDURE DIVISION.\n" +
                "MAIN.\n" +
                "    IF WS-X = 1\n" +
                "        NEXT SENTENCE\n" +
                "    ELSE\n" +
                "        DISPLAY \"NO\".\n" +
                "    EVALUATE TRUE\n" +
                "        WHEN WS-X = 1\n" +
                "            DISPLAY \"ONE\"\n" +
                "        WHEN OTHER\n" +
                "            DISPLAY \"OTHER\"\n" +
                "    END-EVALUATE\n" +
                "    GOBACK.\n";
        Set<String> ids = detectIds(tmp, "ev.cob", src);
        assertTrue(ids.contains("cobol.next_sentence_to_continue"), ids.toString());
        assertTrue(ids.contains("cobol.evaluate_true_simplify"), ids.toString());
    }

    private static void assertDetects(Path tmp, String name, String source, String ruleId)
            throws Exception {
        Set<String> ids = detectIds(tmp, name, source);
        assertTrue(ids.contains(ruleId), ids.toString());
    }

    private static Set<String> detectIds(Path dir, String name, String source) throws Exception {
        Files.createDirectories(dir);
        Path file = dir.resolve(name);
        Files.writeString(file, source, StandardCharsets.UTF_8);
        CobolAdapter adapter = new CobolAdapter();
        SemanticModel model = adapter.buildSemanticModel(dir);
        return adapter.listRefactorCandidates(model, LanguageAdapter.RefactorRuleSet.empty())
                .stream().map(RefactorCandidate::ruleId).collect(Collectors.toSet());
    }

    @Test
    void autoApplicablePreserving_excludesDetectOnlyIdentityRules() {
        assertTrue(CobolAdapter.isAutoApplicablePreserving("cobol.fixed_to_free"));
        assertTrue(CobolAdapter.isAutoApplicablePreserving("cobol.stop_run_to_goback"));
        assertTrue(CobolAdapter.isAutoApplicablePreserving("cobol.goto_to_perform"));
        assertTrue(CobolAdapter.isAutoApplicablePreserving("cobol.exit_program_to_goback"));
        assertTrue(CobolAdapter.isAutoApplicablePreserving("cobol.section_exit_goback"));
        assertTrue(CobolAdapter.isAutoApplicablePreserving("cobol.next_sentence_to_continue"));
        assertTrue(CobolAdapter.isAutoApplicablePreserving("cobol.evaluate_true_simplify"));

        // Detect-only / identity — still preserving, but not auto-applicable.
        assertTrue(CobolAdapter.isPreservingRule("cobol.continue_to_empty"));
        assertFalse(CobolAdapter.isAutoApplicablePreserving("cobol.continue_to_empty"));
        assertFalse(CobolAdapter.isAutoApplicablePreserving("cobol.set_true_88"));
        assertFalse(CobolAdapter.isAutoApplicablePreserving("cobol.program_id_is_initial"));
        assertFalse(CobolAdapter.isAutoApplicablePreserving("cobol.alter_removed"));
        assertFalse(CobolAdapter.isAutoApplicablePreserving("cobol.remove_alter"));
        assertFalse(CobolAdapter.isAutoApplicablePreserving("cobol.inline_perform"));
        assertFalse(CobolAdapter.isAutoApplicablePreserving("cobol.perform_thru_expand"));
        assertFalse(CobolAdapter.isAutoApplicablePreserving("cobol.initialize_replacing"));
        assertFalse(CobolAdapter.isAutoApplicablePreserving("cobol.inspect_converting"));
        // free_format_indicator is an invariant of fixed_to_free, not a transform rule.
        assertFalse(CobolAdapter.isAutoApplicablePreserving("cobol.free_format_indicator"));
    }

    @Test
    void applies_stop_run_and_verifies(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("SAMPLE.cob");
        Files.writeString(file, FIXED_FORMAT_PROGRAM, StandardCharsets.UTF_8);
        CobolAdapter adapter = new CobolAdapter();
        SemanticModel model = adapter.buildSemanticModel(tmp);
        RefactorCandidate candidate = adapter.listRefactorCandidates(
                        model, LanguageAdapter.RefactorRuleSet.empty()).stream()
                .filter(c -> "cobol.stop_run_to_goback".equals(c.ruleId()))
                .findFirst()
                .orElseThrow();
        assertEquals(CobolAdapter.MODE_PRESERVING, candidate.astContext().get("mode"));
        PatchResult patch = adapter.applyRefactor(candidate, tmp);
        assertTrue(patch.success(), () -> String.valueOf(patch.errorMessage()));
        String updated = Files.readString(file, StandardCharsets.UTF_8);
        assertTrue(updated.toUpperCase().contains("GOBACK"), updated);
        VerificationResult vr = adapter.verifyPatch(
                patch, tmp, LanguageAdapter.VerificationConfig.defaults());
        assertTrue(vr.compileSuccess(), () -> String.valueOf(vr.layerResults()));
        assertNotNull(vr.verdict());
    }

    @Test
    void translate_verify_skips_cobc(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("SAMPLE.cob");
        Files.writeString(file, FIXED_FORMAT_PROGRAM, StandardCharsets.UTF_8);
        CobolAdapter adapter = new CobolAdapter();
        SemanticModel model = adapter.buildSemanticModel(tmp);
        RefactorCandidate candidate = adapter.listRefactorCandidates(
                        model, LanguageAdapter.RefactorRuleSet.empty()).stream()
                .filter(c -> "cobol.display_to_print".equals(c.ruleId()))
                .findFirst()
                .orElseThrow();
        assertEquals(CobolAdapter.MODE_TRANSLATE, candidate.astContext().get("mode"));
        PatchResult patch = adapter.applyRefactor(candidate, tmp);
        assertTrue(patch.success());
        VerificationResult vr = adapter.verifyPatch(
                patch, tmp, LanguageAdapter.VerificationConfig.defaults());
        assertFalse(vr.compileSuccess(), "translate must not claim cobc PASS");
        assertTrue(vr.layerResults().stream()
                .anyMatch(l -> "translate".equals(l.layerName())));
    }
}
