package com.shadowstack.adapters.cobol;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Golden stdout for HELLOSS, BATCHIO, DRIVER/WORKER under examples/legacy-cobol/golden/.
 */
class CobolGoldenStdoutIT {

    @Test
    void helloss_matches_golden(@TempDir Path tmp) throws Exception {
        assertMatchesGolden("HELLOSS.cob", "HELLOSS.stdout.txt", tmp);
    }

    @Test
    void batchio_matches_golden(@TempDir Path tmp) throws Exception {
        assertMatchesGolden("BATCHIO.cob", "BATCHIO.stdout.txt", tmp);
    }

    @Test
    void driver_worker_matches_golden(@TempDir Path tmp) throws Exception {
        Path root = findExamplesRoot();
        org.junit.jupiter.api.Assumptions.assumeTrue(root != null);
        Files.copy(root.resolve("DRIVER.cob"), tmp.resolve("DRIVER.cob"));
        Files.copy(root.resolve("WORKER.cob"), tmp.resolve("WORKER.cob"));
        CobolProjectTranslator.translateAll(tmp);
        Process compile = new ProcessBuilder("javac",
                "TranslatedDRIVER.java", "TranslatedWORKER.java")
                .directory(tmp.toFile()).redirectErrorStream(true).start();
        String cout = new String(compile.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(compile.waitFor(60, TimeUnit.SECONDS));
        assertEquals(0, compile.exitValue(), cout);
        Process run = new ProcessBuilder("java", "TranslatedDRIVER")
                .directory(tmp.toFile()).redirectErrorStream(true).start();
        String out = new String(run.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(run.waitFor(30, TimeUnit.SECONDS));
        assertEquals(0, run.exitValue(), out);
        String expected = Files.readString(root.resolve("golden/DRIVER.stdout.txt"), StandardCharsets.UTF_8);
        assertEquals(normalize(expected), normalize(out));
    }

    private static void assertMatchesGolden(String cobFile, String goldenFile, Path tmp) throws Exception {
        Path root = findExamplesRoot();
        org.junit.jupiter.api.Assumptions.assumeTrue(root != null);
        String src = Files.readString(root.resolve(cobFile), StandardCharsets.UTF_8);
        var r = CobolToJavaTranslator.translate(src);
        Path javaFile = tmp.resolve(r.className() + ".java");
        Files.writeString(javaFile, r.javaSource(), StandardCharsets.UTF_8);
        Process compile = new ProcessBuilder("javac", javaFile.getFileName().toString())
                .directory(tmp.toFile()).redirectErrorStream(true).start();
        String cout = new String(compile.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(compile.waitFor(60, TimeUnit.SECONDS));
        assertEquals(0, compile.exitValue(), cout);
        Process run = new ProcessBuilder("java", r.className())
                .directory(tmp.toFile()).redirectErrorStream(true).start();
        String out = new String(run.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(run.waitFor(30, TimeUnit.SECONDS));
        assertEquals(0, run.exitValue(), out);
        String expected = Files.readString(root.resolve("golden").resolve(goldenFile), StandardCharsets.UTF_8);
        assertEquals(normalize(expected), normalize(out));
    }

    private static String normalize(String s) {
        return s.replace("\r\n", "\n").trim() + "\n";
    }

    private static Path findExamplesRoot() {
        Path p = Path.of("examples/legacy-cobol");
        if (Files.isDirectory(p)) return p.toAbsolutePath();
        p = Path.of("../examples/legacy-cobol");
        if (Files.isDirectory(p)) return p.toAbsolutePath().normalize();
        p = Path.of("../../examples/legacy-cobol");
        if (Files.isDirectory(p)) return p.toAbsolutePath().normalize();
        return null;
    }
}
