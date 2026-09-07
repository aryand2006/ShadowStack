package com.shadowstack.adapters;

import com.shadowstack.adapters.cobol.CobolAdapter;
import com.shadowstack.adapters.csharp.CsharpAdapter;
import com.shadowstack.adapters.javascript.JavascriptAdapter;
import com.shadowstack.adapters.model.PatchResult;
import com.shadowstack.adapters.model.RefactorCandidate;
import com.shadowstack.adapters.model.SemanticModel;
import com.shadowstack.adapters.model.VerificationResult;
import com.shadowstack.adapters.python.PythonAdapter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Proves the non-Java adapters can run the full live-convert loop
 * (parse → detect → apply → verify) against the bundled legacy samples.
 */
class MultiLanguageLiveConvertTest {

    @Test
    void python_sample_live_convert(@TempDir Path tmp) throws Exception {
        runLiveConvert(
                new PythonAdapter(),
                resolveSample("examples/legacy-python/report_builder.py"),
                tmp,
                "report_builder.py",
                c -> c.ruleId().startsWith("py."));
    }

    @Test
    void cobol_sample_live_convert(@TempDir Path tmp) throws Exception {
        runLiveConvert(
                new CobolAdapter(),
                resolveSample("examples/legacy-cobol/PAYROLL.cob"),
                tmp,
                "PAYROLL.cob",
                c -> CobolAdapter.isPreservingRule(c.ruleId())
                        && !c.beforeSnippet().equals(c.proposedAfterSnippet()));
    }

    @Test
    void javascript_sample_live_convert(@TempDir Path tmp) throws Exception {
        runLiveConvert(
                new JavascriptAdapter(),
                resolveSample("examples/legacy-javascript/legacy.js"),
                tmp,
                "legacy.js",
                c -> c.ruleId().startsWith("js."));
    }

    @Test
    void python_libcst_xrange_live_convert(@TempDir Path tmp) throws Exception {
        Path sample = resolveSample("examples/legacy-python/xrange_legacy.py");
        assumeTrue(Files.isRegularFile(sample), "sample missing: " + sample);
        Files.writeString(tmp.resolve("xrange_legacy.py"),
                Files.readString(sample, StandardCharsets.UTF_8), StandardCharsets.UTF_8);

        PythonAdapter adapter = new PythonAdapter();
        SemanticModel model = adapter.buildSemanticModel(tmp);
        RefactorCandidate chosen = adapter.listRefactorCandidates(
                        model, LanguageAdapter.RefactorRuleSet.empty()).stream()
                .filter(c -> "py.xrange_to_range".equals(c.ruleId()))
                .findFirst()
                .orElseThrow();
        assertEquals("libcst",
                chosen.astContext() != null ? chosen.astContext().get("parseEngine") : null,
                "xrange sample must be detected via LibCST");

        PatchResult patch = adapter.applyRefactor(chosen, tmp);
        assertTrue(patch.success(), () -> String.valueOf(patch.errorMessage()));
        assertEquals("libcst", patch.metadata() != null ? patch.metadata().get("parseEngine") : null);
        VerificationResult verification = adapter.verifyPatch(
                patch, tmp, LanguageAdapter.VerificationConfig.defaults());
        assertEquals(VerificationResult.Verdict.PASS, verification.verdict(),
                () -> String.valueOf(verification.layerResults()));
    }

