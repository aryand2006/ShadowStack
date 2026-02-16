package com.shadowstack.analysis;

import com.shadowstack.analysis.BaselineSnapshot.*;
import org.eclipse.jdt.core.dom.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.tools.*;
import java.io.IOException;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Phase 0 — Baseline Capture.
 *
 * <p>Captures a complete, immutable snapshot of a Java project's observable
 * characteristics <em>before</em> any refactoring transformation is applied.
 * The resulting {@link BaselineSnapshot} serves as the ground-truth reference
 * for ShadowStack's verified refactoring pipeline.</p>
 *
 * <h3>Capture pipeline</h3>
 * <ol>
 *   <li><strong>Compile</strong> — invokes {@code javax.tools.JavaCompiler} on
 *       every {@code .java} file under the source root.</li>
 *   <li><strong>Test discovery &amp; execution</strong> — discovers JUnit 5 tests
 *       via the JUnit Platform Launcher and runs them, collecting pass/fail counts.</li>
 *   <li><strong>API surface extraction</strong> — walks the AST of every compilation
 *       unit and records all public classes, methods, and fields.</li>
 *   <li><strong>Behavioural detection</strong> — scans for reflection usage,
 *       concurrency primitives, and IO boundary operations.</li>
 *   <li><strong>Complexity metrics</strong> — computes cyclomatic complexity per method.</li>
 *   <li><strong>Semantic fingerprints</strong> — generates SHA-256 fingerprints for
 *       every public method, used later for before/after comparison.</li>
 * </ol>
 *
 * <p>This class is thread-safe. Each call to {@link #captureBaseline(Path)}
 * creates its own isolated analysis state.</p>
 *
 * @see BaselineSnapshot
 * @see ComplexityMetrics
 * @see SemanticFingerprint
 */
public final class BaselineCapture {

    private static final Logger LOG = LoggerFactory.getLogger(BaselineCapture.class);

    /** Reflection API method names to detect. */
    private static final Set<String> REFLECTION_METHODS = Set.of(
            "forName", "getMethod", "getDeclaredMethod", "getField", "getDeclaredField",
            "getConstructor", "getDeclaredConstructor", "newInstance", "invoke",
            "setAccessible", "getAnnotation", "getDeclaredMethods", "getDeclaredFields",
            "getMethods", "getFields", "getConstructors"
    );

    /** Types whose method calls indicate reflection usage. */
    private static final Set<String> REFLECTION_TYPES = Set.of(
            "Class", "Method", "Field", "Constructor", "AccessibleObject",
            "java.lang.Class", "java.lang.reflect.Method", "java.lang.reflect.Field",
            "java.lang.reflect.Constructor", "java.lang.reflect.AccessibleObject"
    );

    /** Types whose usage indicates IO boundary operations. */
    private static final Map<String, IOType> IO_TYPE_MAP = Map.ofEntries(
            Map.entry("File", IOType.FILE),
            Map.entry("Files", IOType.FILE),
            Map.entry("Path", IOType.FILE),
            Map.entry("FileInputStream", IOType.FILE),
            Map.entry("FileOutputStream", IOType.FILE),
            Map.entry("FileReader", IOType.FILE),
            Map.entry("FileWriter", IOType.FILE),
            Map.entry("RandomAccessFile", IOType.FILE),
            Map.entry("BufferedReader", IOType.STREAM),
            Map.entry("BufferedWriter", IOType.STREAM),
            Map.entry("InputStream", IOType.STREAM),
            Map.entry("OutputStream", IOType.STREAM),
            Map.entry("Reader", IOType.STREAM),
            Map.entry("Writer", IOType.STREAM),
            Map.entry("Socket", IOType.SOCKET),
            Map.entry("ServerSocket", IOType.SOCKET),
            Map.entry("DatagramSocket", IOType.SOCKET),
            Map.entry("SocketChannel", IOType.SOCKET),
            Map.entry("URL", IOType.NETWORK),
            Map.entry("HttpURLConnection", IOType.NETWORK),
            Map.entry("HttpClient", IOType.NETWORK),
            Map.entry("Connection", IOType.DATABASE),
            Map.entry("Statement", IOType.DATABASE),
            Map.entry("PreparedStatement", IOType.DATABASE),
            Map.entry("ResultSet", IOType.DATABASE),
            Map.entry("DataSource", IOType.DATABASE),
            Map.entry("System.in", IOType.CONSOLE),
            Map.entry("System.out", IOType.CONSOLE),
            Map.entry("System.err", IOType.CONSOLE),
            Map.entry("Scanner", IOType.CONSOLE)
    );

    /** Concurrency-related type names. */
    private static final Map<String, ConcurrencyType> CONCURRENCY_TYPE_MAP = Map.ofEntries(
            Map.entry("ReentrantLock", ConcurrencyType.REENTRANT_LOCK),
            Map.entry("ReentrantReadWriteLock", ConcurrencyType.READ_WRITE_LOCK),
            Map.entry("ReadWriteLock", ConcurrencyType.READ_WRITE_LOCK),
            Map.entry("Lock", ConcurrencyType.REENTRANT_LOCK),
            Map.entry("ExecutorService", ConcurrencyType.EXECUTOR_SERVICE),
            Map.entry("Executors", ConcurrencyType.EXECUTOR_SERVICE),
            Map.entry("ThreadPoolExecutor", ConcurrencyType.EXECUTOR_SERVICE),
            Map.entry("ScheduledExecutorService", ConcurrencyType.EXECUTOR_SERVICE),
            Map.entry("ForkJoinPool", ConcurrencyType.FORK_JOIN),
            Map.entry("ForkJoinTask", ConcurrencyType.FORK_JOIN),
            Map.entry("RecursiveTask", ConcurrencyType.FORK_JOIN),
            Map.entry("RecursiveAction", ConcurrencyType.FORK_JOIN),
            Map.entry("AtomicInteger", ConcurrencyType.ATOMIC_VARIABLE),
            Map.entry("AtomicLong", ConcurrencyType.ATOMIC_VARIABLE),
            Map.entry("AtomicBoolean", ConcurrencyType.ATOMIC_VARIABLE),
            Map.entry("AtomicReference", ConcurrencyType.ATOMIC_VARIABLE),
            Map.entry("ConcurrentHashMap", ConcurrencyType.CONCURRENT_COLLECTION),
            Map.entry("ConcurrentLinkedQueue", ConcurrencyType.CONCURRENT_COLLECTION),
            Map.entry("CopyOnWriteArrayList", ConcurrencyType.CONCURRENT_COLLECTION),
            Map.entry("BlockingQueue", ConcurrencyType.CONCURRENT_COLLECTION),
            Map.entry("CompletableFuture", ConcurrencyType.COMPLETABLE_FUTURE),
            Map.entry("Thread", ConcurrencyType.THREAD_CREATION)
    );

    /**
     * Captures a complete baseline snapshot of the project at the given source root.
     *
     * @param sourceRoot path to the project's source root directory
     * @return an immutable {@link BaselineSnapshot}
     * @throws IOException          if source files cannot be read
     * @throws NullPointerException if {@code sourceRoot} is null
     */
    public BaselineSnapshot captureBaseline(Path sourceRoot) throws IOException {
        Objects.requireNonNull(sourceRoot, "sourceRoot must not be null");
        Path resolvedRoot = sourceRoot.toAbsolutePath().normalize();
        LOG.info("Starting baseline capture for source root: {}", resolvedRoot);

        Instant captureTime = Instant.now();

        // 1) Discover all .java files
        List<Path> javaFiles = discoverJavaFiles(resolvedRoot);
        LOG.info("Discovered {} Java source files", javaFiles.size());

        // 2) Compile the project
        boolean compileSuccess = compileProject(resolvedRoot, javaFiles);
        LOG.info("Compilation {}", compileSuccess ? "SUCCEEDED" : "FAILED");

        // 3) Run tests (best-effort — failure here does not abort capture)
        TestResults testResults = runTests(resolvedRoot);
        LOG.info("Test results: {} total, {} passed, {} failed, {} skipped",
                testResults.totalTests(), testResults.passed(),
                testResults.failed(), testResults.skipped());

        // 4) Parse all files and run detectors
        List<MethodSignature> apiSignatures = new ArrayList<>();
        List<ReflectionUsage> reflectionUsages = new ArrayList<>();
        List<ConcurrencyPattern> concurrencyPatterns = new ArrayList<>();
        List<IOBoundary> ioBoundaries = new ArrayList<>();
        Map<String, Integer> complexityMetrics = new LinkedHashMap<>();
        Map<String, String> semanticFingerprints = new LinkedHashMap<>();

        for (Path javaFile : javaFiles) {
            try {
                String source = Files.readString(javaFile, StandardCharsets.UTF_8);
                CompilationUnit cu = parseCompilationUnit(source, javaFile);

                // API surface extraction
                apiSignatures.addAll(extractPublicApi(cu));

                // Behavioural detection
                String relativePath = resolvedRoot.relativize(javaFile).toString();
                reflectionUsages.addAll(detectReflection(cu, relativePath));
                concurrencyPatterns.addAll(detectConcurrency(cu, relativePath));
                ioBoundaries.addAll(detectIOBoundaries(cu, relativePath));

                // Complexity metrics
                ComplexityMetrics.ComplexityReport report =
                        ComplexityMetrics.analyze(cu, javaFile);
                complexityMetrics.putAll(report.cyclomaticByMethod());

                // Semantic fingerprints for public methods
                semanticFingerprints.putAll(computeFingerprints(cu));

            } catch (Exception e) {
                LOG.warn("Failed to analyze file {}: {}", javaFile, e.getMessage());
            }
        }

        LOG.info("Baseline capture complete — {} API signatures, {} reflection usages, " +
                        "{} concurrency patterns, {} IO boundaries, {} complexity entries, {} fingerprints",
                apiSignatures.size(), reflectionUsages.size(), concurrencyPatterns.size(),
                ioBoundaries.size(), complexityMetrics.size(), semanticFingerprints.size());

        return BaselineSnapshot.builder()
                .compileSuccess(compileSuccess)
                .testResults(testResults)
                .apiSignatures(apiSignatures)
                .reflectionUsages(reflectionUsages)
                .concurrencyPatterns(concurrencyPatterns)
                .ioBoundaries(ioBoundaries)
                .complexityMetrics(complexityMetrics)
                .semanticFingerprints(semanticFingerprints)
                .timestamp(captureTime)
                .sourceRoot(resolvedRoot)
                .build();
    }

    // ─── Compilation ─────────────────────────────────────────────────

    /**
     * Compiles all Java source files using the system Java compiler.
     *
     * @return {@code true} if compilation succeeded with no errors
     */
    private boolean compileProject(Path sourceRoot, List<Path> javaFiles) {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            LOG.error("No system Java compiler available — ensure JDK (not JRE) is on the path");
            return false;
        }

        try (StandardJavaFileManager fileManager =
                     compiler.getStandardFileManager(null, null, StandardCharsets.UTF_8)) {

            Iterable<? extends JavaFileObject> compilationUnits =
                    fileManager.getJavaFileObjectsFromPaths(javaFiles);

            // Set up output directory
            Path outputDir = sourceRoot.resolve("target").resolve("baseline-classes");
            Files.createDirectories(outputDir);

            List<String> options = List.of(
                    "-d", outputDir.toString(),
                    "-source", "21",
                    "-target", "21",
                    "-encoding", "UTF-8",
                    "-proc:none" // Skip annotation processing
            );

            StringWriter diagnosticOutput = new StringWriter();
            DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();

            JavaCompiler.CompilationTask task = compiler.getTask(
                    diagnosticOutput, fileManager, diagnostics, options, null, compilationUnits);

            boolean success = task.call();

            for (Diagnostic<? extends JavaFileObject> diag : diagnostics.getDiagnostics()) {
                if (diag.getKind() == Diagnostic.Kind.ERROR) {
                    LOG.warn("Compile error: {} at line {}", diag.getMessage(null), diag.getLineNumber());
                }
            }

            return success;

        } catch (IOException e) {
            LOG.error("Compilation failed with IOException", e);
            return false;
        }
    }

    // ─── Test execution ──────────────────────────────────────────────

    /**
     * Discovers and runs JUnit 5 tests under the source root.
     *
     * <p>Uses the JUnit Platform Launcher API. If the launcher is not available
     * on the classpath or test execution fails, returns a zero-count result.</p>
     */
    private TestResults runTests(Path sourceRoot) {
        try {
            // Attempt to use JUnit Platform Launcher for test discovery and execution
            var discovery = org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder
                    .request()
                    .selectors(org.junit.platform.engine.discovery.DiscoverySelectors
                            .selectClasspathRoots(Set.of(sourceRoot)))
                    .build();

            var launcher = org.junit.platform.launcher.core.LauncherFactory.create();

            TestResultCollector collector = new TestResultCollector();
            launcher.execute(discovery, collector);

            return new TestResults(
                    collector.total,
                    collector.passed,
                    collector.failed,
                    collector.skipped,
                    List.copyOf(collector.failureMessages));

        } catch (Exception e) {
            LOG.warn("Test execution unavailable or failed: {}. " +
                    "Returning empty test results.", e.getMessage());
            return new TestResults(0, 0, 0, 0, List.of());
        }
    }

    /**
     * JUnit Platform test execution listener that collects results.
     */
    private static final class TestResultCollector implements org.junit.platform.launcher.TestExecutionListener {
        int total = 0;
        int passed = 0;
        int failed = 0;
        int skipped = 0;
        final List<String> failureMessages = new ArrayList<>();

        @Override
        public void executionFinished(
                org.junit.platform.launcher.TestIdentifier testIdentifier,
                org.junit.platform.engine.TestExecutionResult testExecutionResult) {

            if (!testIdentifier.isTest()) return;

            total++;
            switch (testExecutionResult.getStatus()) {
                case SUCCESSFUL -> passed++;
                case FAILED -> {
                    failed++;
                    testExecutionResult.getThrowable()
                            .ifPresent(t -> failureMessages.add(
                                    testIdentifier.getDisplayName() + ": " + t.getMessage()));
                }
                case ABORTED -> skipped++;
            }
        }

        @Override
        public void executionSkipped(
                org.junit.platform.launcher.TestIdentifier testIdentifier, String reason) {
            if (testIdentifier.isTest()) {
                total++;
                skipped++;
            }
        }
    }

    // ─── AST Parsing ─────────────────────────────────────────────────

    /**
     * Parses a Java source string into an Eclipse JDT CompilationUnit.
     */
    private CompilationUnit parseCompilationUnit(String source, Path filePath) {
        ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
        parser.setKind(ASTParser.K_COMPILATION_UNIT);
        parser.setSource(source.toCharArray());
        parser.setResolveBindings(false);

        Map<String, String> options = new HashMap<>();
        options.put("org.eclipse.jdt.core.compiler.source", "21");
        options.put("org.eclipse.jdt.core.compiler.compliance", "21");
        options.put("org.eclipse.jdt.core.compiler.codegen.targetPlatform", "21");
        parser.setCompilerOptions(options);

        parser.setUnitName(filePath.getFileName().toString());

        return (CompilationUnit) parser.createAST(null);
    }

    // ─── API surface extraction ──────────────────────────────────────

    /**
     * Extracts all public API signatures from a compilation unit.
     */
    private List<MethodSignature> extractPublicApi(CompilationUnit cu) {
        List<MethodSignature> signatures = new ArrayList<>();
        cu.accept(new ASTVisitor() {
            private String currentClass = "";

            @Override
            public boolean visit(TypeDeclaration node) {
                if (Modifier.isPublic(node.getModifiers())) {
                    String previousClass = currentClass;
                    currentClass = resolveTypeName(node);

                    // Extract public methods
                    for (MethodDeclaration method : node.getMethods()) {
                        if (Modifier.isPublic(method.getModifiers()) ||
                                Modifier.isProtected(method.getModifiers())) {
                            signatures.add(extractMethodSignature(method, currentClass));
                        }
                    }

                    // Visit nested types
                    for (TypeDeclaration nested : node.getTypes()) {
                        nested.accept(this);
                    }

                    currentClass = previousClass;
                }
                return false;
            }

            private String resolveTypeName(TypeDeclaration node) {
                PackageDeclaration pkg = cu.getPackage();
                String packageName = pkg != null ? pkg.getName().getFullyQualifiedName() : "";
                String typeName = node.getName().getIdentifier();

                // Handle nested types
                ASTNode parent = node.getParent();
                while (parent instanceof TypeDeclaration td) {
                    typeName = td.getName().getIdentifier() + "." + typeName;
                    parent = td.getParent();
                }

                return packageName.isEmpty() ? typeName : packageName + "." + typeName;
            }
        });
        return signatures;
    }

    /**
     * Extracts a MethodSignature from a MethodDeclaration.
     */
    private MethodSignature extractMethodSignature(MethodDeclaration method, String className) {
        String methodName = method.isConstructor() ? "<init>" : method.getName().getIdentifier();
        String returnType = method.isConstructor() ? "void" :
                (method.getReturnType2() != null ? method.getReturnType2().toString() : "void");

        @SuppressWarnings("unchecked")
        List<SingleVariableDeclaration> params = method.parameters();
        List<String> paramTypes = params.stream()
                .map(p -> p.getType().toString())
                .collect(Collectors.toList());

        return new MethodSignature(
                className,
                methodName,
                returnType,
                paramTypes,
                method.getModifiers(),
                method.isConstructor());
    }

    // ─── Reflection detection ────────────────────────────────────────

    /**
     * Detects reflection API usage in the compilation unit.
     */
    private List<ReflectionUsage> detectReflection(CompilationUnit cu, String filePath) {
        List<ReflectionUsage> usages = new ArrayList<>();

        cu.accept(new ASTVisitor() {
            @Override
            public boolean visit(MethodInvocation node) {
                String methodName = node.getName().getIdentifier();

                if (REFLECTION_METHODS.contains(methodName)) {
                    Expression expr = node.getExpression();
                    String receiverType = expr != null ? expr.toString() : "";

                    // Check if receiver looks like a reflection type
                    boolean isReflective = REFLECTION_TYPES.stream()
                            .anyMatch(receiverType::contains) ||
                            "Class".equals(methodName) ||
                            methodName.equals("forName");

                    if (isReflective || methodName.equals("forName")) {
                        int line = cu.getLineNumber(node.getStartPosition());
                        String targetType = extractReflectionTarget(node);
                        usages.add(new ReflectionUsage(
                                filePath + ":" + line,
                                receiverType + "." + methodName,
                                targetType,
                                line));
                    }
                }
                return true;
            }
        });

        return usages;
    }

    /**
     * Attempts to extract the target type from a reflection call's arguments.
     */
    private String extractReflectionTarget(MethodInvocation node) {
        @SuppressWarnings("unchecked")
        List<Expression> args = node.arguments();
        if (!args.isEmpty()) {
            Expression first = args.get(0);
            if (first instanceof StringLiteral sl) {
                return sl.getLiteralValue();
            }
            return first.toString();
        }
        return "<unknown>";
    }

    // ─── Concurrency detection ───────────────────────────────────────

    /**
     * Detects concurrency patterns in the compilation unit.
     */
    private List<ConcurrencyPattern> detectConcurrency(CompilationUnit cu, String filePath) {
        List<ConcurrencyPattern> patterns = new ArrayList<>();

        cu.accept(new ASTVisitor() {

            @Override
            public boolean visit(SynchronizedStatement node) {
                int line = cu.getLineNumber(node.getStartPosition());
                patterns.add(new ConcurrencyPattern(
                        filePath + ":" + line,
                        ConcurrencyType.SYNCHRONIZED_BLOCK,
                        "synchronized block on: " + node.getExpression(),
                        line));
                return true;
            }

            @Override
            public boolean visit(MethodDeclaration node) {
                if (Modifier.isSynchronized(node.getModifiers())) {
                    int line = cu.getLineNumber(node.getStartPosition());
                    patterns.add(new ConcurrencyPattern(
                            filePath + ":" + line,
                            ConcurrencyType.SYNCHRONIZED_METHOD,
                            "synchronized method: " + node.getName().getIdentifier(),
                            line));
                }
                return true;
            }

            @Override
            public boolean visit(FieldDeclaration node) {
                if (Modifier.isVolatile(node.getModifiers())) {
                    int line = cu.getLineNumber(node.getStartPosition());
                    @SuppressWarnings("unchecked")
                    List<VariableDeclarationFragment> fragments = node.fragments();
                    String fieldNames = fragments.stream()
                            .map(f -> f.getName().getIdentifier())
                            .collect(Collectors.joining(", "));
                    patterns.add(new ConcurrencyPattern(
                            filePath + ":" + line,
                            ConcurrencyType.VOLATILE_FIELD,
                            "volatile field: " + fieldNames,
                            line));
                }

                // Check type for concurrency types
                String typeName = node.getType().toString();
                ConcurrencyType concType = CONCURRENCY_TYPE_MAP.get(typeName);
                if (concType != null) {
                    int line = cu.getLineNumber(node.getStartPosition());
                    patterns.add(new ConcurrencyPattern(
                            filePath + ":" + line,
                            concType,
                            "concurrency type: " + typeName,
                            line));
                }
                return true;
            }

            @Override
            public boolean visit(VariableDeclarationStatement node) {
                String typeName = node.getType().toString();
                ConcurrencyType concType = CONCURRENCY_TYPE_MAP.get(typeName);
                if (concType != null) {
                    int line = cu.getLineNumber(node.getStartPosition());
                    patterns.add(new ConcurrencyPattern(
                            filePath + ":" + line,
                            concType,
                            "local concurrency variable: " + typeName,
                            line));
                }
                return true;
            }

            @Override
            public boolean visit(MethodInvocation node) {
                String methodName = node.getName().getIdentifier();
                Expression expr = node.getExpression();
                if (expr != null) {
                    String receiver = expr.toString();
                    // Detect Executors.newFixedThreadPool(...) etc.
                    if (receiver.equals("Executors") || receiver.contains("ExecutorService")) {
                        int line = cu.getLineNumber(node.getStartPosition());
                        patterns.add(new ConcurrencyPattern(
                                filePath + ":" + line,
                                ConcurrencyType.EXECUTOR_SERVICE,
                                "executor call: " + receiver + "." + methodName,
                                line));
                    }
                    // Detect Thread.start()
                    if (methodName.equals("start") && receiver.contains("Thread")) {
                        int line = cu.getLineNumber(node.getStartPosition());
                        patterns.add(new ConcurrencyPattern(
                                filePath + ":" + line,
                                ConcurrencyType.THREAD_CREATION,
                                "thread start: " + receiver + ".start()",
                                line));
                    }
                }
                return true;
            }

            @Override
            public boolean visit(ClassInstanceCreation node) {
                String typeName = node.getType().toString();
                // Detect new Thread(...)
                if ("Thread".equals(typeName)) {
                    int line = cu.getLineNumber(node.getStartPosition());
                    patterns.add(new ConcurrencyPattern(
                            filePath + ":" + line,
                            ConcurrencyType.THREAD_CREATION,
                            "thread creation: new Thread(...)",
                            line));
                }
                ConcurrencyType concType = CONCURRENCY_TYPE_MAP.get(typeName);
                if (concType != null) {
                    int line = cu.getLineNumber(node.getStartPosition());
                    patterns.add(new ConcurrencyPattern(
                            filePath + ":" + line,
                            concType,
                            "concurrency instance creation: new " + typeName + "(...)",
                            line));
                }
                return true;
            }
        });

        return patterns;
    }

    // ─── IO boundary detection ───────────────────────────────────────

    /**
     * Detects IO boundary operations in the compilation unit.
     */
    private List<IOBoundary> detectIOBoundaries(CompilationUnit cu, String filePath) {
        List<IOBoundary> boundaries = new ArrayList<>();

        cu.accept(new ASTVisitor() {

            @Override
            public boolean visit(ClassInstanceCreation node) {
                String typeName = node.getType().toString();
                IOType ioType = IO_TYPE_MAP.get(typeName);
                if (ioType != null) {
                    int line = cu.getLineNumber(node.getStartPosition());
                    boundaries.add(new IOBoundary(
                            filePath + ":" + line,
                            ioType,
                            "new " + typeName + "(...)",
                            line));
                }
                return true;
            }

            @Override
            public boolean visit(MethodInvocation node) {
                String methodName = node.getName().getIdentifier();
                Expression expr = node.getExpression();
                if (expr != null) {
                    String receiver = expr.toString();
                    // Check for Files.* static calls
                    if ("Files".equals(receiver)) {
                        int line = cu.getLineNumber(node.getStartPosition());
                        boundaries.add(new IOBoundary(
                                filePath + ":" + line,
                                IOType.FILE,
                                "Files." + methodName + "(...)",
                                line));
                    }
                    // Check for System.out/err/in
                    if (receiver.startsWith("System.out") || receiver.startsWith("System.err")) {
                        int line = cu.getLineNumber(node.getStartPosition());
                        boundaries.add(new IOBoundary(
                                filePath + ":" + line,
                                IOType.CONSOLE,
                                receiver + "." + methodName + "(...)",
                                line));
                    }
                }
                return true;
            }

            @Override
            public boolean visit(FieldDeclaration node) {
                String typeName = node.getType().toString();
                IOType ioType = IO_TYPE_MAP.get(typeName);
                if (ioType != null) {
                    int line = cu.getLineNumber(node.getStartPosition());
                    boundaries.add(new IOBoundary(
                            filePath + ":" + line,
                            ioType,
                            "field of type " + typeName,
                            line));
                }
                return true;
            }

            @Override
            public boolean visit(VariableDeclarationStatement node) {
                String typeName = node.getType().toString();
                IOType ioType = IO_TYPE_MAP.get(typeName);
                if (ioType != null) {
                    int line = cu.getLineNumber(node.getStartPosition());
                    boundaries.add(new IOBoundary(
                            filePath + ":" + line,
                            ioType,
                            "local variable of type " + typeName,
                            line));
                }
                return true;
            }
        });

        return boundaries;
    }

    // ─── Semantic fingerprints ───────────────────────────────────────

    /**
     * Computes semantic fingerprints for all public methods in the compilation unit.
     */
    private Map<String, String> computeFingerprints(CompilationUnit cu) {
        Map<String, String> fingerprints = new LinkedHashMap<>();

        cu.accept(new ASTVisitor() {
            @Override
            public boolean visit(TypeDeclaration node) {
                if (!Modifier.isPublic(node.getModifiers())) return false;

                String className = resolveClassName(cu, node);

                for (MethodDeclaration method : node.getMethods()) {
                    if (!Modifier.isPublic(method.getModifiers())) continue;

                    String qualifiedName = className + "#" + method.getName().getIdentifier();
                    String returnType = method.getReturnType2() != null
                            ? method.getReturnType2().toString() : "void";

                    @SuppressWarnings("unchecked")
                    List<SingleVariableDeclaration> params = method.parameters();
                    List<String> paramTypes = params.stream()
                            .map(p -> p.getType().toString())
                            .collect(Collectors.toList());

                    // Collect called methods and field accesses
                    List<String> calledMethods = new ArrayList<>();
                    List<String> fieldAccesses = new ArrayList<>();

                    if (method.getBody() != null) {
                        method.getBody().accept(new ASTVisitor() {
                            @Override
                            public boolean visit(MethodInvocation inv) {
                                calledMethods.add(inv.getName().getIdentifier());
                                return true;
                            }

                            @Override
                            public boolean visit(FieldAccess fa) {
                                fieldAccesses.add(fa.getName().getIdentifier());
                                return true;
                            }

                            @Override
                            public boolean visit(QualifiedName qn) {
                                fieldAccesses.add(qn.getName().getIdentifier());
                                return true;
                            }
                        });
                    }

                    String fingerprint = SemanticFingerprint.compute(
                            qualifiedName, returnType, paramTypes, calledMethods, fieldAccesses);
                    fingerprints.put(qualifiedName, fingerprint);
                }
                return true;
            }
        });

        return fingerprints;
    }

    // ─── Utility ─────────────────────────────────────────────────────

    /**
     * Discovers all {@code .java} files under the given root.
     */
    private List<Path> discoverJavaFiles(Path root) throws IOException {
        List<Path> files = new ArrayList<>();
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (file.toString().endsWith(".java")) {
                    files.add(file);
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                String dirName = dir.getFileName().toString();
                // Skip build output, hidden, and VCS directories
                if (dirName.equals("target") || dirName.equals("build") ||
                        dirName.equals(".git") || dirName.equals(".svn") ||
                        dirName.equals("node_modules")) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }
        });
        return files;
    }

    /**
     * Resolves the fully-qualified class name from a TypeDeclaration.
     */
    private static String resolveClassName(CompilationUnit cu, TypeDeclaration node) {
        PackageDeclaration pkg = cu.getPackage();
        String packageName = pkg != null ? pkg.getName().getFullyQualifiedName() : "";
        String typeName = node.getName().getIdentifier();

        ASTNode parent = node.getParent();
        while (parent instanceof TypeDeclaration td) {
            typeName = td.getName().getIdentifier() + "." + typeName;
            parent = td.getParent();
        }

        return packageName.isEmpty() ? typeName : packageName + "." + typeName;
    }
}
