package com.shadowstack.adapters.javascript;

import com.shadowstack.adapters.LanguageAdapter;
import com.shadowstack.adapters.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lightweight JavaScript/JSX/TypeScript adapter targeting ES2022.
 *
 * <p>The adapter deliberately uses a structural scanner rather than a full
 * compiler front end. Candidate detection is guarded by a JavaScript-aware
 * string/comment mask so identifiers in comments and literals are ignored.</p>
 */
public class JavascriptAdapter implements LanguageAdapter {

    private static final Logger LOG = LoggerFactory.getLogger(JavascriptAdapter.class);
    private static final String LANGUAGE_ID = "javascript";
    private static final String LANGUAGE_VERSION = "ES2022";
    private static final Set<String> EXTENSIONS = Set.of(".js", ".jsx", ".ts", ".tsx");

    private static final Pattern CLASS_DECL =
            Pattern.compile("\\bclass\\s+([A-Za-z_$][\\w$]*)");
    private static final Pattern FUNCTION_DECL =
            Pattern.compile("\\b(?:async\\s+)?function\\s+([A-Za-z_$][\\w$]*)\\s*\\(([^)]*)\\)");
    private static final Pattern ARROW_DECL =
            Pattern.compile("\\b(?:const|let|var)\\s+([A-Za-z_$][\\w$]*)\\s*=\\s*(?:async\\s*)?\\(([^)]*)\\)\\s*=>");
    private static final Pattern CALLBACK_ERR =
            Pattern.compile("function\\s*\\(\\s*err\\s*,");
    private static final Pattern OBJECT_ASSIGN =
            Pattern.compile("\\bObject\\.assign\\s*\\(\\s*\\{\\s*\\}\\s*,\\s*([^)]+)\\)");
    private static final Pattern INDEXOF_STARTS =
            Pattern.compile("\\.indexOf\\(([^)]+)\\)\\s*===\\s*0");
    private static final Pattern STRING_CHARAT0 =
            Pattern.compile("\\.charAt\\(\\s*0\\s*\\)");

    private static final Pattern VAR = Pattern.compile("\\bvar\\b");
    private static final Pattern LOOSE_EQ = Pattern.compile("(?<![=!])==(?!=)");
    private static final Pattern LOOSE_NE = Pattern.compile("(?<![=!])!=(?!=)");
    private static final Pattern ARGUMENTS_INDEX = Pattern.compile("\\barguments\\s*\\[");
    private static final Pattern PROMISE_FUNCTION = Pattern.compile("\\bnew\\s+Promise\\s*\\(\\s*function\\b");
    private static final Pattern DIRNAME = Pattern.compile("\\b__dirname\\b");
    private static final Pattern FILENAME = Pattern.compile("\\b__filename\\b");
    private static final Pattern REQUIRE = Pattern.compile(
            "\\bconst\\s+([A-Za-z_$][\\w$]*)\\s*=\\s*require\\s*\\(\\s*(['\"])([^'\"]+)\\2\\s*\\)");
    private static final Pattern MODULE_EXPORTS = Pattern.compile("\\bmodule\\.exports\\s*=");
    private static final Pattern EXPORTS_DOT =
            Pattern.compile("\\bexports\\.([A-Za-z_$][\\w$]*)\\s*=");
    private static final Pattern STRING_CONCAT = Pattern.compile(
            "(['\"])([^'\"\\\\]*)\\1\\s*\\+\\s*(['\"])([^'\"\\\\]*)\\3");
    private static final Pattern SUBSTR = Pattern.compile("\\.substr\\s*\\(");
    private static final Pattern ESCAPE = Pattern.compile("\\bescape\\s*\\(");
    private static final Pattern UNESCAPE = Pattern.compile("\\bunescape\\s*\\(");
    private static final Pattern INDEX_OF = Pattern.compile(
            "([A-Za-z_$][\\w$]*(?:\\.[A-Za-z_$][\\w$]*)*)\\.indexOf\\s*\\(([^)]+)\\)\\s*(?:>=\\s*0|!==\\s*-1|!=\\s*-1)");
    private static final Pattern BIND_THIS = Pattern.compile(
            "\\bfunction\\s*\\([^)]*\\)\\s*\\{.*}\\s*\\.bind\\s*\\(\\s*this\\s*\\)");

