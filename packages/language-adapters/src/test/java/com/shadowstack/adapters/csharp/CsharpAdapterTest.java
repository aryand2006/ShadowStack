package com.shadowstack.adapters.csharp;

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

class CsharpAdapterTest {

    @Test
    void parses_and_detects_csharp_modernization_rules(@TempDir Path tmp) throws Exception {
        String source = """
                using System.Collections;
                namespace Legacy;

                public class LegacyService
                {
                    public void Run(string value)
                    {
                        ArrayList values = new ArrayList();
                        Hashtable lookup = new Hashtable();
                        builder.AppendFormat("Value {0}", value);
                        var text = string.Format("Value {0}", value);
                        ReadOnlyCollection<string> names = null;
                        var client = new WebClient();
                        var setting = ConfigurationManager.AppSettings["name"];
                        var formatter = new BinaryFormatter();
                        Thread.Abort();
                        BeginInvoke(callback, null);
                        if (value == "") throw new ArgumentNullException("value");
                        if (!cache.ContainsKey(value)) cache[value] = text;
                    }
                }
                """;
        Files.writeString(tmp.resolve("Legacy.cs"), source, StandardCharsets.UTF_8);

        CsharpAdapter adapter = new CsharpAdapter();
        assertEquals("csharp", adapter.languageId());
        assertEquals("12", adapter.languageVersion());

        SemanticModel model = adapter.buildSemanticModel(tmp);
        assertEquals(1, model.classCount());
        assertTrue(model.methodCount() >= 1);

        Set<String> ruleIds = adapter.listRefactorCandidates(
                        model, LanguageAdapter.RefactorRuleSet.empty()).stream()
                .map(RefactorCandidate::ruleId)
                .collect(Collectors.toSet());

        assertTrue(ruleIds.contains("cs.nullable_enable"), ruleIds.toString());
        assertTrue(ruleIds.contains("cs.arraylist_to_list"), ruleIds.toString());
        assertTrue(ruleIds.contains("cs.hashtable_to_dictionary"), ruleIds.toString());
        assertTrue(ruleIds.contains("cs.stringbuilder_appendformat"), ruleIds.toString());
        assertTrue(ruleIds.contains("cs.string_format_to_interpolation"), ruleIds.toString());
        assertTrue(ruleIds.contains("cs.webclient_to_httpclient"), ruleIds.toString());
        assertTrue(ruleIds.contains("cs.binaryformatter_removed"), ruleIds.toString());
        assertTrue(ruleIds.contains("cs.nameof_for_literals"), ruleIds.toString());
        assertTrue(ruleIds.contains("cs.string_isempty"), ruleIds.toString());
        assertTrue(ruleIds.contains("cs.concurrentdict_tryadd"), ruleIds.toString());
    }


    @Test
    void applies_and_verifies_arraylist_to_list(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("Sample.cs");
        Files.writeString(file,
                "using System.Collections;\nclass Sample { ArrayList items = new ArrayList(); }\n",
                java.nio.charset.StandardCharsets.UTF_8);
        CsharpAdapter adapter = new CsharpAdapter();
        SemanticModel model = adapter.buildSemanticModel(tmp);
        List<RefactorCandidate> candidates = adapter.listRefactorCandidates(
                model, LanguageAdapter.RefactorRuleSet.empty());
        RefactorCandidate target = candidates.stream()
                .filter(c -> c.ruleId().contains("arraylist"))
                .findFirst()
                .orElseThrow();
        PatchResult patch = adapter.applyRefactor(target, tmp);
        assertTrue(patch.success(), () -> String.valueOf(patch.errorMessage()));
        String updated = Files.readString(file);
        assertTrue(updated.contains("List"), updated);
        VerificationResult verification = adapter.verifyPatch(
                patch, tmp, LanguageAdapter.VerificationConfig.defaults());
        assertNotNull(verification.verdict());
    }


    @Test
    void applies_and_verifies_hashtable_rule(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("Sample.cs");
        Files.writeString(file,
                "using System.Collections;\nclass Sample { Hashtable lookup = new Hashtable(); }\n",
                StandardCharsets.UTF_8);
        CsharpAdapter adapter = new CsharpAdapter();
        SemanticModel model = adapter.buildSemanticModel(tmp);
        RefactorCandidate target = adapter.listRefactorCandidates(
                        model, LanguageAdapter.RefactorRuleSet.empty()).stream()
                .filter(c -> c.ruleId().contains("hashtable"))
                .findFirst()
                .orElseThrow();
        PatchResult patch = adapter.applyRefactor(target, tmp);
        assertTrue(patch.success(), () -> String.valueOf(patch.errorMessage()));
        String updated = Files.readString(file, StandardCharsets.UTF_8);
        assertTrue(updated.contains("Dictionary"), updated);
        VerificationResult verification = adapter.verifyPatch(
                patch, tmp, LanguageAdapter.VerificationConfig.defaults());
        assertNotNull(verification.verdict());
    }
}
