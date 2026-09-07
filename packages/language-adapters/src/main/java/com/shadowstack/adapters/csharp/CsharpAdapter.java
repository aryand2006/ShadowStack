package com.shadowstack.adapters.csharp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shadowstack.adapters.LanguageAdapter;
import com.shadowstack.adapters.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.Charset;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Structural adapter for C# 12 source.
 *
 * <p>Candidate detection prefers the Roslyn {@code CsharpAstEngine} when
 * {@code dotnet} and a published engine DLL are available, then merges in
 * regex-based findings for rule IDs the Roslyn path did not cover. Apply tries
 * the Roslyn engine first and falls back to line-oriented text replacement.</p>
 */
public class CsharpAdapter implements LanguageAdapter {

    private static final Logger LOG = LoggerFactory.getLogger(CsharpAdapter.class);
    private static final String LANGUAGE_ID = "csharp";
    private static final String LANGUAGE_VERSION = "12";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String ENGINE_DLL_NAME = "CsharpAstEngine.dll";

    private final Object roslynEngineLock = new Object();
    private volatile Path roslynEngineDll;
    private volatile Boolean roslynEngineAvailable;

    private static final Pattern NAMESPACE =
            Pattern.compile("\\bnamespace\\s+([A-Za-z_][\\w.]*)");
    private static final Pattern CLASS_DECL =
            Pattern.compile("\\b(?:class|record|struct|interface)\\s+([A-Za-z_][\\w]*)");
    private static final Pattern METHOD_DECL = Pattern.compile(
            "(?:^|\\s)(?:public|private|protected|internal|static|virtual|override|async|sealed|new|partial|extern|unsafe|\\s)+"
                    + "([A-Za-z_][\\w<>,.?\\[\\]]*)\\s+([A-Za-z_][\\w]*)\\s*\\(([^)]*)\\)");
    private static final Pattern HTTP_WEB_REQUEST =
            Pattern.compile("\\bHttpWebRequest\\b");
    private static final Pattern WEB_REQUEST_CREATE =
            Pattern.compile("\\bWebRequest\\.Create\\s*\\(");
    private static final Pattern NAMEVALUE_COLLECTION =
            Pattern.compile("\\bNameValueCollection\\b");

