package com.shadowstack.adapters.python;

import com.shadowstack.adapters.LanguageAdapter;
import com.shadowstack.adapters.model.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end exercise of the PythonAdapter: parse a Python 2 module, list every
 * shipped rule's candidates, apply each in turn, and verify the patched module.
 */
class PythonAdapterTest {

    private static final String PY2_SOURCE = """
            import urllib2

            class Counter:
                def render(self, rows):
                    print "rows:", len(rows)
                    for i in xrange(len(rows)):
                        row = rows[i]
                        for k, v in row.iteritems():
                            print k, unicode(v)
                    if len(rows) <> 0:
                        return self._summary()
                    return None

                def _summary(self):
                    try:
                        return urllib2.urlopen("https://example.invalid").read()
                    except Exception, e:
                        print "boom:", e
                        return None
            """;

    @Test
    void parses_and_detects_every_python2_rule(@TempDir Path tmp) throws Exception {
        Path module = tmp.resolve("counter.py");
        Files.writeString(module, PY2_SOURCE, StandardCharsets.UTF_8);

        PythonAdapter adapter = new PythonAdapter();
        assertEquals("python", adapter.languageId());
        assertEquals("3.12", adapter.languageVersion());

        SemanticModel model = adapter.buildSemanticModel(tmp);
        assertEquals(1, model.classCount(), "Counter class should be discovered");
        assertTrue(model.methodCount() >= 2, "render + _summary should be discovered");

        List<RefactorCandidate> candidates = adapter.listRefactorCandidates(
                model, LanguageAdapter.RefactorRuleSet.empty());

        Set<String> ruleIds = candidates.stream()
                .map(RefactorCandidate::ruleId)
                .collect(Collectors.toSet());
        assertTrue(ruleIds.contains("py.print_stmt_to_call"),  "print statement rule must fire");
        assertTrue(ruleIds.contains("py.xrange_to_range"),     "xrange rule must fire");
        assertTrue(ruleIds.contains("py.iter_methods_to_views"), "iter* rule must fire");
        assertTrue(ruleIds.contains("py.except_comma_to_as"),  "except-comma rule must fire");
        assertTrue(ruleIds.contains("py.unicode_to_str"),      "unicode() rule must fire");
        assertTrue(ruleIds.contains("py.ne_operator"),         "<> operator rule must fire");
    }

    @Test
    void applies_print_rule_and_writes_diff(@TempDir Path tmp) throws Exception {
        Path module = tmp.resolve("hello.py");
        Files.writeString(module, "print \"hi\"\n", StandardCharsets.UTF_8);

        PythonAdapter adapter = new PythonAdapter();
        SemanticModel model = adapter.buildSemanticModel(tmp);
        List<RefactorCandidate> candidates = adapter.listRefactorCandidates(
                model, LanguageAdapter.RefactorRuleSet.empty());

        RefactorCandidate printCandidate = candidates.stream()
                .filter(c -> "py.print_stmt_to_call".equals(c.ruleId()))
                .findFirst().orElseThrow();

        PatchResult patch = adapter.applyRefactor(printCandidate, tmp);
        assertTrue(patch.success(), "patch should apply");
        assertTrue(patch.unifiedDiff().contains("-print \"hi\""), "diff should show removal");
        assertTrue(patch.unifiedDiff().contains("+print(\"hi\")"), "diff should show addition");
        assertTrue(patch.hasStructuralChange(), "AST hash must change");

        String patched = Files.readString(module);
        assertEquals("print(\"hi\")\n", patched);

        VerificationResult vr = adapter.verifyPatch(patch, tmp,
                LanguageAdapter.VerificationConfig.quick());
        assertNotNull(vr.verdict(), "verdict must be set");
    }

    @Test
    void ignores_patterns_inside_strings_and_comments(@TempDir Path tmp) throws Exception {
        // print/xrange/<> inside a string literal and a comment must NOT trigger candidates.
        String source = """
                # legacy reference: print x and xrange and <>
                msg = "print x and xrange and <>"
                ok = True
                """;
        Path module = tmp.resolve("safe.py");
        Files.writeString(module, source, StandardCharsets.UTF_8);

        PythonAdapter adapter = new PythonAdapter();
        SemanticModel model = adapter.buildSemanticModel(tmp);
        List<RefactorCandidate> candidates = adapter.listRefactorCandidates(
                model, LanguageAdapter.RefactorRuleSet.empty());

        assertTrue(candidates.isEmpty(),
                "no candidates should be produced from strings/comments, got: "
                        + candidates.stream().map(RefactorCandidate::ruleId).toList());
    }
}
