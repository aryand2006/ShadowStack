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
            import cPickle
            import cStringIO
            import __builtin__

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
                        ch = unichr(65)
                        reload(sys)
                        intern(name)
                        it = iter(rows)
                        nxt = it.next()
                        label = `n`
                        raise StandardError, "boom"
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
        assertTrue(ruleIds.contains("py.unichr_to_chr"));
        assertTrue(ruleIds.contains("py.reload_to_importlib"));
        assertTrue(ruleIds.contains("py.intern_to_sys"));
        assertTrue(ruleIds.contains("py.standarderror_to_exception"));
        assertTrue(ruleIds.contains("py.next_method_to_builtin"));
        assertTrue(ruleIds.contains("py.backtick_to_repr"));
        assertTrue(ruleIds.contains("py.import_cpickle"));
        assertTrue(ruleIds.contains("py.import_cstringio"));
        assertTrue(ruleIds.contains("py.import_builtin"));
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
        assertEquals(VerificationResult.Verdict.PASS, vr.verdict(),
                () -> String.valueOf(vr.layerResults()));
    }

    @Test
    void verify_hardFails_whenPythonMissing(@TempDir Path tmp) throws Exception {
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

        String prev = System.getProperty("shadowstack.verify.python3");
        System.setProperty("shadowstack.verify.python3", "/nonexistent/shadowstack-python3");
        try {
            VerificationResult vr = adapter.verifyPatch(
                    patch, tmp, LanguageAdapter.VerificationConfig.defaults());
            assertEquals(VerificationResult.Verdict.FAIL, vr.verdict(),
                    () -> String.valueOf(vr.layerResults()));
            assertTrue(vr.layerResults().stream()
                            .anyMatch(l -> "compilation".equals(l.layerName())
                                    && !l.passed()
                                    && l.details() != null
                                    && l.details().contains("python3 not available")),
                    () -> String.valueOf(vr.layerResults()));
        } finally {
            if (prev == null) {
                System.clearProperty("shadowstack.verify.python3");
            } else {
                System.setProperty("shadowstack.verify.python3", prev);
            }
        }
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

    @Test
    void printChevron_detects(@TempDir Path tmp) throws Exception {
        assertDetects(tmp, "legacy_print_chevron.py",
                "print >>sys.stderr, 'err'\n", "py.print_chevron_to_file");
    }

    @Test
    void itertoolsAliases_detect(@TempDir Path tmp) throws Exception {
        Set<String> ids = detectIds(tmp, "legacy_itertools.py",
                "from itertools import imap, izip, ifilter\n");
        assertTrue(ids.contains("py.imap_to_map"), ids.toString());
        assertTrue(ids.contains("py.izip_to_zip"), ids.toString());
        assertTrue(ids.contains("py.ifilter_to_filter"), ids.toString());
    }

    @Test
    void reduceBuiltin_detects(@TempDir Path tmp) throws Exception {
        assertDetects(tmp, "legacy_reduce.py",
                "total = reduce(lambda a, b: a + b, [1, 2, 3])\n",
                "py.reduce_to_functools");
    }

    @Test
    void moreLegacyImports_detect(@TempDir Path tmp) throws Exception {
        String source = String.join("\n",
                "import commands",
                "import urlparse",
                "import httplib",
                "import BaseHTTPServer",
                "import md5",
                "import sha",
                "import sets",
                "import UserDict",
                "import robotparser",
                "");
        Set<String> ids = detectIds(tmp, "legacy_more_imports.py", source);
        assertTrue(ids.contains("py.import_commands"), ids.toString());
        assertTrue(ids.contains("py.import_urlparse"), ids.toString());
        assertTrue(ids.contains("py.import_httplib"), ids.toString());
        assertTrue(ids.contains("py.import_basehttpserver"), ids.toString());
        assertTrue(ids.contains("py.import_md5"), ids.toString());
        assertTrue(ids.contains("py.import_sha"), ids.toString());
        assertTrue(ids.contains("py.import_sets"), ids.toString());
        assertTrue(ids.contains("py.import_userdict"), ids.toString());
        assertTrue(ids.contains("py.import_robotparser"), ids.toString());
    }

    private static void assertDetects(Path tmp, String name, String source, String ruleId)
            throws Exception {
        Set<String> ids = detectIds(tmp, name, source);
        assertTrue(ids.contains(ruleId), ids.toString());
    }

    private static Set<String> detectIds(Path tmp, String name, String source) throws Exception {
        Path module = tmp.resolve(name);
        Files.writeString(module, source, StandardCharsets.UTF_8);
        PythonAdapter adapter = new PythonAdapter();
        SemanticModel model = adapter.buildSemanticModel(tmp);
        return adapter.listRefactorCandidates(model, LanguageAdapter.RefactorRuleSet.empty())
                .stream().map(RefactorCandidate::ruleId).collect(Collectors.toSet());
    }


    @Test
    void applies_and_verifies_against_legacy_sample(@TempDir Path tmp) throws Exception {
        Path sample = Path.of("examples/legacy-python/report_builder.py").toAbsolutePath().normalize();
        if (!Files.isRegularFile(sample)) {
            sample = Path.of("/workspace/examples/legacy-python/report_builder.py");
        }
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.isRegularFile(sample));
        Files.writeString(tmp.resolve("report_builder.py"),
                Files.readString(sample, StandardCharsets.UTF_8), StandardCharsets.UTF_8);

        PythonAdapter adapter = new PythonAdapter();
        SemanticModel model = adapter.buildSemanticModel(tmp);
        RefactorCandidate candidate = adapter.listRefactorCandidates(
                        model, LanguageAdapter.RefactorRuleSet.empty()).stream()
                .filter(c -> c.ruleId().contains("print"))
                .findFirst()
                .orElseThrow();
        PatchResult patch = adapter.applyRefactor(candidate, tmp);
        assertTrue(patch.success(), () -> String.valueOf(patch.errorMessage()));
        String updated = Files.readString(tmp.resolve("report_builder.py"), StandardCharsets.UTF_8);
        assertTrue(updated.contains("print(") || !updated.equals(
                Files.readString(sample, StandardCharsets.UTF_8)), updated);
        VerificationResult vr = adapter.verifyPatch(
                patch, tmp, LanguageAdapter.VerificationConfig.defaults());
        assertNotNull(vr.verdict());
        // Full legacy samples remain Python-2-invalid after a single rule apply;
        // verification must still return a deterministic verdict/layers.
        assertFalse(vr.layerResults().isEmpty());
    }
}