    private static final Pattern ARRAY_LIST = Pattern.compile("\\bArrayList\\b");
    private static final Pattern HASH_TABLE = Pattern.compile("\\bHashtable\\b");
    private static final Pattern APPEND_FORMAT = Pattern.compile(
            "([A-Za-z_][\\w.]*)\\.AppendFormat\\s*\\(\\s*\"([^\"]*\\{0\\}[^\"]*)\"\\s*,\\s*([^,()]+)\\)");
    private static final Pattern STRING_FORMAT = Pattern.compile(
            "\\bstring\\.Format\\s*\\(\\s*\"([^\"]*\\{0\\}[^\"]*)\"\\s*,\\s*([^,()]+)\\)");
    private static final Pattern READ_ONLY_COLLECTION =
            Pattern.compile("\\bReadOnlyCollection\\s*<([^>]+)>");
    private static final Pattern WEB_CLIENT = Pattern.compile("\\bWebClient\\b");
    private static final Pattern CONFIGURATION_MANAGER =
            Pattern.compile("\\bConfigurationManager\\.AppSettings\\b");
    private static final Pattern BINARY_FORMATTER = Pattern.compile("\\bBinaryFormatter\\b");
    private static final Pattern REMOTING = Pattern.compile("\\bSystem\\.Runtime\\.Remoting\\b");
    private static final Pattern THREAD_ABORT = Pattern.compile("\\bThread\\.Abort\\s*\\(");
    private static final Pattern PRINCIPAL_PERMISSION = Pattern.compile("\\bPrincipalPermission\\b");
    private static final Pattern BEGIN_END_INVOKE = Pattern.compile("\\b(?:BeginInvoke|EndInvoke)\\s*\\(");
    private static final Pattern ARGUMENT_NULL = Pattern.compile(
            "\\bthrow\\s+new\\s+ArgumentNullException\\s*\\(\\s*\"([A-Za-z_][\\w]*)\"\\s*\\)");
    private static final Pattern EMPTY_EQUALS =
            Pattern.compile("\\b([A-Za-z_][\\w.]*)\\s*==\\s*\"\"");
    private static final Pattern EMPTY_METHOD =
            Pattern.compile("\\b([A-Za-z_][\\w.]*)\\.Equals\\s*\\(\\s*\"\"\\s*\\)");
    private static final Pattern CONCURRENT_DICTIONARY = Pattern.compile(
            "if\\s*\\(\\s*!([A-Za-z_][\\w.]*)\\.ContainsKey\\s*\\(([^)]+)\\)\\s*\\)\\s*"
                    + "\\1\\s*\\[\\s*\\2\\s*]\\s*=\\s*([^;]+);");

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
                ModuleParse parsed = parseModule(source, relPath);
                totalLines += parsed.lineCount;
                packageFiles.computeIfAbsent(parsed.namespaceName, ignored -> new ArrayList<>())
                        .add(relPath);
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
                List<RefactorCandidate> roslyn = detectWithRoslynEngine(file, relPath);
                if (!roslyn.isEmpty()) {
                    Set<String> roslynIds = roslyn.stream()
                            .map(RefactorCandidate::ruleId)
                            .collect(Collectors.toSet());
                    candidates.addAll(roslyn);
                    for (RefactorCandidate regexCandidate : detectCandidates(source, relPath)) {
                        if (!roslynIds.contains(regexCandidate.ruleId())) {
                            candidates.add(regexCandidate);
                        }
                    }
                } else {
                    candidates.addAll(detectCandidates(source, relPath));
                }
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
            String beforeHash = structuralHash(before);
            boolean usedRoslyn = isRoslynEngineAvailable()
                    && tryApplyWithRoslynEngine(target, candidate.ruleId(), candidate.startLine());
            String after;
            if (usedRoslyn) {
                after = Files.readString(target, StandardCharsets.UTF_8);
            } else {
                after = replaceCandidateLine(before, candidate);
                Files.writeString(target, after, StandardCharsets.UTF_8);
            }
            String afterHash = structuralHash(after);
            return PatchResult.builder()
                    .candidateId(candidate.candidateId())
                    .unifiedDiff(unifiedDiff(candidate.sourceFile(), before, after))
                    .beforeAstHash(beforeHash)
                    .afterAstHash(afterHash)
                    .addAffectedFile(candidate.sourceFile())
                    .success(true)
                    .putMetadata("ruleId", candidate.ruleId())
                    .putMetadata("linesAffected", candidate.lineSpan())
                    .putMetadata("parseEngine", usedRoslyn ? "roslyn" : "regex")
                    .build();
        } catch (Exception e) {
            return PatchResult.failure(candidate.candidateId(), e.getMessage());
        }
    }

    @Override
    public VerificationResult verifyPatch(
            PatchResult patch, Path sourceRoot, VerificationConfig config) {
        Objects.requireNonNull(patch, "patch must not be null");
        Objects.requireNonNull(sourceRoot, "sourceRoot must not be null");
        Objects.requireNonNull(config, "config must not be null");

        List<VerificationResult.LayerResult> layers = new ArrayList<>();
        boolean compileOk = true;
        if (config.runCompilation()) {
            VerificationResult.LayerResult compile = verifyDotnetSyntax(patch, sourceRoot);
            layers.add(compile);
            compileOk = compile.passed();
        }
        VerificationResult.LayerResult structural = verifyStructure(patch, sourceRoot);
        layers.add(structural);

        boolean runtimeVerified = layers.stream()
                .anyMatch(l -> "compilation".equals(l.layerName())
                        && l.passed()
                        && l.details() != null
                        && l.details().contains("dotnet build succeeded"));
        // Hard gate: only PASS when a real `dotnet build` succeeded — never soft-pass
        // on missing SDK / missing .csproj / structural-only probes.
        boolean nativeGate = structural.passed() && runtimeVerified;
        return VerificationResult.builder()
                .patchId(patch.patchId())
                .compileSuccess(compileOk && structural.passed())
                .testSuccess(nativeGate)
                .astStructuralMatchScore(structural.score())
                .bytecodeDescriptorMatch(nativeGate)
                .apiSurfaceCompatible(structural.passed())
                .goldenMasterMatch(false)
                .layerResults(layers)
                .beforeAstHash(patch.beforeAstHash())
                .afterAstHash(patch.afterAstHash())
                .build();
    }

    private VerificationResult.LayerResult verifyStructure(PatchResult patch, Path sourceRoot) {
        long start = System.currentTimeMillis();
        boolean passed = true;
        String details = "No affected files";
        try {
            if (!patch.affectedFiles().isEmpty()) {
                Path target = sourceRoot.resolve(patch.affectedFiles().get(0));
                ModuleParse parsed = parseModule(
                        Files.readString(target, StandardCharsets.UTF_8),
                        target.getFileName().toString());
                details = "Re-parsed source: " + parsed.classes.size()
                        + " types, " + parsed.methods.size() + " methods";
            }
        } catch (Exception e) {
            passed = false;
            details = "Re-parse failed: " + e.getMessage();
        }
        return new VerificationResult.LayerResult(
                "structural", passed, passed ? 1.0 : 0.0, details,
                System.currentTimeMillis() - start);
    }

    private VerificationResult.LayerResult verifyDotnetSyntax(PatchResult patch, Path sourceRoot) {
        long start = System.currentTimeMillis();
        if (patch.affectedFiles().isEmpty()) {
            return new VerificationResult.LayerResult("compilation", true, 1.0, "No affected files", 0);
        }
        try {
            ProcessBuilder probe = new ProcessBuilder("dotnet", "--info");
            probe.redirectErrorStream(true);
            Process p = probe.start();
            boolean finished = p.waitFor(15, java.util.concurrent.TimeUnit.SECONDS);
            long elapsed = System.currentTimeMillis() - start;
            if (!finished) {
                p.destroyForcibly();
                return new VerificationResult.LayerResult(
                        "compilation", false, 0.0, "dotnet --info timed out", elapsed);
            }
            if (p.exitValue() != 0) {
                return new VerificationResult.LayerResult(
                        "compilation", false, 0.0,
                        "dotnet not available", elapsed);
            }
            Path target = sourceRoot.resolve(patch.affectedFiles().get(0));
            Path projectDir = findCsprojDirectory(sourceRoot, target);
            if (projectDir == null) {
                return new VerificationResult.LayerResult(
                        "compilation", false, 0.0,
                        "no .csproj under " + sourceRoot,
                        elapsed);
            }
            ProcessBuilder build = new ProcessBuilder("dotnet", "build", "--nologo", "-v", "q");
            build.directory(projectDir.toFile());
            build.redirectErrorStream(true);
            Process bp = build.start();
            StringBuilder out = new StringBuilder();
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(bp.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) out.append(line).append('\n');
            }
            boolean done = bp.waitFor(60, java.util.concurrent.TimeUnit.SECONDS);
            elapsed = System.currentTimeMillis() - start;
            if (!done) {
                bp.destroyForcibly();
                return new VerificationResult.LayerResult(
                        "compilation", false, 0.0, "dotnet build timed out", elapsed);
            }
            boolean ok = bp.exitValue() == 0;
            return new VerificationResult.LayerResult(
                    "compilation", ok, ok ? 1.0 : 0.0,
                    ok ? "dotnet build succeeded"
                            : "dotnet build failed:\n" + out,
                    elapsed);
        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - start;
            return new VerificationResult.LayerResult(
                    "compilation", false, 0.0,
                    "dotnet not available", elapsed);
        }
    }

    /**
     * Prefer a .csproj beside the affected file (walking up to {@code sourceRoot}),
     * otherwise any .csproj discovered under the source root.
     */
    private static Path findCsprojDirectory(Path sourceRoot, Path affectedFile) {
        Path dir = affectedFile.getParent();
        while (dir != null && (dir.startsWith(sourceRoot) || dir.equals(sourceRoot))) {
            if (firstCsprojIn(dir) != null) {
                return dir;
            }
            if (dir.equals(sourceRoot)) {
                break;
            }
            dir = dir.getParent();
        }
        try {
            final Path[] found = {null};
            Files.walkFileTree(sourceRoot, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (file.getFileName().toString().endsWith(".csproj")) {
                        found[0] = file.getParent();
                        return FileVisitResult.TERMINATE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult preVisitDirectory(Path d, BasicFileAttributes attrs) {
                    String name = d.getFileName() == null ? "" : d.getFileName().toString();
                    return Set.of(".git", "bin", "obj").contains(name)
                            ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
                }
            });
            return found[0];
        } catch (IOException e) {
            return null;
        }
    }

    private static Path firstCsprojIn(Path dir) {
        if (dir == null || !Files.isDirectory(dir)) return null;
        try (var stream = Files.list(dir)) {
            return stream
                    .filter(f -> f.getFileName().toString().endsWith(".csproj"))
                    .findFirst()
                    .orElse(null);
        } catch (IOException e) {
            return null;
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  ROSLYN AST ENGINE
    // ═══════════════════════════════════════════════════════════════════════

    private boolean isRoslynEngineAvailable() {
        Boolean cached = roslynEngineAvailable;
        if (cached != null) {
            return cached;
        }
        synchronized (roslynEngineLock) {
            if (roslynEngineAvailable != null) {
                return roslynEngineAvailable;
            }
            try {
                Path dll = resolveRoslynEngineDll();
                if (dll == null) {
                    roslynEngineAvailable = false;
                    return false;
                }
                ProcessBuilder pb = new ProcessBuilder("dotnet", "--info");
                pb.redirectErrorStream(true);
                Process p = pb.start();
                boolean finished = p.waitFor(10, TimeUnit.SECONDS);
                roslynEngineAvailable = finished && p.exitValue() == 0;
                if (!roslynEngineAvailable) {
                    LOG.info("Roslyn C# engine unavailable (dotnet probe failed)");
                }
                return roslynEngineAvailable;
            } catch (Exception e) {
                LOG.debug("Roslyn engine probe failed: {}", e.getMessage());
                roslynEngineAvailable = false;
                return false;
            }
        }
    }

    private Path resolveRoslynEngineDll() {
        Path cached = roslynEngineDll;
        if (cached != null && Files.isRegularFile(cached)) {
            return cached;
        }
        synchronized (roslynEngineLock) {
            if (roslynEngineDll != null && Files.isRegularFile(roslynEngineDll)) {
                return roslynEngineDll;
            }
            String rel = "packages/language-adapters/native-engines/csharp/publish/" + ENGINE_DLL_NAME;
            List<Path> candidates = new ArrayList<>();
            Path userDir = Path.of(System.getProperty("user.dir", ".")).toAbsolutePath().normalize();
            candidates.add(userDir.resolve(rel).normalize());
            candidates.add(userDir.resolve("native-engines/csharp/publish").resolve(ENGINE_DLL_NAME)
                    .normalize());
            // Walk up from user.dir looking for the monorepo engine publish dir.
            Path cursor = userDir;
            for (int i = 0; i < 8 && cursor != null; i++) {
                candidates.add(cursor.resolve(rel).normalize());
                if (Files.isRegularFile(cursor.resolve("pom.xml"))) {
                    candidates.add(cursor.resolve(rel).normalize());
                }
                Path parent = cursor.getParent();
                if (parent == null || parent.equals(cursor)) {
                    break;
                }
                cursor = parent;
            }
            // Last resort (container / cloud agent layout).
            candidates.add(Path.of("/workspace").resolve(rel));
            candidates.add(Path.of("native-engines/csharp/publish").resolve(ENGINE_DLL_NAME)
                    .toAbsolutePath().normalize());
            candidates.add(Path.of("../native-engines/csharp/publish").resolve(ENGINE_DLL_NAME)
                    .toAbsolutePath().normalize());

            for (Path candidate : candidates) {
                if (Files.isRegularFile(candidate)) {
                    roslynEngineDll = candidate;
                    return roslynEngineDll;
                }
            }
            LOG.warn("Roslyn engine DLL not found (expected under native-engines/csharp/publish)");
            return null;
        }
    }

    private List<String> roslynCommand(String... args) {
        Path dll = resolveRoslynEngineDll();
        List<String> cmd = new ArrayList<>();
        cmd.add("dotnet");
        cmd.add("exec");
        cmd.add(dll.toAbsolutePath().toString());
        cmd.addAll(Arrays.asList(args));
        return cmd;
    }

    private List<RefactorCandidate> detectWithRoslynEngine(Path file, String relPath) {
        if (!isRoslynEngineAvailable()) {
            return List.of();
        }
        try {
            ProcessBuilder pb = new ProcessBuilder(roslynCommand(
                    "detect", file.toAbsolutePath().toString()));
            pb.redirectErrorStream(true);
            Process p = pb.start();
            String output;
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                output = reader.lines().collect(Collectors.joining("\n")).trim();
            }
            boolean finished = p.waitFor(45, TimeUnit.SECONDS);
            if (!finished) {
                p.destroyForcibly();
                LOG.warn("Roslyn detect timed out for {}", relPath);
                return List.of();
            }
            if (p.exitValue() != 0 || output.isEmpty()) {
                return List.of();
            }
            String json = output;
            int lastNl = output.lastIndexOf('\n');
            if (lastNl >= 0) {
                String last = output.substring(lastNl + 1).trim();
                if (last.startsWith("[")) {
                    json = last;
                }
            }
            if (!json.startsWith("[")) {
                return List.of();
            }
            JsonNode arr = JSON.readTree(json);
            if (!arr.isArray() || arr.isEmpty()) {
                return List.of();
            }
            List<RefactorCandidate> out = new ArrayList<>();
            for (JsonNode node : arr) {
                String ruleId = textOr(node, "ruleId", "");
                if (ruleId.isEmpty()) continue;
                String ruleName = textOr(node, "ruleName", ruleId);
                String before = textOr(node, "beforeSnippet", "");
                String after = textOr(node, "afterSnippet", before);
                int startLine = node.path("startLine").asInt(1);
                int endLine = node.path("endLine").asInt(startLine);
                double confidence = Math.max(0.0, Math.min(1.0, node.path("confidence").asDouble(0.9)));
                RiskTier risk = parseRisk(textOr(node, "risk", "LOW"));
                out.add(RefactorCandidate.builder()
                        .sourceFile(relPath)
                        .startLine(startLine)
                        .endLine(endLine)
                        .ruleId(ruleId)
                        .ruleName(ruleName)
                        .ruleCategory("MODERNIZATION")
                        .beforeSnippet(before)
                        .proposedAfterSnippet(after)
                        .confidenceScore(confidence)
                        .riskTier(risk)
                        .addSafetyInvariant(new SafetyInvariant(
                                "cs-roslyn", "Detected via Roslyn CsharpAstEngine",
                                SafetyInvariant.Category.BEHAVIORAL_EQUIVALENCE,
                                SafetyInvariant.Status.SATISFIED,
                                "Roslyn structural match"))
                        .putAstContext("language", LANGUAGE_ID)
                        .putAstContext("ruleId", ruleId)
                        .putAstContext("parseEngine", "roslyn")
                        .build());
            }
            return out;
        } catch (Exception e) {
            LOG.debug("Roslyn detect failed for {}: {}", relPath, e.getMessage());
            return List.of();
        }
    }

    private boolean tryApplyWithRoslynEngine(Path file, String ruleId, int startLine) {
        try {
            if (resolveRoslynEngineDll() == null) {
                return false;
            }
            ProcessBuilder pb = new ProcessBuilder(roslynCommand(
                    "apply", file.toAbsolutePath().toString(), ruleId, Integer.toString(startLine)));
            pb.redirectErrorStream(true);
            Process p = pb.start();
            String output;
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                output = reader.lines().collect(Collectors.joining("\n")).trim();
            }
            boolean finished = p.waitFor(45, TimeUnit.SECONDS);
            if (!finished) {
                p.destroyForcibly();
                return false;
            }
            if (output.isEmpty()) {
                return false;
            }
            String json = output;
            int lastNl = output.lastIndexOf('\n');
            if (lastNl >= 0) {
                String last = output.substring(lastNl + 1).trim();
                if (last.startsWith("{")) {
                    json = last;
                }
            }
            JsonNode node = JSON.readTree(json);
            return node.path("ok").asBoolean(false);
        } catch (Exception e) {
            LOG.debug("Roslyn apply failed for {} ({}:{}): {}",
                    file, ruleId, startLine, e.getMessage());
            return false;
        }
    }

    private static String textOr(JsonNode node, String field, String fallback) {
        JsonNode v = node.get(field);
        if (v == null || v.isNull()) {
            return fallback;
        }
        String s = v.asText();
        return s != null ? s : fallback;
    }

    private static RiskTier parseRisk(String risk) {
        if (risk == null) {
            return RiskTier.LOW;
        }
        try {
            return RiskTier.valueOf(risk.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return RiskTier.LOW;
        }
    }

    private List<RefactorCandidate> detectCandidates(String source, String relPath) {
        boolean[] codeMask = buildCodeMask(source);
        String[] lines = source.split("\n", -1);
        List<RefactorCandidate> out = new ArrayList<>();

        if (!Pattern.compile("(?m)^\\s*#nullable\\s+enable\\b").matcher(source).find()) {
            String firstLine = lines.length == 0 ? "" : lines[0];
            out.add(candidate(relPath, 1, firstLine, "#nullable enable\n" + firstLine,
                    "cs.nullable_enable", "Enable nullable reference type analysis",
                    0.98, RiskTier.LOW, SafetyInvariant.Status.SATISFIED));
        }

        int offset = 0;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            detect(line, offset, i + 1, relPath, codeMask, ARRAY_LIST,
                    "cs.arraylist_to_list", "ArrayList → List<object>", "List<object>",
                    0.88, RiskTier.MODERATE, SafetyInvariant.Status.SATISFIED, out);
            detect(line, offset, i + 1, relPath, codeMask, HASH_TABLE,
                    "cs.hashtable_to_dictionary", "Hashtable → Dictionary<object, object>",
                    "Dictionary<object, object>", 0.86, RiskTier.MODERATE,
                    SafetyInvariant.Status.SATISFIED, out);
            detectAppendFormat(line, offset, i + 1, relPath, codeMask, out);
            detectStringFormat(line, offset, i + 1, relPath, codeMask, out);
            detectReadOnlyCollection(line, offset, i + 1, relPath, codeMask, out);
            detect(line, offset, i + 1, relPath, codeMask, WEB_CLIENT,
                    "cs.webclient_to_httpclient", "WebClient → HttpClient", "HttpClient",
                    0.72, RiskTier.HIGH, SafetyInvariant.Status.UNKNOWN, out);
            detect(line, offset, i + 1, relPath, codeMask, CONFIGURATION_MANAGER,
                    "cs.configurationmanager_to_iconfiguration",
                    "ConfigurationManager.AppSettings → IConfiguration",
                    "/* inject IConfiguration */ configuration", 0.62, RiskTier.HIGH,
                    SafetyInvariant.Status.UNKNOWN, out);
            detect(line, offset, i + 1, relPath, codeMask, BINARY_FORMATTER,
                    "cs.binaryformatter_removed", "BinaryFormatter removed from modern .NET",
                    "/* removed: choose a safe serializer */ BinaryFormatter",
                    0.99, RiskTier.CRITICAL, SafetyInvariant.Status.UNKNOWN, out);
            detect(line, offset, i + 1, relPath, codeMask, REMOTING,
                    "cs.remoting_removed", ".NET Remoting removed from modern .NET",
                    "/* removed: migrate to IPC or HTTP */ System.Runtime.Remoting",
                    0.99, RiskTier.CRITICAL, SafetyInvariant.Status.UNKNOWN, out);
            detect(line, offset, i + 1, relPath, codeMask, THREAD_ABORT,
                    "cs.threadabort_removed", "Thread.Abort → cooperative cancellation",
                    "/* use CancellationToken */ Thread.Abort(",
                    0.98, RiskTier.CRITICAL, SafetyInvariant.Status.UNKNOWN, out);
            detect(line, offset, i + 1, relPath, codeMask, PRINCIPAL_PERMISSION,
                    "cs.principalpermission_removed", "PrincipalPermission removed",
                    "/* replace with explicit authorization */ PrincipalPermission",
                    0.98, RiskTier.CRITICAL, SafetyInvariant.Status.UNKNOWN, out);
            detect(line, offset, i + 1, relPath, codeMask, BEGIN_END_INVOKE,
                    "cs.asynchronous_begin_end", "Begin/End async pattern → Task-based async",
                    "/* migrate to Task-based async */ $0", 0.82, RiskTier.HIGH,
                    SafetyInvariant.Status.UNKNOWN, out);
            detectArgumentNull(line, offset, i + 1, relPath, codeMask, out);
            detectStringEmpty(line, offset, i + 1, relPath, codeMask, EMPTY_EQUALS, out);
            detect(line, offset, i + 1, relPath, codeMask, HTTP_WEB_REQUEST,
                    "cs.httprequest_to_httpclient", "HttpWebRequest → HttpClient",
                    "HttpClient", 0.70, RiskTier.HIGH, SafetyInvariant.Status.UNKNOWN, out);
            detect(line, offset, i + 1, relPath, codeMask, WEB_REQUEST_CREATE,
                    "cs.webrequest_to_httpclient", "WebRequest.Create → HttpClient",
                    "/* use HttpClient */ HttpClient", 0.68, RiskTier.HIGH,
                    SafetyInvariant.Status.UNKNOWN, out);
            detect(line, offset, i + 1, relPath, codeMask, NAMEVALUE_COLLECTION,
                    "cs.namevaluecollection_to_dict", "NameValueCollection → Dictionary",
                    "Dictionary<string, string>", 0.75, RiskTier.MODERATE,
                    SafetyInvariant.Status.SATISFIED, out);

            detectStringEmpty(line, offset, i + 1, relPath, codeMask, EMPTY_METHOD, out);
            detectConcurrentDictionary(line, offset, i + 1, relPath, codeMask, out);
            offset += line.length() + 1;
        }
        return out;
    }

    private void detect(String line, int lineOffset, int lineNumber, String relPath,
                        boolean[] mask, Pattern pattern, String ruleId, String ruleName,
                        String replacement, double confidence, RiskTier risk,
                        SafetyInvariant.Status status, List<RefactorCandidate> out) {
        Matcher matcher = pattern.matcher(line);
        while (matcher.find()) {
            if (!isCodeAt(mask, lineOffset + matcher.start())) continue;
            String actualReplacement = replacement.replace("$0", matcher.group());
            String after = replaceMatch(line, matcher, actualReplacement);
            out.add(candidate(relPath, lineNumber, line, after, ruleId, ruleName,
                    confidence, risk, status));
        }
    }

    private void detectAppendFormat(String line, int offset, int lineNumber, String relPath,
                                    boolean[] mask, List<RefactorCandidate> out) {
        Matcher matcher = APPEND_FORMAT.matcher(line);
        while (matcher.find()) {
            if (!isCodeAt(mask, offset + matcher.start())) continue;
            if (matcher.group(2).matches(".*\\{[1-9][^}]*}.*")) continue;
            String interpolation = matcher.group(2).replace("{0}", "{" + matcher.group(3).trim() + "}");
            String after = replaceMatch(line, matcher,
                    matcher.group(1) + ".Append($\"" + interpolation + "\")");
            out.add(candidate(relPath, lineNumber, line, after,
                    "cs.stringbuilder_appendformat",
                    "StringBuilder.AppendFormat → interpolated Append",
                    0.90, RiskTier.LOW, SafetyInvariant.Status.SATISFIED));
        }
    }

    private void detectStringFormat(String line, int offset, int lineNumber, String relPath,
                                    boolean[] mask, List<RefactorCandidate> out) {
        Matcher matcher = STRING_FORMAT.matcher(line);
        while (matcher.find()) {
            if (!isCodeAt(mask, offset + matcher.start())) continue;
            if (matcher.group(1).matches(".*\\{[1-9][^}]*}.*")) continue;
            String interpolation = matcher.group(1).replace("{0}", "{" + matcher.group(2).trim() + "}");
            String after = replaceMatch(line, matcher, "$\"" + interpolation + "\"");
            out.add(candidate(relPath, lineNumber, line, after,
                    "cs.string_format_to_interpolation",
                    "string.Format → string interpolation",
                    0.92, RiskTier.LOW, SafetyInvariant.Status.SATISFIED));
        }
    }

    private void detectReadOnlyCollection(String line, int offset, int lineNumber, String relPath,
                                          boolean[] mask, List<RefactorCandidate> out) {
        Matcher matcher = READ_ONLY_COLLECTION.matcher(line);
        while (matcher.find()) {
            if (!isCodeAt(mask, offset + matcher.start())) continue;
            String after = replaceMatch(line, matcher, "IReadOnlyList<" + matcher.group(1) + ">");
            out.add(candidate(relPath, lineNumber, line, after,
                    "cs.readonlycollection_to_ilist",
                    "ReadOnlyCollection<T> → IReadOnlyList<T>",
                    0.68, RiskTier.MODERATE, SafetyInvariant.Status.UNKNOWN));
        }
    }

    private void detectArgumentNull(String line, int offset, int lineNumber, String relPath,
                                    boolean[] mask, List<RefactorCandidate> out) {
        Matcher matcher = ARGUMENT_NULL.matcher(line);
        while (matcher.find()) {
            if (!isCodeAt(mask, offset + matcher.start())) continue;
            String name = matcher.group(1);
            String after = replaceMatch(line, matcher,
                    "throw new ArgumentNullException(nameof(" + name + "))");
            out.add(candidate(relPath, lineNumber, line, after,
                    "cs.nameof_for_literals",
                    "ArgumentNullException string literal → nameof",
                    0.98, RiskTier.LOW, SafetyInvariant.Status.SATISFIED));
        }
    }

    private void detectStringEmpty(String line, int offset, int lineNumber, String relPath,
                                   boolean[] mask, Pattern pattern, List<RefactorCandidate> out) {
        Matcher matcher = pattern.matcher(line);
        while (matcher.find()) {
            if (!isCodeAt(mask, offset + matcher.start())) continue;
            String after = replaceMatch(line, matcher,
                    "string.IsNullOrEmpty(" + matcher.group(1) + ")");
            out.add(candidate(relPath, lineNumber, line, after,
                    "cs.string_isempty", "Empty-string comparison → string.IsNullOrEmpty",
                    0.74, RiskTier.MODERATE, SafetyInvariant.Status.UNKNOWN));
        }
    }

    private void detectConcurrentDictionary(
            String line, int offset, int lineNumber, String relPath,
            boolean[] mask, List<RefactorCandidate> out) {
        Matcher matcher = CONCURRENT_DICTIONARY.matcher(line);
        while (matcher.find()) {
            if (!isCodeAt(mask, offset + matcher.start())) continue;
            String after = replaceMatch(line, matcher,
                    matcher.group(1) + ".TryAdd(" + matcher.group(2).trim()
                            + ", " + matcher.group(3).trim() + ");");
            out.add(candidate(relPath, lineNumber, line, after,
                    "cs.concurrentdict_tryadd",
                    "ContainsKey + assignment → atomic TryAdd",
                    0.80, RiskTier.MODERATE, SafetyInvariant.Status.UNKNOWN));
        }
    }

    private RefactorCandidate candidate(
            String relPath, int lineNumber, String before, String after,
            String ruleId, String ruleName, double confidence, RiskTier risk,
            SafetyInvariant.Status status) {
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
                        "cs-code-mask", "Match is outside string literals and comments",
                        SafetyInvariant.Category.BEHAVIORAL_EQUIVALENCE,
                        status,
                        status == SafetyInvariant.Status.SATISFIED
                                ? "C# lexical mask confirmed executable code and rewrite is local"
                                : "Candidate requires semantic review before application"))
                .putAstContext("language", LANGUAGE_ID)
                .putAstContext("ruleId", ruleId)
                .build();
    }

    private ModuleParse parseModule(String source, String relPath) {
        ModuleParse parsed = new ModuleParse();
        parsed.lineCount = source.split("\n", -1).length;
        boolean[] mask = buildCodeMask(source);
        String[] lines = source.split("\n", -1);
        String currentClass = null;
        int offset = 0;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            Matcher namespace = NAMESPACE.matcher(line);
            if (namespace.find() && isCodeAt(mask, offset + namespace.start())) {
                parsed.namespaceName = namespace.group(1);
            }
            Matcher classes = CLASS_DECL.matcher(line);
            while (classes.find()) {
                if (!isCodeAt(mask, offset + classes.start())) continue;
                String name = classes.group(1);
                currentClass = parsed.namespaceName.isEmpty()
                        ? name : parsed.namespaceName + "." + name;
                parsed.classes.add(new SemanticModel.ClassInfo(
                        currentClass, name, parsed.namespaceName, null,
                        List.of(), List.of("public"), List.of(), relPath,
                        i + 1, i + 1, line.contains("interface "),
                        false, line.contains("record ")));
            }
            Matcher methods = METHOD_DECL.matcher(line);
            while (methods.find()) {
                if (!isCodeAt(mask, offset + methods.start())) continue;
                String returnType = methods.group(1);
                String name = methods.group(2);
                List<String> parameterNames = parseParameterNames(methods.group(3));
                List<String> parameterTypes = parseParameterTypes(methods.group(3));
                String owner = currentClass != null ? currentClass : parsed.namespaceName;
                parsed.methods.add(new SemanticModel.MethodInfo(
                        owner + "#" + name + "(" + String.join(",", parameterTypes) + ")",
                        name, owner, returnType, parameterTypes, parameterNames,
                        List.of("public"), List.of(), List.of(), i + 1, i + 1,
                        estimateComplexity(line), estimateComplexity(line), 1,
                        SemanticModel.Purity.UNKNOWN));
            }
            offset += line.length() + 1;
        }
        return parsed;
    }

    private static List<String> parseParameterNames(String parameters) {
        if (parameters == null || parameters.isBlank()) return List.of();
        List<String> result = new ArrayList<>();
        for (String item : parameters.split(",")) {
            String[] parts = item.trim().split("\\s+");
            if (parts.length > 0) result.add(parts[parts.length - 1].replaceAll("[=?].*", ""));
        }
        return result;
    }

    private static List<String> parseParameterTypes(String parameters) {
        if (parameters == null || parameters.isBlank()) return List.of();
        List<String> result = new ArrayList<>();
        for (String item : parameters.split(",")) {
            String[] parts = item.trim().split("\\s+");
            result.add(parts.length > 1 ? parts[parts.length - 2] : "object");
        }
        return result;
    }

    private static int estimateComplexity(String line) {
        int result = 1;
        Matcher matcher = Pattern.compile("\\b(?:if|for|foreach|while|case|catch)\\b|&&|\\|\\|")
                .matcher(line);
        while (matcher.find()) result++;
        return result;
    }

    private List<Path> collectFiles(Path sourceRoot) {
        List<Path> files = new ArrayList<>();
        try {
            Files.walkFileTree(sourceRoot, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (file.getFileName().toString().endsWith(".cs")) files.add(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    String name = dir.getFileName() == null ? "" : dir.getFileName().toString();
                    return Set.of(".git", "bin", "obj").contains(name)
                            ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            throw new AdapterException("parse", "Failed to walk C# source tree", e);
        }
        Collections.sort(files);
        return files;
    }

    private static boolean[] buildCodeMask(String source) {
        boolean[] mask = new boolean[source.length()];
        boolean lineComment = false;
        boolean blockComment = false;
        boolean verbatim = false;
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
                if (verbatim && current == '"' && next == '"') {
                    i++;
                } else if (!verbatim && escaped) {
                    escaped = false;
                } else if (!verbatim && current == '\\') {
                    escaped = true;
                } else if (current == quote) {
                    quote = 0;
                    verbatim = false;
                }
                continue;
            }
            if (current == '/' && next == '/') {
                lineComment = true;
                i++;
            } else if (current == '/' && next == '*') {
                blockComment = true;
                i++;
            } else if (current == '@' && next == '"') {
                verbatim = true;
                quote = '"';
                i++;
            } else if (current == '"' || current == '\'') {
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

    private static final class ModuleParse {
        private final List<SemanticModel.ClassInfo> classes = new ArrayList<>();
        private final List<SemanticModel.MethodInfo> methods = new ArrayList<>();
        private String namespaceName = "";
        private int lineCount;
    }
}
