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
 * legacy-pattern refactor rules.
 */
class CobolAdapterTest {

    private static final String FIXED_FORMAT_PROGRAM =
            "000100 IDENTIFICATION DIVISION.                                         00000100\n" +
            "000200 PROGRAM-ID. SAMPLE.                                              00000200\n" +
            "000300 DATA DIVISION.                                                   00000300\n" +
            "000400 WORKING-STORAGE SECTION.                                         00000400\n" +
            "000500 01 GREETING PIC X(10) VALUE \"HELLO\".                             00000500\n" +
            "000600 01 WS-COUNT PIC 9(3) VALUE 1.                                    00000600\n" +
            "000650 01 WS-FLAG PIC X VALUE \"N\".                                      00000650\n" +
            "000700 PROCEDURE DIVISION.                                              00000700\n" +
            "000800 MAIN-PARA.                                                       00000800\n" +
            "000900     ACCEPT GREETING.                                             00000900\n" +
            "001000     MOVE \"READY\" TO GREETING.                                    00001000\n" +
            "001100     ADD 1 TO WS-COUNT.                                           00001100\n" +
            "001200     SUBTRACT 1 FROM WS-COUNT.                                    00001200\n" +
            "001250     MULTIPLY 2 BY WS-COUNT.                                      00001250\n" +
            "001260     DIVIDE 2 INTO WS-COUNT.                                      00001260\n" +
            "001270     INITIALIZE GREETING.                                         00001270\n" +
            "001280     STRING \"HI\" GREETING INTO GREETING.                          00001280\n" +
            "001290     SET WS-FLAG TO TRUE.                                         00001290\n" +
            "001300     COMPUTE WS-COUNT = WS-COUNT * 2.                             00001300\n" +
            "001400     PERFORM SHOW-GREETING.                                       00001400\n" +
            "001500     DISPLAY GREETING.                                            00001500\n" +
            "001600     GO TO END-PARA.                                              00001600\n" +
            "001700 SHOW-GREETING.                                                   00001700\n" +
            "001800     DISPLAY GREETING.                                            00001800\n" +
            "001900 END-PARA.                                                        00001900\n" +
            "001950     EXIT PROGRAM.                                                00001950\n" +
            "002000     STOP RUN.                                                    00002000\n";

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
        assertTrue(ruleIds.contains("cobol.display_to_print"), "DISPLAY rule must fire");
        assertTrue(ruleIds.contains("cobol.move_to_assign"), "MOVE rule must fire");
        assertTrue(ruleIds.contains("cobol.compute_to_assign"), "COMPUTE rule must fire");
        assertTrue(ruleIds.contains("cobol.perform_to_call"), "PERFORM rule must fire");
        assertTrue(ruleIds.contains("cobol.add_to_assign"), "ADD rule must fire");
        assertTrue(ruleIds.contains("cobol.subtract_to_assign"), "SUBTRACT rule must fire");
        assertTrue(ruleIds.contains("cobol.accept_to_input"), "ACCEPT rule must fire");
        assertTrue(ruleIds.contains("cobol.multiply_to_assign"), "MULTIPLY rule must fire");
        assertTrue(ruleIds.contains("cobol.divide_to_assign"), "DIVIDE rule must fire");
        assertTrue(ruleIds.contains("cobol.initialize_to_clear"), "INITIALIZE rule must fire");
        assertTrue(ruleIds.contains("cobol.exit_program_to_return"), "EXIT PROGRAM rule must fire");
        assertTrue(ruleIds.contains("cobol.string_to_concat"), "STRING rule must fire");
        assertTrue(ruleIds.contains("cobol.set_to_true"), "SET TO TRUE rule must fire");
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
        assertFalse(converted.startsWith("000100"),
                "free-format must drop the leading sequence area");
        assertTrue(converted.contains("IDENTIFICATION DIVISION"));
        assertTrue(converted.contains("PROGRAM-ID. SAMPLE."));
        assertTrue(converted.contains("STOP RUN."));

        VerificationResult vr = adapter.verifyPatch(patch, tmp,
                LanguageAdapter.VerificationConfig.defaults());
        assertTrue(vr.compileSuccess(),
                "post-conversion program must still re-parse cleanly");
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
        PatchResult patch = adapter.applyRefactor(candidate, tmp);
        assertTrue(patch.success(), () -> String.valueOf(patch.errorMessage()));
        String updated = Files.readString(file, StandardCharsets.UTF_8);
        assertTrue(updated.toUpperCase().contains("GOBACK"), updated);
        VerificationResult vr = adapter.verifyPatch(
                patch, tmp, LanguageAdapter.VerificationConfig.defaults());
        assertTrue(vr.compileSuccess());
        assertNotNull(vr.verdict());
    }
}
