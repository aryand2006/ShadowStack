package com.shadowstack.adapters.python;

import com.shadowstack.adapters.LanguageAdapter;
import com.shadowstack.adapters.model.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration coverage for the LibCST AST modernization engine wired through
 * {@link PythonAdapter}.
 */
class PythonAstEngineIT {

    static boolean libcstAvailable() {
        try {
            Process p = new ProcessBuilder("python3", "-c", "import libcst")
                    .redirectErrorStream(true)
                    .start();
            return p.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    @Test
    @EnabledIf("libcstAvailable")
    void astEngine_detects_xrange_and_apply_verifies(@TempDir Path tmp) throws Exception {
        Path module = tmp.resolve("loop.py");
        Files.writeString(module, """
                def count(n):
                    total = 0
                    for i in xrange(n):
                        total += i
                    return total
                """, StandardCharsets.UTF_8);

        PythonAdapter adapter = new PythonAdapter();
        SemanticModel model = adapter.buildSemanticModel(tmp);
        List<RefactorCandidate> candidates = adapter.listRefactorCandidates(
                model, LanguageAdapter.RefactorRuleSet.empty());

        RefactorCandidate xrange = candidates.stream()
                .filter(c -> "py.xrange_to_range".equals(c.ruleId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "expected py.xrange_to_range, got "
                                + candidates.stream().map(RefactorCandidate::ruleId).toList()));

        assertEquals("libcst", xrange.astContext().get("parseEngine"));
        assertTrue(xrange.beforeSnippet().contains("xrange"));
        assertTrue(xrange.proposedAfterSnippet().contains("range"));

        PatchResult patch = adapter.applyRefactor(xrange, tmp);
        assertTrue(patch.success(), () -> String.valueOf(patch.errorMessage()));
        assertEquals("libcst", patch.metadata().get("parseEngine"));

        String updated = Files.readString(module, StandardCharsets.UTF_8);
        assertTrue(updated.contains("range(n)"), updated);
        assertFalse(updated.contains("xrange"), updated);

        VerificationResult vr = adapter.verifyPatch(
                patch, tmp, LanguageAdapter.VerificationConfig.defaults());
        assertEquals(VerificationResult.Verdict.PASS, vr.verdict(),
                () -> vr.layerResults().toString());
    }
}
