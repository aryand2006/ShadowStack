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
 * Verifies division/paragraph extraction, fixed→free conversion, and the
 * legacy-pattern refactor rules.
 */
class CobolAdapterTest {

    private static final String FIXED_FORMAT_PROGRAM =
            "000100 IDENTIFICATION DIVISION.                                         00000100\n" +
            "000200 PROGRAM-ID. SAMPLE.                                              00000200\n" +
            "000300 DATA DIVISION.                                                   00000300\n" +
            "000400 WORKING-STORAGE SECTION.                                         00000400\n" +
            "000500 01 GREETING       PIC X(10) VALUE \"HELLO\".                     00000500\n" +
            "000600 PROCEDURE DIVISION.                                              00000600\n" +
            "000700 MAIN-PARA.                                                       00000700\n" +
            "000800     DISPLAY GREETING.                                            00000800\n" +
            "000900     GO TO END-PARA.                                              00000900\n" +
            "001000 END-PARA.                                                        00001000\n" +
            "001100     STOP RUN.                                                    00001100\n";

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
        assertTrue(ruleIds.contains("cobol.fixed_to_free"),     "fixed→free rule must fire");
        assertTrue(ruleIds.contains("cobol.stop_run_to_goback"), "STOP RUN rule must fire");
        assertTrue(ruleIds.contains("cobol.goto_to_perform"),    "terminal GO TO rule must fire");
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
        assertTrue(patch.success(), "fixed→free patch must succeed");

        String converted = Files.readString(file);
        // No more sequence numbers in cols 1–6:
        assertFalse(converted.startsWith("000100"),
                "free-format must drop the leading sequence area");
        // Program area content preserved:
        assertTrue(converted.contains("IDENTIFICATION DIVISION"));
        assertTrue(converted.contains("PROGRAM-ID. SAMPLE."));
        assertTrue(converted.contains("STOP RUN."));

        VerificationResult vr = adapter.verifyPatch(patch, tmp,
                LanguageAdapter.VerificationConfig.defaults());
        assertTrue(vr.compileSuccess(),
                "post-conversion program must still re-parse cleanly");
    }
}