    @Test
    void csharp_sample_live_convert(@TempDir Path tmp) throws Exception {
        Path sample = resolveSample("examples/legacy-csharp/Legacy.cs");
        Path csproj = resolveSample("examples/legacy-csharp/LegacyCsharp.csproj");
        assumeTrue(Files.isRegularFile(sample), "sample missing: " + sample);
        assumeTrue(Files.isRegularFile(csproj), "csproj missing: " + csproj);
        Files.writeString(tmp.resolve("Legacy.cs"),
                Files.readString(sample, StandardCharsets.UTF_8), StandardCharsets.UTF_8);
        Files.copy(csproj, tmp.resolve("LegacyCsharp.csproj"));

        CsharpAdapter adapter = new CsharpAdapter();
        SemanticModel model = adapter.buildSemanticModel(tmp);
        List<RefactorCandidate> candidates = adapter.listRefactorCandidates(
                model, LanguageAdapter.RefactorRuleSet.empty());
        assertFalse(candidates.isEmpty());
        RefactorCandidate chosen = candidates.stream()
                .filter(c -> c.ruleId().startsWith("cs.")
                        && !"cs.nullable_enable".equals(c.ruleId())
                        && !c.beforeSnippet().equals(c.proposedAfterSnippet()))
                .sorted(Comparator.comparingDouble(RefactorCandidate::confidenceScore).reversed())
                .findFirst()
                .orElseThrow();
        PatchResult patch = adapter.applyRefactor(chosen, tmp);
        assertTrue(patch.success(), () -> String.valueOf(patch.errorMessage()));
        String updated = Files.readString(tmp.resolve("Legacy.cs"), StandardCharsets.UTF_8);
        assertNotEquals(Files.readString(sample, StandardCharsets.UTF_8), updated);
        VerificationResult verification = adapter.verifyPatch(
                patch, tmp, LanguageAdapter.VerificationConfig.defaults());
        assumeTrue(verification.layerResults().stream().anyMatch(l ->
                        l.details() != null && l.details().contains("dotnet build succeeded"))
                        || verification.verdict() == VerificationResult.Verdict.PASS,
                "dotnet build required for C# full gate");
        assertEquals(VerificationResult.Verdict.PASS, verification.verdict(),
                () -> String.valueOf(verification.layerResults()));
    }

    private static void runLiveConvert(
            LanguageAdapter adapter,
            Path sample,
            Path tmp,
            String fileName,
            java.util.function.Predicate<RefactorCandidate> ruleFilter) throws Exception {
        assumeTrue(Files.isRegularFile(sample), "sample missing: " + sample);
        String original = Files.readString(sample, StandardCharsets.UTF_8);
        Path target = tmp.resolve(fileName);
        Files.writeString(target, original, StandardCharsets.UTF_8);

        SemanticModel model = adapter.buildSemanticModel(tmp);
        assertNotNull(model);

        List<RefactorCandidate> candidates = adapter.listRefactorCandidates(
                model, LanguageAdapter.RefactorRuleSet.empty());
        assertFalse(candidates.isEmpty(),
                adapter.languageId() + " should detect modernization candidates");

        RefactorCandidate chosen = candidates.stream()
                .filter(ruleFilter)
                .sorted(Comparator.comparingDouble(RefactorCandidate::confidenceScore).reversed())
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "No matching candidate for " + adapter.languageId()
                                + " from " + candidates.stream().map(RefactorCandidate::ruleId).toList()));

        PatchResult patch = adapter.applyRefactor(chosen, tmp);
        assertTrue(patch.success(), () -> adapter.languageId() + " apply failed: "
                + patch.errorMessage());
        assertFalse(patch.affectedFiles().isEmpty());

        String updated = Files.readString(target, StandardCharsets.UTF_8);
        assertNotEquals(original, updated,
                adapter.languageId() + " apply should rewrite source for " + chosen.ruleId());

        VerificationResult verification = adapter.verifyPatch(
                patch, tmp, LanguageAdapter.VerificationConfig.defaults());
        assertNotNull(verification.verdict());
        assertFalse(verification.layerResults().isEmpty(),
                adapter.languageId() + " verify must emit layers");
    }

    private static Path resolveSample(String relative) {
        Path cwd = Path.of(relative).toAbsolutePath().normalize();
        if (Files.isRegularFile(cwd)) {
            return cwd;
        }
        Path fromModule = Path.of("..", "..", relative).toAbsolutePath().normalize();
        if (Files.isRegularFile(fromModule)) {
            return fromModule;
        }
        return Path.of("/workspace", relative);
    }
}
