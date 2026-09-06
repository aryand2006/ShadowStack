package com.shadowstack.adapters.javascript;

import com.shadowstack.adapters.LanguageAdapter;
import com.shadowstack.adapters.model.*;
import com.shadowstack.adapters.model.RefactorCandidate;
import com.shadowstack.adapters.model.SemanticModel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class JavascriptAdapterTest {

    @Test
    void parses_and_detects_javascript_modernization_rules(@TempDir Path tmp) throws Exception {
        String source = """
                class LegacyService {}

                function legacy(value, items) {
                    var loose = value == "1";
                    if (loose != false) console.log(__dirname, __filename);
                    const first = arguments[0];
                    const fs = require('fs');
                    const promise = new Promise(function (resolve) { resolve(value); });
                    const label = "legacy " + "javascript";
                    const short = label.substr(1);
                    const encoded = escape(label);
                    const found = items.indexOf(value) >= 0;
                    module.exports = found;
                    exports.label = label;
                }
                """;
        Files.writeString(tmp.resolve("legacy.js"), source, StandardCharsets.UTF_8);

        JavascriptAdapter adapter = new JavascriptAdapter();
        assertEquals("javascript", adapter.languageId());
        assertEquals("ES2022", adapter.languageVersion());

        SemanticModel model = adapter.buildSemanticModel(tmp);
        assertEquals(1, model.classCount());
        assertTrue(model.methodCount() >= 1);

        Set<String> ruleIds = adapter.listRefactorCandidates(
                        model, LanguageAdapter.RefactorRuleSet.empty()).stream()
                .map(RefactorCandidate::ruleId)
                .collect(Collectors.toSet());

        assertTrue(ruleIds.contains("js.var_to_let"), ruleIds.toString());
        assertTrue(ruleIds.contains("js.==_to_==="), ruleIds.toString());
        assertTrue(ruleIds.contains("js.!=_to_!=="), ruleIds.toString());
        assertTrue(ruleIds.contains("js.require_to_import"), ruleIds.toString());
        assertTrue(ruleIds.contains("js.module_exports_to_export"), ruleIds.toString());
        assertTrue(ruleIds.contains("js.exports_dot_to_export"), ruleIds.toString());
        assertTrue(ruleIds.contains("js.string_concat_plus"), ruleIds.toString());
        assertTrue(ruleIds.contains("js.substr_to_substring"), ruleIds.toString());
        assertTrue(ruleIds.contains("js.indexof_to_includes"), ruleIds.toString());
    }


    @Test
    void applies_and_verifies_var_to_let(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("sample.js");
        Files.writeString(file, "var x = 1;\n", java.nio.charset.StandardCharsets.UTF_8);
        JavascriptAdapter adapter = new JavascriptAdapter();
        SemanticModel model = adapter.buildSemanticModel(tmp);
        List<RefactorCandidate> candidates = adapter.listRefactorCandidates(
                model, LanguageAdapter.RefactorRuleSet.empty());
        RefactorCandidate target = candidates.stream()
                .filter(c -> "js.var_to_let".equals(c.ruleId()))
                .findFirst()
                .orElseThrow();
        PatchResult patch = adapter.applyRefactor(target, tmp);
        assertTrue(patch.success(), () -> String.valueOf(patch.errorMessage()));
        String updated = Files.readString(file);
        assertTrue(updated.contains("let"), updated);
        VerificationResult verification = adapter.verifyPatch(
                patch, tmp, LanguageAdapter.VerificationConfig.defaults());
        assertEquals(VerificationResult.Verdict.PASS, verification.verdict(),
                () -> String.valueOf(verification.layerResults()));
        assertTrue(verification.compileSuccess());
    }


    @Test
    void applies_and_verifies_loose_equality(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("eq.js");
        Files.writeString(file, "function isOne(x) {\n  return x == \"1\";\n}\n",
                StandardCharsets.UTF_8);
        JavascriptAdapter adapter = new JavascriptAdapter();
        SemanticModel model = adapter.buildSemanticModel(tmp);
        RefactorCandidate target = adapter.listRefactorCandidates(
                        model, LanguageAdapter.RefactorRuleSet.empty()).stream()
                .filter(c -> "js.==_to_===".equals(c.ruleId()))
                .findFirst()
                .orElseThrow();
        PatchResult patch = adapter.applyRefactor(target, tmp);
        assertTrue(patch.success(), () -> String.valueOf(patch.errorMessage()));
        String updated = Files.readString(file);
        assertTrue(updated.contains("==="), updated);
        VerificationResult verification = adapter.verifyPatch(
                patch, tmp, LanguageAdapter.VerificationConfig.defaults());
        assertEquals(VerificationResult.Verdict.PASS, verification.verdict(),
                () -> String.valueOf(verification.layerResults()));
        assertTrue(verification.compileSuccess());
    }

    @Test
    void applies_and_verifies_object_assign_spread(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("assign.js");
        Files.writeString(file, "function merge(value) {\n  return Object.assign({}, value);\n}\n",
                StandardCharsets.UTF_8);
        JavascriptAdapter adapter = new JavascriptAdapter();
        SemanticModel model = adapter.buildSemanticModel(tmp);
        RefactorCandidate target = adapter.listRefactorCandidates(
                        model, LanguageAdapter.RefactorRuleSet.empty()).stream()
                .filter(c -> "js.object_assign_to_spread".equals(c.ruleId()))
                .findFirst()
                .orElseThrow();
        PatchResult patch = adapter.applyRefactor(target, tmp);
        assertTrue(patch.success(), () -> String.valueOf(patch.errorMessage()));
        String updated = Files.readString(file);
        assertTrue(updated.contains("{...value}"), updated);
        assertFalse(updated.contains("Object.assign"), updated);
        VerificationResult verification = adapter.verifyPatch(
                patch, tmp, LanguageAdapter.VerificationConfig.defaults());
        assertEquals(VerificationResult.Verdict.PASS, verification.verdict(),
                () -> String.valueOf(verification.layerResults()));
    }

    @Test
    void applies_and_verifies_startswith(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("starts.js");
        Files.writeString(file,
                "function startsWithValue(list, value) {\n  return list.indexOf(value) === 0;\n}\n",
                StandardCharsets.UTF_8);
        JavascriptAdapter adapter = new JavascriptAdapter();
        SemanticModel model = adapter.buildSemanticModel(tmp);
        RefactorCandidate target = adapter.listRefactorCandidates(
                        model, LanguageAdapter.RefactorRuleSet.empty()).stream()
                .filter(c -> "js.indexof_zero_to_startswith".equals(c.ruleId()))
                .findFirst()
                .orElseThrow();
        PatchResult patch = adapter.applyRefactor(target, tmp);
        assertTrue(patch.success(), () -> String.valueOf(patch.errorMessage()));
        String updated = Files.readString(file);
        assertTrue(updated.contains(".startsWith(value)"), updated);
        VerificationResult verification = adapter.verifyPatch(
                patch, tmp, LanguageAdapter.VerificationConfig.defaults());
        assertEquals(VerificationResult.Verdict.PASS, verification.verdict(),
                () -> String.valueOf(verification.layerResults()));
    }
}
