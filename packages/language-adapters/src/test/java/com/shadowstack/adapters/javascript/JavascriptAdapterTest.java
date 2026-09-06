package com.shadowstack.adapters.javascript;

import com.shadowstack.adapters.LanguageAdapter;
import com.shadowstack.adapters.model.RefactorCandidate;
import com.shadowstack.adapters.model.SemanticModel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
}