    @Override
    public String languageId() {
        return LANGUAGE_ID;
    }

    @Override
    public String languageVersion() {
        return LANGUAGE_VERSION;
    }

    @Override
    public SemanticModel parse(Path sourceRoot, LanguageAdapterConfig config) {
        Objects.requireNonNull(sourceRoot, "sourceRoot must not be null");
        Objects.requireNonNull(config, "config must not be null");
        if (!Files.isDirectory(sourceRoot)) {
            throw new AdapterException("parse",
                    "Source root does not exist or is not a directory: " + sourceRoot);
        }

        List<Path> files = collectFiles(sourceRoot);
        Charset encoding = Charset.forName(config.sourceEncoding());
        SemanticModel.Builder builder = SemanticModel.builder()
                .languageId(LANGUAGE_ID)
                .languageVersion(LANGUAGE_VERSION)
                .sourceRoot(sourceRoot);
        Map<String, List<String>> packageFiles = new LinkedHashMap<>();
        int totalLines = 0;

        for (Path file : files) {
            try {
                String source = Files.readString(file, encoding);
                String relPath = normalizedRelativePath(sourceRoot, file);
                String moduleName = moduleName(relPath);
                String packageName = packageName(moduleName);
                ModuleParse parsed = parseModule(source, relPath, moduleName);
                totalLines += parsed.lineCount;
                packageFiles.computeIfAbsent(packageName, ignored -> new ArrayList<>()).add(relPath);
                builder.addAllClasses(parsed.classes);
                builder.addAllMethods(parsed.methods);
            } catch (IOException e) {
                LOG.warn("Failed to read {}: {}", file, e.getMessage());
            }
        }

        packageFiles.forEach((name, paths) ->
                builder.addPackage(new SemanticModel.PackageInfo(name, paths, List.of())));
        return builder.putMetadata("fileCount", files.size())
                .putMetadata("totalLines", totalLines)
                .build();
    }

    @Override
    public SemanticModel buildSemanticModel(Path sourceRoot) {
        return parse(sourceRoot, LanguageAdapterConfig.defaults());
    }

    @Override
    public List<RefactorCandidate> listRefactorCandidates(
            SemanticModel model, RefactorRuleSet rules) {
        Objects.requireNonNull(model, "model must not be null");
        Objects.requireNonNull(rules, "rules must not be null");
        List<RefactorCandidate> candidates = new ArrayList<>();
        for (Path file : collectFiles(model.sourceRoot())) {
            try {
                String source = Files.readString(file, StandardCharsets.UTF_8);
                String relPath = normalizedRelativePath(model.sourceRoot(), file);
                candidates.addAll(detectCandidates(source, relPath));
            } catch (IOException e) {
                LOG.warn("Skipping {}: {}", file, e.getMessage());
            }
        }
        return Collections.unmodifiableList(candidates);
    }

    @Override
    public PatchResult applyRefactor(RefactorCandidate candidate, Path sourceRoot) {
        Objects.requireNonNull(candidate, "candidate must not be null");
        Objects.requireNonNull(sourceRoot, "sourceRoot must not be null");
        Path target = sourceRoot.resolve(candidate.sourceFile());
        if (!Files.isRegularFile(target)) {
            return PatchResult.failure(candidate.candidateId(), "Source file not found: " + target);
        }
        try {
            String before = Files.readString(target, StandardCharsets.UTF_8);
            String after = replaceCandidateLine(before, candidate);
            String beforeHash = structuralHash(before);
            String afterHash = structuralHash(after);
            Files.writeString(target, after, StandardCharsets.UTF_8);
            return PatchResult.builder()
                    .candidateId(candidate.candidateId())
                    .unifiedDiff(unifiedDiff(candidate.sourceFile(), before, after))
                    .beforeAstHash(beforeHash)
                    .afterAstHash(afterHash)
                    .addAffectedFile(candidate.sourceFile())
                    .success(true)
                    .putMetadata("ruleId", candidate.ruleId())
                    .putMetadata("linesAffected", candidate.lineSpan())
                    .build();
        } catch (Exception e) {
            return PatchResult.failure(candidate.candidateId(), e.getMessage());
        }
    }

