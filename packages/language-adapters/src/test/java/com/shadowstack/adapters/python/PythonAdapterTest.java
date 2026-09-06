package com.shadowstack.adapters.python;

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

class PythonAdapterTest {

    private static final String PY2_SOURCE = """
            import urllib2
            import ConfigParser
            import Queue
            import thread

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
                        name = raw_input("n")
                        if d.has_key(name):
                            return d[name]
                        n = long(1)
                        apply(str, (n,))
                        data = file("x").read()
                        execfile("y.py")
                        msg = u"ok"
                        return urllib2.urlopen("https://example.invalid").read()
                    except Exception, e:
                        raise ValueError, e
            """;

    @Test
    void parses_and_detects_every_python2_rule(@TempDir Path tmp) throws Exception {
        Path module = tmp.resolve("counter.py");
        Files.writeString(module, PY2_SOURCE, StandardCharsets.UTF_8);

        PythonAdapter adapter = new PythonAdapter();
        assertEquals("python", adapter.languageId());
        assertEquals("3.12", adapter.languageVersion());

        SemanticModel model = adapter.buildSemanticModel(tmp);
        assertEquals(1, model.classCount());
        assertTrue(model.methodCount() >= 2);

        List<RefactorCandidate> candidates = adapter.listRefactorCandidates(
                model, LanguageAdapter.RefactorRuleSet.empty());
        Set<String> ruleIds = candidates.stream()
                .map(RefactorCandidate::ruleId)
                .collect(Collectors.toSet());

        assertTrue(ruleIds.contains("py.print_stmt_to_call"));
        assertTrue(ruleIds.contains("py.xrange_to_range"));
        assertTrue(ruleIds.contains("py.iter_methods_to_views"));
        assertTrue(ruleIds.contains("py.except_comma_to_as"));
        assertTrue(ruleIds.contains("py.unicode_to_str"));
        assertTrue(ruleIds.contains("py.ne_operator"));
        assertTrue(ruleIds.contains("py.has_key_to_in"));
        assertTrue(ruleIds.contains("py.raw_input_to_input"));
        assertTrue(ruleIds.contains("py.long_to_int"));
        assertTrue(ruleIds.contains("py.raise_comma_to_call"));
        assertTrue(ruleIds.contains("py.file_to_open"));
        assertTrue(ruleIds.contains("py.apply_to_starcall"));
        assertTrue(ruleIds.contains("py.import_urllib2"));
        assertTrue(ruleIds.contains("py.import_configparser"));
        assertTrue(ruleIds.contains("py.import_queue"));
        assertTrue(ruleIds.contains("py.import_thread"));
        assertTrue(ruleIds.contains("py.execfile_to_exec"));
        assertTrue(ruleIds.contains("py.unicode_literal_prefix"));
    }

    @Test
    void applies_print_rule_and_writes_diff(@TempDir Path tmp) throws Exception {
        Path module = tmp.resolve("hello.py");
        Files.writeString(module, "print \"hi\"\n", StandardCharsets.UTF_8);

        PythonAdapter adapter = new PythonAdapter();
        SemanticModel model = adapter.buildSemanticModel(tmp);
        RefactorCandidate printCandidate = adapter.listRefactorCandidates(
                        model, LanguageAdapter.RefactorRuleSet.empty()).stream()
                .filter(c -> "py.print_stmt_to_call".equals(c.ruleId()))
                .findFirst().orElseThrow();

        PatchResult patch = adapter.applyRefactor(printCandidate, tmp);
        assertTrue(patch.success());
        assertTrue(patch.unifiedDiff().contains("-print \"hi\""));
        assertTrue(patch.unifiedDiff().contains("+print(\"hi\")"));
        assertTrue(patch.hasStructuralChange());
        assertEquals("print(\"hi\")\n", Files.readString(module));

        VerificationResult vr = adapter.verifyPatch(patch, tmp,
                LanguageAdapter.VerificationConfig.quick());
        assertNotNull(vr.verdict());
    }

    @Test
    void ignores_patterns_inside_strings_and_comments(@TempDir Path tmp) throws Exception {
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
        assertTrue(candidates.isEmpty(), () -> candidates.stream()
                .map(RefactorCandidate::ruleId).toList().toString());
    }
}
