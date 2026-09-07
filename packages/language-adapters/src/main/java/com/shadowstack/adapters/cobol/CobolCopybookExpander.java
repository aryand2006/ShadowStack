package com.shadowstack.adapters.cobol;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Expands COBOL {@code COPY} directives (simple form) before semantic rehost.
 *
 * <p>Supports {@code COPY name} and {@code COPY name REPLACING ==x== BY ==y==}.
 * Searches {@code sourceRoot} and {@code sourceRoot/copybooks} for
 * {@code .cpy}/{@code .CPY}/{@code .cob}. Missing copybooks are recorded as gaps.
 * Not a full preprocessor — nested programs and library-name OF/IN are out of scope.</p>
 */
public final class CobolCopybookExpander {

    private static final Pattern COPY_STMT = Pattern.compile(
            "(?i)\\bCOPY\\s+([A-Z0-9][A-Z0-9-]*)"
                    + "(?:\\s+REPLACING\\s+==([^=]+)==\\s+BY\\s+==([^=]+)==)?"
                    + "\\s*\\.?");

    private CobolCopybookExpander() {}

    /**
     * Expand COPY directives in {@code source}, appending gap messages for missing
     * copybooks or cycles.
     */
    public static String expand(String source, Path sourceRoot, List<String> gaps) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(gaps, "gaps");
        if (sourceRoot == null) {
            return source;
        }
        return expandRecursive(source, sourceRoot, gaps, new LinkedHashSet<>());
    }

    private static String expandRecursive(
            String source, Path sourceRoot, List<String> gaps, Set<String> stack) {
        Matcher m = COPY_STMT.matcher(source);
        StringBuilder out = new StringBuilder();
        int last = 0;
        while (m.find()) {
            out.append(source, last, m.start());
            String name = m.group(1).toUpperCase(Locale.ROOT);
            String from = m.group(2);
            String to = m.group(3);
            if (!stack.add(name)) {
                gaps.add("COPY cycle: " + name);
                out.append("*>> COPY cycle skipped: ").append(name).append('\n');
                last = m.end();
                continue;
            }
            Path found = locateCopybook(sourceRoot, name);
            if (found == null) {
                gaps.add("Missing copybook: " + name);
                out.append("*>> Missing COPY ").append(name).append('\n');
                stack.remove(name);
                last = m.end();
                continue;
            }
            String body;
            try {
                body = Files.readString(found, StandardCharsets.UTF_8);
            } catch (IOException e) {
                gaps.add("Unreadable copybook: " + name + " (" + e.getMessage() + ")");
                out.append("*>> Unreadable COPY ").append(name).append('\n');
                stack.remove(name);
                last = m.end();
                continue;
            }
            if (from != null && to != null) {
                body = body.replace(from, to);
            }
            String expanded = expandRecursive(body, sourceRoot, gaps, stack);
            out.append('\n').append(expanded).append('\n');
            stack.remove(name);
            last = m.end();
        }
        out.append(source, last, source.length());
        return out.toString();
    }

    static Path locateCopybook(Path sourceRoot, String name) {
        String base = name;
        List<Path> dirs = new ArrayList<>();
        dirs.add(sourceRoot);
        Path copybooks = sourceRoot.resolve("copybooks");
        if (Files.isDirectory(copybooks)) {
            dirs.add(copybooks);
        }
        String[] exts = {".cpy", ".CPY", ".cob", ".COB", ".cbl", ".CBL"};
        for (Path dir : dirs) {
            for (String ext : exts) {
                Path p = dir.resolve(base + ext);
                if (Files.isRegularFile(p)) return p;
                Path lower = dir.resolve(base.toLowerCase(Locale.ROOT) + ext);
                if (Files.isRegularFile(lower)) return lower;
            }
            // Bare name match (already includes extension in COPY token — rare)
            Path bare = dir.resolve(base);
            if (Files.isRegularFile(bare)) return bare;
        }
        return null;
    }
}