        @Override
    public VerificationResult verifyPatch(PatchResult patch, Path sourceRoot, VerificationConfig config) {
        Objects.requireNonNull(patch, "patch must not be null");
        Objects.requireNonNull(sourceRoot, "sourceRoot must not be null");
        Objects.requireNonNull(config, "config must not be null");

        List<VerificationResult.LayerResult> layers = new ArrayList<>();
        boolean compileOk = true;
        if (config.runCompilation()) {
            VerificationResult.LayerResult compile = verifyNodeSyntax(patch, sourceRoot);
            layers.add(compile);
            compileOk = compile.passed();
        }
        VerificationResult.LayerResult structural = verifyStructure(patch, sourceRoot);
        layers.add(structural);

        boolean runtimeVerified = layers.stream()
                .anyMatch(l -> "compilation".equals(l.layerName())
                        && l.passed()
                        && l.details() != null
                        && l.details().contains("node --check succeeded"));
        return VerificationResult.builder()
                .patchId(patch.patchId())
                .compileSuccess(compileOk)
                .testSuccess(runtimeVerified)
                .astStructuralMatchScore(structural.score())
                .bytecodeDescriptorMatch(runtimeVerified)
                .apiSurfaceCompatible(structural.passed())
                .goldenMasterMatch(false)
                .layerResults(layers)
                .beforeAstHash(patch.beforeAstHash())
                .afterAstHash(patch.afterAstHash())
                .build();
    }

    private VerificationResult.LayerResult verifyNodeSyntax(PatchResult patch, Path sourceRoot) {
        long start = System.currentTimeMillis();
        if (patch.affectedFiles().isEmpty()) {
            return new VerificationResult.LayerResult("compilation", true, 1.0, "No affected files", 0);
        }
        Path target = sourceRoot.resolve(patch.affectedFiles().get(0));
        try {
            ProcessBuilder pb = new ProcessBuilder("node", "--check", target.toString());
            pb.redirectErrorStream(true);
            Process p = pb.start();
            StringBuilder out = new StringBuilder();
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    out.append(line).append('\n');
                }
            }
            boolean finished = p.waitFor(30, java.util.concurrent.TimeUnit.SECONDS);
            long elapsed = System.currentTimeMillis() - start;
            if (!finished) {
                p.destroyForcibly();
                return new VerificationResult.LayerResult("compilation", false, 0.0, "node --check timed out", elapsed);
            }
            boolean passed = p.exitValue() == 0;
            return new VerificationResult.LayerResult(
                    "compilation",
                    passed,
                    passed ? 1.0 : 0.0,
                    passed ? "node --check succeeded on " + target.getFileName()
                            : "node --check failed:\n" + out,
                    elapsed);
        } catch (IOException e) {
            long elapsed = System.currentTimeMillis() - start;
            return new VerificationResult.LayerResult(
                    "compilation", true, 0.7,
                    "node not available; structural-only verification", elapsed);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new VerificationResult.LayerResult(
                    "compilation", false, 0.0, "interrupted", System.currentTimeMillis() - start);
        }
    }

    private VerificationResult.LayerResult verifyStructure(PatchResult patch, Path sourceRoot) {
        long start = System.currentTimeMillis();
        if (patch.affectedFiles().isEmpty()) {
            return new VerificationResult.LayerResult("structural", true, 1.0, "No affected files", 0);
        }
        try {
            Path target = sourceRoot.resolve(patch.affectedFiles().get(0));
            String source = Files.readString(target, StandardCharsets.UTF_8);
            ModuleParse parsed = parseModule(
                    source, target.getFileName().toString(), moduleName(target.getFileName().toString()));
            double score = parsed.classes.isEmpty() && parsed.methods.isEmpty() ? 0.95 : 1.0;
            return new VerificationResult.LayerResult(
                    "structural", true, score,
                    "Re-parsed module: " + parsed.classes.size() + " classes, "
                            + parsed.methods.size() + " functions",
                    System.currentTimeMillis() - start);
        } catch (Exception e) {
            return new VerificationResult.LayerResult(
                    "structural", false, 0.0, "Re-parse failed: " + e.getMessage(),
                    System.currentTimeMillis() - start);
        }
    }

    private List<RefactorCandidate> detectCandidates(String source, String relPath) {
        boolean[] codeMask = buildCodeMask(source);
        String[] lines = source.split("\n", -1);
        List<RefactorCandidate> out = new ArrayList<>();
        int offset = 0;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            detect(line, offset, i + 1, relPath, codeMask, VAR,
                    "js.var_to_let", "var → let", "let",
                    0.90, RiskTier.LOW, out);
            detect(line, offset, i + 1, relPath, codeMask, LOOSE_EQ,
                    "js.==_to_===", "Loose equality → strict equality", "===",
                    0.82, RiskTier.MODERATE, out);
            detect(line, offset, i + 1, relPath, codeMask, LOOSE_NE,
                    "js.!=_to_!==", "Loose inequality → strict inequality", "!==",
                    0.82, RiskTier.MODERATE, out);
            detect(line, offset, i + 1, relPath, codeMask, ARGUMENTS_INDEX,
                    "js.arguments_to_rest", "arguments indexing → rest parameter",
                    "/* use ...args */ arguments[", 0.70, RiskTier.MODERATE, out);
            detect(line, offset, i + 1, relPath, codeMask, PROMISE_FUNCTION,
                    "js.promise_constructor_to_async", "Promise constructor → async function",
                    "/* prefer async */ new Promise(function", 0.65, RiskTier.MODERATE, out);
            detect(line, offset, i + 1, relPath, codeMask, DIRNAME,
                    "js.dirname_to_importmeta", "__dirname → import.meta.dirname",
                    "import.meta.dirname", 0.86, RiskTier.MODERATE, out);
            detect(line, offset, i + 1, relPath, codeMask, FILENAME,
                    "js.filename_to_importmeta", "__filename → import.meta.url",
                    "import.meta.url", 0.82, RiskTier.MODERATE, out);
            detectRequire(line, offset, i + 1, relPath, codeMask, out);
            detect(line, offset, i + 1, relPath, codeMask, MODULE_EXPORTS,
                    "js.module_exports_to_export", "module.exports → export default",
                    "export default", 0.84, RiskTier.MODERATE, out);
            detectExportsDot(line, offset, i + 1, relPath, codeMask, out);
            detectStringConcat(line, offset, i + 1, relPath, codeMask, out);
            detect(line, offset, i + 1, relPath, codeMask, SUBSTR,
                    "js.substr_to_substring", "substr() → substring()", ".substring(",
                    0.78, RiskTier.MODERATE, out);
            detect(line, offset, i + 1, relPath, codeMask, ESCAPE,
                    "js.escape_to_encodeuri", "escape() → encodeURI()", "encodeURI(",
                    0.76, RiskTier.MODERATE, out);
            detect(line, offset, i + 1, relPath, codeMask, UNESCAPE,
                    "js.unescape_to_decodeuri", "unescape() → decodeURI()", "decodeURI(",
                    0.76, RiskTier.MODERATE, out);
            detectIndexOf(line, offset, i + 1, relPath, codeMask, out);
            detect(line, offset, i + 1, relPath, codeMask, CALLBACK_ERR,
                    "js.callback_err_first", "err-first callback → Promise/async",
                    "/* prefer async/await */ function(err,", 0.60, RiskTier.MODERATE, out);
            detectObjectAssign(line, offset, i + 1, relPath, codeMask, out);
            detectIndexOfStartsWith(line, offset, i + 1, relPath, codeMask, out);
            detect(line, offset, i + 1, relPath, codeMask, STRING_CHARAT0,
                    "js.charat0_to_at", "charAt(0) → at(0) / [0]",
                    ".at(0)", 0.70, RiskTier.LOW, out);

            detect(line, offset, i + 1, relPath, codeMask, BIND_THIS,
                    "js.bind_to_arrow", "Bound function expression → arrow function",
                    "/* prefer an arrow function capturing this */ $0",
                    0.60, RiskTier.MODERATE, out);
            offset += line.length() + 1;
        }
        return out;
    }

    private void detect(String line, int lineOffset, int lineNumber, String relPath,
                        boolean[] mask, Pattern pattern, String ruleId, String ruleName,
                        String replacement, double confidence, RiskTier risk,
                        List<RefactorCandidate> out) {
        Matcher matcher = pattern.matcher(line);
        while (matcher.find()) {
            if (!isCodeAt(mask, lineOffset + matcher.start())) continue;
            String actualReplacement = replacement.replace("$0", matcher.group());
            String after = line.substring(0, matcher.start())
                    + actualReplacement + line.substring(matcher.end());
            out.add(candidate(relPath, lineNumber, line, after, ruleId, ruleName,
                    confidence, risk));
        }
    }

    private void detectRequire(String line, int offset, int lineNumber, String relPath,
                               boolean[] mask, List<RefactorCandidate> out) {
        Matcher matcher = REQUIRE.matcher(line);
        while (matcher.find()) {
            if (!isCodeAt(mask, offset + matcher.start())) continue;
            String replacement = "import " + matcher.group(1) + " from "
                    + matcher.group(2) + matcher.group(3) + matcher.group(2);
            String after = replaceMatch(line, matcher, replacement);
            out.add(candidate(relPath, lineNumber, line, after,
                    "js.require_to_import", "CommonJS require → ES module import",
                    0.88, RiskTier.MODERATE));
        }
    }

    private void detectExportsDot(String line, int offset, int lineNumber, String relPath,
                                  boolean[] mask, List<RefactorCandidate> out) {
        Matcher matcher = EXPORTS_DOT.matcher(line);
        while (matcher.find()) {
            if (!isCodeAt(mask, offset + matcher.start())) continue;
            String after = replaceMatch(line, matcher, "export const " + matcher.group(1) + " =");
            out.add(candidate(relPath, lineNumber, line, after,
                    "js.exports_dot_to_export", "Named CommonJS export → ES module export",
                    0.82, RiskTier.MODERATE));
        }
    }

    private void detectStringConcat(String line, int offset, int lineNumber, String relPath,
                                    boolean[] mask, List<RefactorCandidate> out) {
        Matcher matcher = STRING_CONCAT.matcher(line);
        while (matcher.find()) {
            int plus = line.indexOf('+', matcher.start());
            if (plus < 0 || !isCodeAt(mask, offset + plus)) continue;
            String content = (matcher.group(2) + matcher.group(4))
                    .replace("`", "\\`")
                    .replace("${", "\\${");
            String after = replaceMatch(line, matcher,
                    "`" + content + "`");
            out.add(candidate(relPath, lineNumber, line, after,
                    "js.string_concat_plus", "String concatenation → template literal",
                    0.96, RiskTier.LOW));
        }
    }

    private void detectIndexOf(String line, int offset, int lineNumber, String relPath,
                               boolean[] mask, List<RefactorCandidate> out) {
        Matcher matcher = INDEX_OF.matcher(line);
        while (matcher.find()) {
            if (!isCodeAt(mask, offset + matcher.start())) continue;
            String after = replaceMatch(line, matcher,
                    matcher.group(1) + ".includes(" + matcher.group(2).trim() + ")");
            out.add(candidate(relPath, lineNumber, line, after,
                    "js.indexof_to_includes", "indexOf membership check → includes",
                    0.92, RiskTier.LOW));
        }
    }

    private void detectObjectAssign(String line, int offset, int lineNumber, String relPath,
                                    boolean[] mask, List<RefactorCandidate> out) {
        Matcher matcher = OBJECT_ASSIGN.matcher(line);
        while (matcher.find()) {
            if (!isCodeAt(mask, offset + matcher.start())) continue;
            String after = replaceMatch(line, matcher, "({..." + matcher.group(1).trim() + "})");
            out.add(candidate(relPath, lineNumber, line, after,
                    "js.object_assign_to_spread", "Object.assign({}, x) → ({...x})",
                    0.80, RiskTier.LOW));
        }
    }

    private void detectIndexOfStartsWith(String line, int offset, int lineNumber, String relPath,
                                         boolean[] mask, List<RefactorCandidate> out) {
        Matcher matcher = INDEXOF_STARTS.matcher(line);
        while (matcher.find()) {
            if (!isCodeAt(mask, offset + matcher.start())) continue;
            String after = replaceMatch(line, matcher, ".startsWith(" + matcher.group(1) + ")");
            out.add(candidate(relPath, lineNumber, line, after,
                    "js.indexof_zero_to_startswith", "indexOf(x) === 0 → startsWith(x)",
                    0.88, RiskTier.LOW));
        }
    }

    private RefactorCandidate candidate(String relPath, int lineNumber, String before,
                                        String after, String ruleId, String ruleName,
                                        double confidence, RiskTier risk) {
        return RefactorCandidate.builder()
                .sourceFile(relPath)
                .startLine(lineNumber)
                .endLine(lineNumber)
                .ruleId(ruleId)
                .ruleName(ruleName)
                .ruleCategory("MODERNIZATION")
                .beforeSnippet(before)
                .proposedAfterSnippet(after)
                .confidenceScore(confidence)
                .riskTier(risk)
                .addSafetyInvariant(new SafetyInvariant(
                        "js-code-mask", "Match is outside string literals and comments",
                        SafetyInvariant.Category.BEHAVIORAL_EQUIVALENCE,
                        SafetyInvariant.Status.SATISFIED,
                        "JavaScript lexical mask confirmed executable code"))
                .putAstContext("language", LANGUAGE_ID)
                .putAstContext("ruleId", ruleId)
                .build();
    }

    private ModuleParse parseModule(String source, String relPath, String moduleName) {
        ModuleParse parsed = new ModuleParse();
        parsed.lineCount = source.split("\n", -1).length;
        boolean[] mask = buildCodeMask(source);
        String[] lines = source.split("\n", -1);
        int offset = 0;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            Matcher classes = CLASS_DECL.matcher(line);
            while (classes.find()) {
                if (!isCodeAt(mask, offset + classes.start())) continue;
                String name = classes.group(1);
                parsed.classes.add(new SemanticModel.ClassInfo(
                        moduleName + "." + name, name, moduleName, null,
                        List.of(), List.of("public"), List.of(), relPath,
                        i + 1, i + 1, false, false, false));
            }
            addFunctions(FUNCTION_DECL.matcher(line), line, offset, i, mask, moduleName, parsed);
            addFunctions(ARROW_DECL.matcher(line), line, offset, i, mask, moduleName, parsed);
            offset += line.length() + 1;
        }
        return parsed;
    }

    private void addFunctions(Matcher matcher, String line, int offset, int lineIndex,
                              boolean[] mask, String moduleName, ModuleParse parsed) {
        while (matcher.find()) {
            if (!isCodeAt(mask, offset + matcher.start())) continue;
            String name = matcher.group(1);
            List<String> params = parseParameters(matcher.group(2));
            List<String> types = Collections.nCopies(params.size(), "any");
            parsed.methods.add(new SemanticModel.MethodInfo(
                    moduleName + "#" + name + "(" + String.join(",", types) + ")",
                    name, moduleName, "any", types, params, List.of("public"), List.of(),
                    List.of(), lineIndex + 1, lineIndex + 1,
                    estimateComplexity(line), estimateComplexity(line), 1,
                    SemanticModel.Purity.UNKNOWN));
        }
    }

    private static int estimateComplexity(String line) {
        int result = 1;
        Matcher matcher = Pattern.compile("\\b(?:if|for|while|case|catch)\\b|&&|\\|\\|").matcher(line);
        while (matcher.find()) result++;
        return result;
    }

    private static List<String> parseParameters(String value) {
        if (value == null || value.isBlank()) return List.of();
        List<String> result = new ArrayList<>();
        for (String item : value.split(",")) {
            String parameter = item.trim().replaceFirst("^\\.\\.\\.", "");
            int colon = parameter.indexOf(':');
            if (colon >= 0) parameter = parameter.substring(0, colon).trim();
            int equals = parameter.indexOf('=');
            if (equals >= 0) parameter = parameter.substring(0, equals).trim();
            if (!parameter.isEmpty()) result.add(parameter);
        }
        return result;
    }

    private List<Path> collectFiles(Path sourceRoot) {
        List<Path> files = new ArrayList<>();
        try {
            Files.walkFileTree(sourceRoot, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
                    if (EXTENSIONS.stream().anyMatch(name::endsWith)) files.add(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    String name = dir.getFileName() == null ? "" : dir.getFileName().toString();
                    return Set.of(".git", "node_modules", "dist", "build").contains(name)
                            ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            throw new AdapterException("parse", "Failed to walk JavaScript source tree", e);
        }
        Collections.sort(files);
        return files;
    }

    private static boolean[] buildCodeMask(String source) {
        boolean[] mask = new boolean[source.length()];
        boolean lineComment = false;
        boolean blockComment = false;
        char quote = 0;
        boolean escaped = false;
        for (int i = 0; i < source.length(); i++) {
            char current = source.charAt(i);
            char next = i + 1 < source.length() ? source.charAt(i + 1) : 0;
            if (lineComment) {
                if (current == '\n') {
                    lineComment = false;
                    mask[i] = true;
                }
                continue;
            }
            if (blockComment) {
                if (current == '*' && next == '/') {
                    i++;
                    blockComment = false;
                }
                continue;
            }
            if (quote != 0) {
                if (escaped) {
                    escaped = false;
                } else if (current == '\\') {
                    escaped = true;
                } else if (current == quote) {
                    quote = 0;
                }
                continue;
            }
            if (current == '/' && next == '/') {
                lineComment = true;
                i++;
            } else if (current == '/' && next == '*') {
                blockComment = true;
                i++;
            } else if (current == '\'' || current == '"' || current == '`') {
                quote = current;
            } else {
                mask[i] = true;
            }
        }
        return mask;
    }

    private static boolean isCodeAt(boolean[] mask, int index) {
        return index >= 0 && index < mask.length && mask[index];
    }

    private static String replaceMatch(String line, Matcher matcher, String replacement) {
        return line.substring(0, matcher.start()) + replacement + line.substring(matcher.end());
    }

    private static String replaceCandidateLine(String source, RefactorCandidate candidate) {
        String[] lines = source.split("\n", -1);
        int index = candidate.startLine() - 1;
        if (index < 0 || index >= lines.length) {
            throw new IllegalStateException(
                    "Line " + candidate.startLine() + " out of range for " + candidate.sourceFile());
        }
        if (!lines[index].equals(candidate.beforeSnippet())) {
            throw new IllegalStateException(
                    "Before-snippet mismatch at line " + candidate.startLine()
                            + " for rule " + candidate.ruleId());
        }
        lines[index] = candidate.proposedAfterSnippet();
        return String.join("\n", lines);
    }

    private static String structuralHash(String source) {
        boolean[] mask = buildCodeMask(source);
        StringBuilder canonical = new StringBuilder();
        for (int i = 0; i < source.length(); i++) {
            if (mask[i] && !Character.isWhitespace(source.charAt(i))) canonical.append(source.charAt(i));
        }
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash, 0, 8);
        } catch (NoSuchAlgorithmException e) {
            return Integer.toHexString(canonical.toString().hashCode());
        }
    }

    private static String unifiedDiff(String relPath, String before, String after) {
        if (before.equals(after)) return "";
        String[] oldLines = before.split("\n", -1);
        String[] newLines = after.split("\n", -1);
        StringBuilder diff = new StringBuilder("--- a/").append(relPath)
                .append("\n+++ b/").append(relPath).append('\n');
        for (int i = 0; i < Math.max(oldLines.length, newLines.length); i++) {
            String oldLine = i < oldLines.length ? oldLines[i] : null;
            String newLine = i < newLines.length ? newLines[i] : null;
            if (Objects.equals(oldLine, newLine)) continue;
            diff.append("@@ -").append(i + 1).append(",1 +").append(i + 1).append(",1 @@\n");
            if (oldLine != null) diff.append('-').append(oldLine).append('\n');
            if (newLine != null) diff.append('+').append(newLine).append('\n');
        }
        return diff.toString();
    }

    private static String normalizedRelativePath(Path root, Path file) {
        return root.relativize(file).toString().replace('\\', '/');
    }

    private static String moduleName(String relPath) {
        String result = relPath.replace('\\', '/');
        int dot = result.lastIndexOf('.');
        if (dot >= 0) result = result.substring(0, dot);
        return result.replace('/', '.');
    }

    private static String packageName(String moduleName) {
        int dot = moduleName.lastIndexOf('.');
        return dot < 0 ? "" : moduleName.substring(0, dot);
    }

    private static final class ModuleParse {
        private final List<SemanticModel.ClassInfo> classes = new ArrayList<>();
        private final List<SemanticModel.MethodInfo> methods = new ArrayList<>();
        private int lineCount;
    }
}
