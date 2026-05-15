package com.shadowstack.adapters.java;

import com.shadowstack.adapters.LanguageAdapter;
import com.shadowstack.adapters.model.*;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.compiler.IProblem;
import org.eclipse.jdt.core.dom.*;
import org.eclipse.jdt.core.dom.rewrite.ASTRewrite;
import org.eclipse.jface.text.Document;
import org.eclipse.text.edits.TextEdit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Full {@link LanguageAdapter} implementation for Java source code.
 *
 * <p>Uses Eclipse JDT Core's {@link ASTParser} for parsing, type resolution,
 * and AST rewriting. This adapter supports Java 21 language features and
 * performs full binding resolution when classpath information is available.</p>
 *
 * <h3>Thread Safety</h3>
 * <p>Instances are safe for concurrent use across different source roots.
 * Internal caches are backed by {@link ConcurrentHashMap}.</p>
 *
 * <h3>Capabilities</h3>
 * <ul>
 *   <li>Full AST parsing with type binding resolution</li>
 *   <li>Class hierarchy extraction (superclass, interfaces)</li>
 *   <li>Method signature extraction with parameter types and names</li>
 *   <li>Call graph construction from method invocations</li>
 *   <li>Field and annotation metadata extraction</li>
 *   <li>Cyclomatic complexity computation</li>
 *   <li>Method purity classification (heuristic)</li>
 *   <li>AST rewriting with unified diff generation</li>
 *   <li>Multi-layer patch verification</li>
 * </ul>
 */
public class JavaAdapter implements LanguageAdapter {

    private static final Logger LOG = LoggerFactory.getLogger(JavaAdapter.class);
    private static final String LANGUAGE_ID = "java";
    private static final String LANGUAGE_VERSION = "21";

    private final Map<Path, SemanticModel> modelCache = new ConcurrentHashMap<>();

    @Override
    public String languageId() {
        return LANGUAGE_ID;
    }

    @Override
    public String languageVersion() {
        return LANGUAGE_VERSION;
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  PARSING
    // ═══════════════════════════════════════════════════════════════════════

    @Override
    public SemanticModel parse(Path sourceRoot, LanguageAdapterConfig config) {
        Objects.requireNonNull(sourceRoot, "sourceRoot must not be null");
        Objects.requireNonNull(config, "config must not be null");

        LOG.info("Parsing Java sources under {} (resolveBindings={}, encoding={})",
                sourceRoot, config.resolveBindings(), config.sourceEncoding());

        if (!Files.isDirectory(sourceRoot)) {
            throw new AdapterException("parse",
                    "Source root does not exist or is not a directory: " + sourceRoot);
        }

        List<Path> javaFiles = collectJavaFiles(sourceRoot, config);
        LOG.info("Discovered {} Java source files", javaFiles.size());

        SemanticModel.Builder modelBuilder = SemanticModel.builder()
                .languageId(LANGUAGE_ID)
                .languageVersion(LANGUAGE_VERSION)
                .sourceRoot(sourceRoot);

        Map<String, List<String>> packageFileMap = new LinkedHashMap<>();

        for (Path javaFile : javaFiles) {
            try {
                String source = Files.readString(javaFile, Charset.forName(config.sourceEncoding()));
                String relativePath = sourceRoot.relativize(javaFile).toString();

                CompilationUnit cu = parseCompilationUnit(source, javaFile.getFileName().toString(),
                        sourceRoot, config);

                extractFromCompilationUnit(cu, relativePath, modelBuilder, packageFileMap);

            } catch (IOException e) {
                LOG.warn("Failed to read source file: {}", javaFile, e);
            } catch (Exception e) {
                LOG.warn("Failed to parse source file: {}", javaFile, e);
            }
        }

        buildPackageInfos(packageFileMap, modelBuilder);

        modelBuilder.putMetadata("fileCount", javaFiles.size());
        modelBuilder.putMetadata("adapterVersion", "1.0.0");

        SemanticModel model = modelBuilder.build();
        modelCache.put(sourceRoot, model);

        LOG.info("Built semantic model: {} classes, {} methods, {} call edges",
                model.classCount(), model.methodCount(), model.callGraphEdges().size());

        return model;
    }

    @Override
    public SemanticModel buildSemanticModel(Path sourceRoot) {
        return parse(sourceRoot, LanguageAdapterConfig.defaults());
    }

    /**
     * Creates an Eclipse JDT {@link CompilationUnit} from source text.
     */
    private CompilationUnit parseCompilationUnit(String source, String unitName,
                                                  Path sourceRoot,
                                                  LanguageAdapterConfig config) {
        ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
        parser.setKind(ASTParser.K_COMPILATION_UNIT);
        parser.setSource(source.toCharArray());
        parser.setUnitName(unitName);

        Map<String, String> options = JavaCore.getOptions();
        options.put(JavaCore.COMPILER_SOURCE, LANGUAGE_VERSION);
        options.put(JavaCore.COMPILER_COMPLIANCE, LANGUAGE_VERSION);
        options.put(JavaCore.COMPILER_CODEGEN_TARGET_PLATFORM, LANGUAGE_VERSION);
        parser.setCompilerOptions(options);

        if (config.resolveBindings()) {
            parser.setResolveBindings(true);
            parser.setBindingsRecovery(true);

            String[] sourcepaths = config.sourcepathEntries().isEmpty()
                    ? new String[]{sourceRoot.toAbsolutePath().toString()}
                    : config.sourcepathEntries().toArray(String[]::new);

            String[] classpaths = config.classpathEntries().isEmpty()
                    ? null
                    : config.classpathEntries().toArray(String[]::new);

            String[] encodings = new String[sourcepaths.length];
            Arrays.fill(encodings, config.sourceEncoding());

            parser.setEnvironment(classpaths, sourcepaths, encodings, true);
        }

        return (CompilationUnit) parser.createAST(null);
    }

    /**
     * Extracts all semantic information from a single compilation unit.
     */
    private void extractFromCompilationUnit(CompilationUnit cu, String relativePath,
                                             SemanticModel.Builder modelBuilder,
                                             Map<String, List<String>> packageFileMap) {
        String packageName = cu.getPackage() != null
                ? cu.getPackage().getName().getFullyQualifiedName()
                : "(default)";

        packageFileMap.computeIfAbsent(packageName, k -> new ArrayList<>()).add(relativePath);

        cu.accept(new ASTVisitor() {

            private String currentClassFqn = null;

            @Override
            public boolean visit(TypeDeclaration node) {
                processTypeDeclaration(node, cu, relativePath, packageName, modelBuilder);
                currentClassFqn = resolveTypeFqn(node, packageName);
                return true;
            }

            @Override
            public boolean visit(EnumDeclaration node) {
                processEnumDeclaration(node, cu, relativePath, packageName, modelBuilder);
                currentClassFqn = packageName + "." + node.getName().getIdentifier();
                return true;
            }

            @Override
            public boolean visit(RecordDeclaration node) {
                processRecordDeclaration(node, cu, relativePath, packageName, modelBuilder);
                currentClassFqn = packageName + "." + node.getName().getIdentifier();
                return true;
            }

            @Override
            public boolean visit(MethodDeclaration node) {
                if (currentClassFqn != null) {
                    processMethodDeclaration(node, cu, currentClassFqn, modelBuilder);
                }
                return true;
            }

            @Override
            public boolean visit(FieldDeclaration node) {
                if (currentClassFqn != null) {
                    processFieldDeclaration(node, cu, currentClassFqn, modelBuilder);
                }
                return true;
            }

            @Override
            public boolean visit(MethodInvocation node) {
                processMethodInvocation(node, cu, modelBuilder);
                return true;
            }
        });
    }

    /**
     * Processes a class or interface type declaration.
     */
    private void processTypeDeclaration(TypeDeclaration node, CompilationUnit cu,
                                         String relativePath, String packageName,
                                         SemanticModel.Builder modelBuilder) {
        String simpleName = node.getName().getIdentifier();
        String fqn = resolveTypeFqn(node, packageName);

        String superClass = null;
        if (node.getSuperclassType() != null) {
            ITypeBinding superBinding = node.getSuperclassType().resolveBinding();
            superClass = superBinding != null
                    ? superBinding.getQualifiedName()
                    : node.getSuperclassType().toString();
        }

        List<String> interfaces = new ArrayList<>();
        for (Object iface : node.superInterfaceTypes()) {
            Type ifaceType = (Type) iface;
            ITypeBinding binding = ifaceType.resolveBinding();
            interfaces.add(binding != null ? binding.getQualifiedName() : ifaceType.toString());
        }

        List<String> modifiers = extractModifiers(node.modifiers());
        List<String> annotations = extractAnnotationNames(node.modifiers());

        int startLine = cu.getLineNumber(node.getStartPosition());
        int endLine = cu.getLineNumber(node.getStartPosition() + node.getLength() - 1);

        modelBuilder.addClass(new SemanticModel.ClassInfo(
                fqn, simpleName, packageName, superClass, interfaces,
                modifiers, annotations, relativePath, startLine, endLine,
                node.isInterface(), false, false));
    }

    /**
     * Processes an enum declaration.
     */
    private void processEnumDeclaration(EnumDeclaration node, CompilationUnit cu,
                                         String relativePath, String packageName,
                                         SemanticModel.Builder modelBuilder) {
        String simpleName = node.getName().getIdentifier();
        String fqn = packageName + "." + simpleName;

        List<String> interfaces = new ArrayList<>();
        for (Object iface : node.superInterfaceTypes()) {
            Type ifaceType = (Type) iface;
            ITypeBinding binding = ifaceType.resolveBinding();
            interfaces.add(binding != null ? binding.getQualifiedName() : ifaceType.toString());
        }

        List<String> modifiers = extractModifiers(node.modifiers());
        List<String> annotations = extractAnnotationNames(node.modifiers());

        int startLine = cu.getLineNumber(node.getStartPosition());
        int endLine = cu.getLineNumber(node.getStartPosition() + node.getLength() - 1);

        modelBuilder.addClass(new SemanticModel.ClassInfo(
                fqn, simpleName, packageName, "java.lang.Enum", interfaces,
                modifiers, annotations, relativePath, startLine, endLine,
                false, true, false));
    }

    /**
     * Processes a record declaration (Java 16+).
     */
    private void processRecordDeclaration(RecordDeclaration node, CompilationUnit cu,
                                           String relativePath, String packageName,
                                           SemanticModel.Builder modelBuilder) {
        String simpleName = node.getName().getIdentifier();
        String fqn = packageName + "." + simpleName;

        List<String> interfaces = new ArrayList<>();
        for (Object iface : node.superInterfaceTypes()) {
            Type ifaceType = (Type) iface;
            ITypeBinding binding = ifaceType.resolveBinding();
            interfaces.add(binding != null ? binding.getQualifiedName() : ifaceType.toString());
        }

        List<String> modifiers = extractModifiers(node.modifiers());
        List<String> annotations = extractAnnotationNames(node.modifiers());

        int startLine = cu.getLineNumber(node.getStartPosition());
        int endLine = cu.getLineNumber(node.getStartPosition() + node.getLength() - 1);

        modelBuilder.addClass(new SemanticModel.ClassInfo(
                fqn, simpleName, packageName, "java.lang.Record", interfaces,
                modifiers, annotations, relativePath, startLine, endLine,
                false, false, true));
    }

    /**
     * Processes a method declaration, extracting signature, metrics, and purity.
     */
    private void processMethodDeclaration(MethodDeclaration node, CompilationUnit cu,
                                           String owningClass,
                                           SemanticModel.Builder modelBuilder) {
        String methodName = node.getName().getIdentifier();

        List<String> paramTypes = new ArrayList<>();
        List<String> paramNames = new ArrayList<>();
        for (Object param : node.parameters()) {
            SingleVariableDeclaration svd = (SingleVariableDeclaration) param;
            ITypeBinding typeBinding = svd.getType().resolveBinding();
            paramTypes.add(typeBinding != null ? typeBinding.getQualifiedName() : svd.getType().toString());
            paramNames.add(svd.getName().getIdentifier());
        }

        String returnType = "void";
        if (node.getReturnType2() != null) {
            ITypeBinding retBinding = node.getReturnType2().resolveBinding();
            returnType = retBinding != null ? retBinding.getQualifiedName() : node.getReturnType2().toString();
        }

        String signature = buildMethodSignature(owningClass, methodName, paramTypes);

        List<String> modifiers = extractModifiers(node.modifiers());
        List<String> annotations = extractAnnotationNames(node.modifiers());

        List<String> thrownExceptions = new ArrayList<>();
        for (Object exc : node.thrownExceptionTypes()) {
            Type excType = (Type) exc;
            ITypeBinding excBinding = excType.resolveBinding();
            thrownExceptions.add(excBinding != null ? excBinding.getQualifiedName() : excType.toString());
        }

        int startLine = cu.getLineNumber(node.getStartPosition());
        int endLine = cu.getLineNumber(node.getStartPosition() + node.getLength() - 1);
        int lineCount = endLine - startLine + 1;

        int cyclomaticComplexity = computeCyclomaticComplexity(node);
        int cognitiveComplexity = computeCognitiveComplexity(node);
        SemanticModel.Purity purity = classifyPurity(node);

        modelBuilder.addMethod(new SemanticModel.MethodInfo(
                signature, methodName, owningClass, returnType,
                paramTypes, paramNames, modifiers, annotations,
                thrownExceptions, startLine, endLine,
                cyclomaticComplexity, cognitiveComplexity, lineCount, purity));
    }

    /**
     * Processes a field declaration.
     */
    private void processFieldDeclaration(FieldDeclaration node, CompilationUnit cu,
                                          String owningClass,
                                          SemanticModel.Builder modelBuilder) {
        ITypeBinding typeBinding = node.getType().resolveBinding();
        String typeName = typeBinding != null ? typeBinding.getQualifiedName() : node.getType().toString();

        List<String> modifiers = extractModifiers(node.modifiers());
        List<String> annotations = extractAnnotationNames(node.modifiers());

        for (Object frag : node.fragments()) {
            VariableDeclarationFragment vdf = (VariableDeclarationFragment) frag;
            String fieldName = vdf.getName().getIdentifier();
            int line = cu.getLineNumber(vdf.getStartPosition());

            modelBuilder.addField(new SemanticModel.FieldInfo(
                    fieldName, owningClass, typeName, modifiers, annotations, line));
        }
    }

    /**
     * Processes a method invocation to build call graph edges.
     */
    private void processMethodInvocation(MethodInvocation node, CompilationUnit cu,
                                          SemanticModel.Builder modelBuilder) {
        IMethodBinding methodBinding = node.resolveMethodBinding();
        if (methodBinding == null) return;

        ASTNode enclosingMethod = findEnclosingMethod(node);
        if (enclosingMethod == null) return;

        String callerSig = buildSignatureFromDeclaration(enclosingMethod);
        if (callerSig == null) return;

        String calleeClass = methodBinding.getDeclaringClass() != null
                ? methodBinding.getDeclaringClass().getQualifiedName() : "unknown";
        String calleeName = methodBinding.getName();

        List<String> calleeParamTypes = new ArrayList<>();
        for (ITypeBinding paramType : methodBinding.getParameterTypes()) {
            calleeParamTypes.add(paramType.getQualifiedName());
        }

        String calleeSig = buildMethodSignature(calleeClass, calleeName, calleeParamTypes);
        int callSiteLine = cu.getLineNumber(node.getStartPosition());
        ITypeBinding declaringClass = methodBinding.getDeclaringClass();
        boolean declaringClassFinal = declaringClass != null
                && Modifier.isFinal(declaringClass.getModifiers());
        boolean isVirtual = !Modifier.isStatic(methodBinding.getModifiers())
                && !Modifier.isFinal(methodBinding.getModifiers())
                && !declaringClassFinal;

        modelBuilder.addCallGraphEdge(new SemanticModel.CallGraphEdge(
                callerSig, calleeSig, callSiteLine, isVirtual));
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  REFACTORING
    // ═══════════════════════════════════════════════════════════════════════

    @Override
    public List<RefactorCandidate> listRefactorCandidates(SemanticModel model, RefactorRuleSet rules) {
        Objects.requireNonNull(model, "model must not be null");
        Objects.requireNonNull(rules, "rules must not be null");

        LOG.info("Evaluating {} refactoring rules against model with {} classes",
                rules.ruleIds().size(), model.classCount());

        List<RefactorCandidate> candidates = rules.evaluate(model);

        LOG.info("Found {} refactoring candidates", candidates.size());
        if (LOG.isDebugEnabled()) {
            Map<String, Long> byRule = candidates.stream()
                    .collect(Collectors.groupingBy(RefactorCandidate::ruleId, Collectors.counting()));
            byRule.forEach((rule, count) ->
                    LOG.debug("  Rule '{}': {} candidates", rule, count));
        }

        return Collections.unmodifiableList(candidates);
    }

    @Override
    public PatchResult applyRefactor(RefactorCandidate candidate, Path sourceRoot) {
        Objects.requireNonNull(candidate, "candidate must not be null");
        Objects.requireNonNull(sourceRoot, "sourceRoot must not be null");

        LOG.info("Applying refactoring '{}' to {} (lines {}-{})",
                candidate.ruleId(), candidate.sourceFile(),
                candidate.startLine(), candidate.endLine());

        Path targetFile = sourceRoot.resolve(candidate.sourceFile());
        if (!Files.isRegularFile(targetFile)) {
            return PatchResult.failure(candidate.candidateId(),
                    "Source file not found: " + targetFile);
        }

        try {
            String originalSource = Files.readString(targetFile);
            String beforeHash = computeAstHash(originalSource, targetFile.getFileName().toString(), sourceRoot);

            String patchedSource = applyAstRewrite(originalSource,
                    targetFile.getFileName().toString(), sourceRoot, candidate);

            String afterHash = computeAstHash(patchedSource, targetFile.getFileName().toString(), sourceRoot);
            String unifiedDiff = generateUnifiedDiff(
                    candidate.sourceFile(), originalSource, patchedSource);

            Files.writeString(targetFile, patchedSource);

            LOG.info("Successfully applied refactoring. AST hash {} -> {}", beforeHash, afterHash);

            return PatchResult.builder()
                    .candidateId(candidate.candidateId())
                    .unifiedDiff(unifiedDiff)
                    .beforeAstHash(beforeHash)
                    .afterAstHash(afterHash)
                    .addAffectedFile(candidate.sourceFile())
                    .success(true)
                    .putMetadata("ruleId", candidate.ruleId())
                    .putMetadata("linesAffected", candidate.lineSpan())
                    .build();

        } catch (Exception e) {
            LOG.error("Failed to apply refactoring '{}' to {}", candidate.ruleId(), candidate.sourceFile(), e);
            return PatchResult.failure(candidate.candidateId(), e.getMessage());
        }
    }

    /**
     * Performs the actual AST rewrite by locating the target node and replacing
     * it with the proposed transformation.
     */
    private String applyAstRewrite(String source, String unitName, Path sourceRoot,
                                    RefactorCandidate candidate) {
        CompilationUnit cu = parseCompilationUnit(source, unitName, sourceRoot,
                LanguageAdapterConfig.defaults());

        AST ast = cu.getAST();
        ASTRewrite rewrite = ASTRewrite.create(ast);

        ASTNode targetNode = findNodeAtLines(cu, candidate.startLine(), candidate.endLine());

        if (targetNode != null) {
            ASTParser snippetParser = ASTParser.newParser(AST.getJLSLatest());
            snippetParser.setKind(ASTParser.K_STATEMENTS);
            snippetParser.setSource(candidate.proposedAfterSnippet().toCharArray());

            Map<String, String> options = JavaCore.getOptions();
            options.put(JavaCore.COMPILER_SOURCE, LANGUAGE_VERSION);
            options.put(JavaCore.COMPILER_COMPLIANCE, LANGUAGE_VERSION);
            snippetParser.setCompilerOptions(options);

            ASTNode replacement = snippetParser.createAST(null);

            if (replacement instanceof Block block && !block.statements().isEmpty()) {
                if (block.statements().size() == 1 && targetNode instanceof Statement) {
                    ASTNode singleStmt = (ASTNode) block.statements().get(0);
                    ASTNode copied = ASTNode.copySubtree(ast, singleStmt);
                    rewrite.replace(targetNode, copied, null);
                } else {
                    ASTNode copied = ASTNode.copySubtree(ast, block);
                    rewrite.replace(targetNode, copied, null);
                }
            }
        } else {
            LOG.warn("Could not locate AST node at lines {}-{}; falling back to text replacement",
                    candidate.startLine(), candidate.endLine());
            return applyTextReplacement(source, candidate);
        }

        try {
            Document document = new Document(source);
            TextEdit edits = rewrite.rewriteAST(document, JavaCore.getOptions());
            edits.apply(document);
            return document.get();
        } catch (Exception e) {
            LOG.warn("AST rewrite failed; falling back to text replacement", e);
            return applyTextReplacement(source, candidate);
        }
    }

    /**
     * Falls back to line-based text replacement when AST rewrite is not feasible.
     */
    private String applyTextReplacement(String source, RefactorCandidate candidate) {
        String[] lines = source.split("\n", -1);
        StringBuilder result = new StringBuilder();

        for (int i = 0; i < lines.length; i++) {
            int lineNum = i + 1;
            if (lineNum == candidate.startLine()) {
                result.append(candidate.proposedAfterSnippet());
                if (!candidate.proposedAfterSnippet().endsWith("\n")) {
                    result.append("\n");
                }
            } else if (lineNum > candidate.startLine() && lineNum <= candidate.endLine()) {
                continue;
            } else {
                result.append(lines[i]);
                if (i < lines.length - 1) {
                    result.append("\n");
                }
            }
        }

        return result.toString();
    }

    /**
     * Locates the AST node that spans the given line range.
     */
    private ASTNode findNodeAtLines(CompilationUnit cu, int startLine, int endLine) {
        final ASTNode[] found = {null};

        cu.accept(new ASTVisitor() {
            @Override
            public void preVisit(ASTNode node) {
                int nodeStart = cu.getLineNumber(node.getStartPosition());
                int nodeEnd = cu.getLineNumber(node.getStartPosition() + node.getLength() - 1);

                if (nodeStart == startLine && nodeEnd == endLine) {
                    found[0] = node;
                } else if (found[0] == null && nodeStart >= startLine && nodeEnd <= endLine) {
                    found[0] = node;
                }
            }
        });

        return found[0];
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  VERIFICATION
    // ═══════════════════════════════════════════════════════════════════════

    @Override
    public VerificationResult verifyPatch(PatchResult patch, Path sourceRoot, VerificationConfig config) {
        Objects.requireNonNull(patch, "patch must not be null");
        Objects.requireNonNull(sourceRoot, "sourceRoot must not be null");
        Objects.requireNonNull(config, "config must not be null");

        LOG.info("Verifying patch {} with config: compile={}, tests={}, ast={}, bytecode={}, api={}",
                patch.patchId(), config.runCompilation(), config.runTests(),
                config.compareAstStructure(), config.compareBytecode(), config.checkApiSurface());

        VerificationResult.Builder resultBuilder = VerificationResult.builder()
                .patchId(patch.patchId())
                .beforeAstHash(patch.beforeAstHash())
                .afterAstHash(patch.afterAstHash());

        if (config.runCompilation()) {
            VerificationResult.LayerResult compileResult = verifyCompilation(sourceRoot);
            resultBuilder.compileSuccess(compileResult.passed());
            resultBuilder.addLayerResult(compileResult);
        } else {
            resultBuilder.compileSuccess(true);
        }

        if (config.runTests()) {
            VerificationResult.LayerResult testResult = verifyTests(sourceRoot, config);
            resultBuilder.testSuccess(testResult.passed());
            resultBuilder.addLayerResult(testResult);
        } else {
            resultBuilder.testSuccess(true);
        }

        if (config.compareAstStructure()) {
            VerificationResult.LayerResult astResult = verifyAstStructure(patch);
            resultBuilder.astStructuralMatchScore(astResult.score());
            resultBuilder.addLayerResult(astResult);
        } else {
            resultBuilder.astStructuralMatchScore(1.0);
        }

        if (config.compareBytecode()) {
            VerificationResult.LayerResult bytecodeResult = verifyBytecodeDescriptors(patch, sourceRoot);
            resultBuilder.bytecodeDescriptorMatch(bytecodeResult.passed());
            resultBuilder.addLayerResult(bytecodeResult);
        } else {
            resultBuilder.bytecodeDescriptorMatch(true);
        }

        if (config.checkApiSurface()) {
            VerificationResult.LayerResult apiResult = verifyApiSurface(patch, sourceRoot);
            resultBuilder.apiSurfaceCompatible(apiResult.passed());
            resultBuilder.addLayerResult(apiResult);
        } else {
            resultBuilder.apiSurfaceCompatible(true);
        }

        if (config.runGoldenMaster() && config.goldenMasterPath() != null) {
            VerificationResult.LayerResult gmResult = verifyGoldenMaster(
                    sourceRoot, Path.of(config.goldenMasterPath()));
            resultBuilder.goldenMasterMatch(gmResult.passed());
            resultBuilder.addLayerResult(gmResult);
        } else {
            resultBuilder.goldenMasterMatch(true);
        }

        VerificationResult result = resultBuilder.build();
        LOG.info("Verification complete: verdict={}, semanticRisk={}",
                result.verdict(), String.format("%.4f", result.semanticRiskScore()));

        return result;
    }

    /**
     * Verifies that the patched source compiles without errors by re-parsing
     * all Java files and checking for compilation problems.
     */
    private VerificationResult.LayerResult verifyCompilation(Path sourceRoot) {
        long start = System.currentTimeMillis();

        try {
            List<Path> javaFiles = collectJavaFiles(sourceRoot, LanguageAdapterConfig.defaults());
            List<String> errors = new ArrayList<>();

            for (Path file : javaFiles) {
                String source = Files.readString(file);
                CompilationUnit cu = parseCompilationUnit(source, file.getFileName().toString(),
                        sourceRoot, LanguageAdapterConfig.defaults());

                for (IProblem problem : cu.getProblems()) {
                    if (problem.isError()) {
                        errors.add(file.getFileName() + ":" + problem.getSourceLineNumber()
                                + " - " + problem.getMessage());
                    }
                }
            }

            long elapsed = System.currentTimeMillis() - start;
            boolean passed = errors.isEmpty();
            String details = passed
                    ? "All files compiled successfully"
                    : "Compilation errors:\n" + String.join("\n", errors);

            return new VerificationResult.LayerResult(
                    "compilation", passed, passed ? 1.0 : 0.0, details, elapsed);

        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - start;
            return new VerificationResult.LayerResult(
                    "compilation", false, 0.0,
                    "Compilation check failed: " + e.getMessage(), elapsed);
        }
    }

    /**
     * Verifies that tests pass after the patch. Delegates to an external
     * process for test execution.
     */
    private VerificationResult.LayerResult verifyTests(Path sourceRoot, VerificationConfig config) {
        long start = System.currentTimeMillis();

        String testCommand = config.testCommand() != null
                ? config.testCommand()
                : detectTestCommand(sourceRoot);

        if (testCommand == null) {
            long elapsed = System.currentTimeMillis() - start;
            return new VerificationResult.LayerResult(
                    "tests", true, 0.5,
                    "No test framework detected; skipping test execution", elapsed);
        }

        try {
            ProcessBuilder pb = new ProcessBuilder("sh", "-c", testCommand)
                    .directory(sourceRoot.toFile())
                    .redirectErrorStream(true);

            Process process = pb.start();
            String output = new String(process.getInputStream().readAllBytes());
            boolean exited = process.waitFor(config.timeoutSeconds(),
                    java.util.concurrent.TimeUnit.SECONDS);

            long elapsed = System.currentTimeMillis() - start;

            if (!exited) {
                process.destroyForcibly();
                return new VerificationResult.LayerResult(
                        "tests", false, 0.0,
                        "Test execution timed out after " + config.timeoutSeconds() + "s",
                        elapsed);
            }

            boolean passed = process.exitValue() == 0;
            return new VerificationResult.LayerResult(
                    "tests", passed, passed ? 1.0 : 0.0,
                    passed ? "All tests passed" : "Test failures detected:\n" + truncate(output, 2000),
                    elapsed);

        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - start;
            return new VerificationResult.LayerResult(
                    "tests", false, 0.0,
                    "Test execution failed: " + e.getMessage(), elapsed);
        }
    }

    /**
     * Verifies AST structural equivalence between before and after states
     * by comparing AST hashes.
     */
    private VerificationResult.LayerResult verifyAstStructure(PatchResult patch) {
        long start = System.currentTimeMillis();

        boolean exactMatch = patch.beforeAstHash().equals(patch.afterAstHash());

        double score;
        String details;
        if (exactMatch) {
            score = 1.0;
            details = "AST structure unchanged (cosmetic-only change)";
        } else {
            score = 0.85;
            details = "AST structure differs (expected for meaningful refactoring). " +
                    "Before hash: " + patch.beforeAstHash() +
                    ", After hash: " + patch.afterAstHash();
        }

        long elapsed = System.currentTimeMillis() - start;
        return new VerificationResult.LayerResult("astStructure", true, score, details, elapsed);
    }

    /**
     * Verifies that bytecode method descriptors remain compatible after refactoring.
     * Compares method signatures from the semantic model.
     */
    private VerificationResult.LayerResult verifyBytecodeDescriptors(PatchResult patch, Path sourceRoot) {
        long start = System.currentTimeMillis();

        try {
            SemanticModel afterModel = parse(sourceRoot, LanguageAdapterConfig.defaults());
            SemanticModel beforeModel = modelCache.get(sourceRoot);

            if (beforeModel == null) {
                long elapsed = System.currentTimeMillis() - start;
                return new VerificationResult.LayerResult(
                        "bytecodeDescriptor", true, 0.5,
                        "No cached pre-patch model available for comparison", elapsed);
            }

            Set<String> beforeSignatures = beforeModel.methods().stream()
                    .map(SemanticModel.MethodInfo::signature)
                    .collect(Collectors.toSet());

            Set<String> afterSignatures = afterModel.methods().stream()
                    .map(SemanticModel.MethodInfo::signature)
                    .collect(Collectors.toSet());

            Set<String> removedMethods = new LinkedHashSet<>(beforeSignatures);
            removedMethods.removeAll(afterSignatures);

            long elapsed = System.currentTimeMillis() - start;
            boolean passed = removedMethods.isEmpty();
            String details = passed
                    ? "All method descriptors preserved"
                    : "Removed method descriptors: " + removedMethods;

            return new VerificationResult.LayerResult(
                    "bytecodeDescriptor", passed, passed ? 1.0 : 0.0, details, elapsed);

        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - start;
            return new VerificationResult.LayerResult(
                    "bytecodeDescriptor", false, 0.0,
                    "Bytecode descriptor check failed: " + e.getMessage(), elapsed);
        }
    }

    /**
     * Verifies that the public API surface is preserved after refactoring.
     */
    private VerificationResult.LayerResult verifyApiSurface(PatchResult patch, Path sourceRoot) {
        long start = System.currentTimeMillis();

        try {
            SemanticModel afterModel = parse(sourceRoot, LanguageAdapterConfig.defaults());
            SemanticModel beforeModel = modelCache.get(sourceRoot);

            if (beforeModel == null) {
                long elapsed = System.currentTimeMillis() - start;
                return new VerificationResult.LayerResult(
                        "apiSurface", true, 0.5,
                        "No cached pre-patch model available for API comparison", elapsed);
            }

            Set<String> beforePublicMethods = beforeModel.methods().stream()
                    .filter(m -> m.modifiers().contains("public"))
                    .map(SemanticModel.MethodInfo::signature)
                    .collect(Collectors.toSet());

            Set<String> afterPublicMethods = afterModel.methods().stream()
                    .filter(m -> m.modifiers().contains("public"))
                    .map(SemanticModel.MethodInfo::signature)
                    .collect(Collectors.toSet());

            Set<String> removedApi = new LinkedHashSet<>(beforePublicMethods);
            removedApi.removeAll(afterPublicMethods);

            long elapsed = System.currentTimeMillis() - start;
            boolean passed = removedApi.isEmpty();
            String details = passed
                    ? "Public API surface fully preserved"
                    : "Removed public methods: " + removedApi;

            return new VerificationResult.LayerResult(
                    "apiSurface", passed, passed ? 1.0 : 0.0, details, elapsed);

        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - start;
            return new VerificationResult.LayerResult(
                    "apiSurface", false, 0.0,
                    "API surface check failed: " + e.getMessage(), elapsed);
        }
    }

    /**
     * Verifies against a golden master — a set of known-good outputs captured
     * from a previous verified state.
     */
    private VerificationResult.LayerResult verifyGoldenMaster(Path sourceRoot, Path goldenMasterPath) {
        long start = System.currentTimeMillis();

        if (!Files.isDirectory(goldenMasterPath)) {
            long elapsed = System.currentTimeMillis() - start;
            return new VerificationResult.LayerResult(
                    "goldenMaster", false, 0.0,
                    "Golden master path does not exist: " + goldenMasterPath, elapsed);
        }

        try {
            SemanticModel currentModel = parse(sourceRoot, LanguageAdapterConfig.defaults());
            SemanticModel goldenModel = parse(goldenMasterPath, LanguageAdapterConfig.defaults());

            boolean classCountMatch = currentModel.classCount() == goldenModel.classCount();
            boolean methodCountMatch = currentModel.methodCount() == goldenModel.methodCount();

            long elapsed = System.currentTimeMillis() - start;
            boolean passed = classCountMatch && methodCountMatch;
            double score = ((classCountMatch ? 0.5 : 0.0) + (methodCountMatch ? 0.5 : 0.0));

            return new VerificationResult.LayerResult(
                    "goldenMaster", passed, score,
                    String.format("Classes: %d/%d, Methods: %d/%d",
                            currentModel.classCount(), goldenModel.classCount(),
                            currentModel.methodCount(), goldenModel.methodCount()),
                    elapsed);

        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - start;
            return new VerificationResult.LayerResult(
                    "goldenMaster", false, 0.0,
                    "Golden master comparison failed: " + e.getMessage(), elapsed);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  UTILITY METHODS
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Collects all {@code .java} files under the source root, respecting
     * exclude patterns from the configuration.
     */
    private List<Path> collectJavaFiles(Path sourceRoot, LanguageAdapterConfig config) {
        List<Path> files = new ArrayList<>();
        Set<PathMatcher> excludeMatchers = config.excludePatterns().stream()
                .map(pattern -> sourceRoot.getFileSystem().getPathMatcher("glob:" + pattern))
                .collect(Collectors.toSet());

        try {
            Files.walkFileTree(sourceRoot, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (file.toString().endsWith(".java")) {
                        Path relativePath = sourceRoot.relativize(file);
                        boolean excluded = excludeMatchers.stream()
                                .anyMatch(m -> m.matches(relativePath));
                        if (!excluded) {
                            files.add(file);
                        }
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    LOG.warn("Cannot access file: {}", file, exc);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            throw new AdapterException("parse", "Failed to walk source tree: " + sourceRoot, e);
        }

        return files;
    }

    /**
     * Extracts modifier keywords from a list of AST modifier nodes.
     */
    @SuppressWarnings("unchecked")
    private List<String> extractModifiers(List<?> modifiers) {
        List<String> result = new ArrayList<>();
        for (Object mod : modifiers) {
            if (mod instanceof org.eclipse.jdt.core.dom.Modifier m) {
                result.add(m.getKeyword().toString());
            }
        }
        return result;
    }

    /**
     * Extracts annotation type names from a list of AST modifier/annotation nodes.
     */
    private List<String> extractAnnotationNames(List<?> modifiers) {
        List<String> result = new ArrayList<>();
        for (Object mod : modifiers) {
            if (mod instanceof Annotation ann) {
                ITypeBinding binding = ann.resolveTypeBinding();
                result.add(binding != null ? binding.getQualifiedName() : ann.getTypeName().toString());
            }
        }
        return result;
    }

    /**
     * Resolves the fully qualified name of a type declaration.
     */
    private String resolveTypeFqn(TypeDeclaration node, String packageName) {
        ITypeBinding binding = node.resolveBinding();
        if (binding != null) {
            return binding.getQualifiedName();
        }
        return packageName + "." + node.getName().getIdentifier();
    }

    /**
     * Builds a unique method signature string.
     */
    private String buildMethodSignature(String owningClass, String methodName, List<String> paramTypes) {
        return owningClass + "#" + methodName + "(" + String.join(",", paramTypes) + ")";
    }

    /**
     * Builds a method signature from an enclosing MethodDeclaration AST node.
     */
    private String buildSignatureFromDeclaration(ASTNode enclosingMethod) {
        if (!(enclosingMethod instanceof MethodDeclaration md)) return null;

        ASTNode parent = md.getParent();
        String owningClass = "unknown";
        if (parent instanceof TypeDeclaration td) {
            ITypeBinding binding = td.resolveBinding();
            owningClass = binding != null ? binding.getQualifiedName() : td.getName().getIdentifier();
        }

        List<String> paramTypes = new ArrayList<>();
        for (Object param : md.parameters()) {
            SingleVariableDeclaration svd = (SingleVariableDeclaration) param;
            ITypeBinding tb = svd.getType().resolveBinding();
            paramTypes.add(tb != null ? tb.getQualifiedName() : svd.getType().toString());
        }

        return buildMethodSignature(owningClass, md.getName().getIdentifier(), paramTypes);
    }

    /**
     * Finds the enclosing MethodDeclaration for a given AST node.
     */
    private ASTNode findEnclosingMethod(ASTNode node) {
        ASTNode current = node.getParent();
        while (current != null) {
            if (current instanceof MethodDeclaration) return current;
            current = current.getParent();
        }
        return null;
    }

    /**
     * Computes McCabe cyclomatic complexity for a method.
     *
     * <p>Counts decision points: if, while, for, do, case, catch, &&, ||, ?:
     * and starts with a base complexity of 1.</p>
     */
    private int computeCyclomaticComplexity(MethodDeclaration node) {
        int[] complexity = {1};

        node.accept(new ASTVisitor() {
            @Override public boolean visit(IfStatement n) { complexity[0]++; return true; }
            @Override public boolean visit(WhileStatement n) { complexity[0]++; return true; }
            @Override public boolean visit(ForStatement n) { complexity[0]++; return true; }
            @Override public boolean visit(EnhancedForStatement n) { complexity[0]++; return true; }
            @Override public boolean visit(DoStatement n) { complexity[0]++; return true; }
            @Override public boolean visit(SwitchCase n) { if (!n.isDefault()) complexity[0]++; return true; }
            @Override public boolean visit(CatchClause n) { complexity[0]++; return true; }
            @Override public boolean visit(ConditionalExpression n) { complexity[0]++; return true; }
            @Override public boolean visit(InfixExpression n) {
                if (n.getOperator() == InfixExpression.Operator.CONDITIONAL_AND
                        || n.getOperator() == InfixExpression.Operator.CONDITIONAL_OR) {
                    complexity[0]++;
                }
                return true;
            }
        });

        return complexity[0];
    }

    /**
     * Computes a simplified cognitive complexity score for a method.
     *
     * <p>Increments for nesting depth of control structures, with additional
     * penalties for deeply nested logic.</p>
     */
    private int computeCognitiveComplexity(MethodDeclaration node) {
        int[] complexity = {0};
        int[] nestingLevel = {0};

        node.accept(new ASTVisitor() {
            @Override public boolean visit(IfStatement n) {
                complexity[0] += 1 + nestingLevel[0];
                nestingLevel[0]++;
                return true;
            }
            @Override public void endVisit(IfStatement n) { nestingLevel[0]--; }

            @Override public boolean visit(WhileStatement n) {
                complexity[0] += 1 + nestingLevel[0];
                nestingLevel[0]++;
                return true;
            }
            @Override public void endVisit(WhileStatement n) { nestingLevel[0]--; }

            @Override public boolean visit(ForStatement n) {
                complexity[0] += 1 + nestingLevel[0];
                nestingLevel[0]++;
                return true;
            }
            @Override public void endVisit(ForStatement n) { nestingLevel[0]--; }

            @Override public boolean visit(EnhancedForStatement n) {
                complexity[0] += 1 + nestingLevel[0];
                nestingLevel[0]++;
                return true;
            }
            @Override public void endVisit(EnhancedForStatement n) { nestingLevel[0]--; }

            @Override public boolean visit(DoStatement n) {
                complexity[0] += 1 + nestingLevel[0];
                nestingLevel[0]++;
                return true;
            }
            @Override public void endVisit(DoStatement n) { nestingLevel[0]--; }

            @Override public boolean visit(CatchClause n) {
                complexity[0] += 1 + nestingLevel[0];
                nestingLevel[0]++;
                return true;
            }
            @Override public void endVisit(CatchClause n) { nestingLevel[0]--; }

            @Override public boolean visit(ConditionalExpression n) {
                complexity[0] += 1 + nestingLevel[0];
                return true;
            }

            @Override public boolean visit(InfixExpression n) {
                if (n.getOperator() == InfixExpression.Operator.CONDITIONAL_AND
                        || n.getOperator() == InfixExpression.Operator.CONDITIONAL_OR) {
                    complexity[0]++;
                }
                return true;
            }
        });

        return complexity[0];
    }

    /**
     * Classifies method purity through heuristic static analysis.
     *
     * <p>Checks for field assignments (MUTATES_STATE), field reads (READS_STATE),
     * and method invocations on non-pure targets (SIDE_EFFECTING).</p>
     */
    private SemanticModel.Purity classifyPurity(MethodDeclaration node) {
        if (node.getBody() == null) {
            return SemanticModel.Purity.UNKNOWN;
        }

        boolean[] mutatesState = {false};
        boolean[] readsState = {false};
        boolean[] hasSideEffects = {false};

        node.accept(new ASTVisitor() {
            @Override
            public boolean visit(Assignment assignment) {
                Expression lhs = assignment.getLeftHandSide();
                if (lhs instanceof FieldAccess || lhs instanceof QualifiedName) {
                    mutatesState[0] = true;
                } else if (lhs instanceof SimpleName sn) {
                    IBinding binding = sn.resolveBinding();
                    if (binding instanceof IVariableBinding vb && vb.isField()) {
                        mutatesState[0] = true;
                    }
                }
                return true;
            }

            @Override
            public boolean visit(FieldAccess fa) {
                readsState[0] = true;
                return true;
            }

            @Override
            public boolean visit(MethodInvocation mi) {
                IMethodBinding binding = mi.resolveMethodBinding();
                if (binding != null) {
                    String declaringClass = binding.getDeclaringClass() != null
                            ? binding.getDeclaringClass().getQualifiedName() : "";
                    if (declaringClass.startsWith("java.io.")
                            || declaringClass.startsWith("java.net.")
                            || declaringClass.startsWith("java.nio.file.")
                            || "java.lang.System".equals(declaringClass)
                            || "java.lang.Runtime".equals(declaringClass)
                            || "java.lang.ProcessBuilder".equals(declaringClass)) {
                        hasSideEffects[0] = true;
                    }
                }
                return true;
            }
        });

        if (hasSideEffects[0]) return SemanticModel.Purity.SIDE_EFFECTING;
        if (mutatesState[0]) return SemanticModel.Purity.MUTATES_STATE;
        if (readsState[0]) return SemanticModel.Purity.READS_STATE;
        return SemanticModel.Purity.PURE;
    }

    /**
     * Builds {@link SemanticModel.PackageInfo} records from the accumulated
     * package-to-file mapping.
     */
    private void buildPackageInfos(Map<String, List<String>> packageFileMap,
                                    SemanticModel.Builder modelBuilder) {
        Set<String> allPackages = packageFileMap.keySet();

        for (Map.Entry<String, List<String>> entry : packageFileMap.entrySet()) {
            String pkgName = entry.getKey();
            List<String> files = entry.getValue();

            List<String> subPackages = allPackages.stream()
                    .filter(p -> p.startsWith(pkgName + ".") && !p.substring(pkgName.length() + 1).contains("."))
                    .toList();

            modelBuilder.addPackage(new SemanticModel.PackageInfo(pkgName, files, subPackages));
        }
    }

    /**
     * Computes a SHA-256 hash of the AST structure (ignoring whitespace and comments)
     * for structural comparison.
     */
    private String computeAstHash(String source, String unitName, Path sourceRoot) {
        try {
            CompilationUnit cu = parseCompilationUnit(source, unitName, sourceRoot,
                    new LanguageAdapterConfig(false, false, "UTF-8",
                            List.of(), List.of(), List.of(), Map.of()));

            StringBuilder astStructure = new StringBuilder();
            cu.accept(new ASTVisitor() {
                @Override
                public void preVisit(ASTNode node) {
                    astStructure.append(node.getNodeType()).append(':');
                    if (node instanceof SimpleName sn) {
                        astStructure.append(sn.getIdentifier());
                    }
                    astStructure.append(';');
                }
            });

            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(astStructure.toString().getBytes());
            return bytesToHex(hash);

        } catch (NoSuchAlgorithmException e) {
            throw new AdapterException("hash", "SHA-256 algorithm not available", e);
        }
    }

    /**
     * Generates a unified diff between two versions of a source file.
     */
    private String generateUnifiedDiff(String fileName, String before, String after) {
        String[] beforeLines = before.split("\n", -1);
        String[] afterLines = after.split("\n", -1);

        StringBuilder diff = new StringBuilder();
        diff.append("--- a/").append(fileName).append('\n');
        diff.append("+++ b/").append(fileName).append('\n');

        int contextLines = 3;
        List<int[]> hunks = computeDiffHunks(beforeLines, afterLines, contextLines);

        for (int[] hunk : hunks) {
            int beforeStart = hunk[0];
            int beforeLen = hunk[1];
            int afterStart = hunk[2];
            int afterLen = hunk[3];

            diff.append(String.format("@@ -%d,%d +%d,%d @@%n",
                    beforeStart + 1, beforeLen, afterStart + 1, afterLen));

            int bi = beforeStart;
            int ai = afterStart;
            int biEnd = beforeStart + beforeLen;
            int aiEnd = afterStart + afterLen;

            while (bi < biEnd || ai < aiEnd) {
                if (bi < biEnd && ai < aiEnd && bi < beforeLines.length
                        && ai < afterLines.length && beforeLines[bi].equals(afterLines[ai])) {
                    diff.append(' ').append(beforeLines[bi]).append('\n');
                    bi++;
                    ai++;
                } else {
                    if (bi < biEnd && bi < beforeLines.length) {
                        diff.append('-').append(beforeLines[bi]).append('\n');
                        bi++;
                    }
                    if (ai < aiEnd && ai < afterLines.length) {
                        diff.append('+').append(afterLines[ai]).append('\n');
                        ai++;
                    }
                }
            }
        }

        return diff.toString();
    }

    /**
     * Computes diff hunk ranges for unified diff generation.
     * Uses a simple longest-common-subsequence approach.
     */
    private List<int[]> computeDiffHunks(String[] before, String[] after, int contextLines) {
        List<int[]> changes = new ArrayList<>();
        int bi = 0, ai = 0;

        while (bi < before.length && ai < after.length) {
            if (before[bi].equals(after[ai])) {
                bi++;
                ai++;
            } else {
                int changeStartBi = bi;
                int changeStartAi = ai;

                while (bi < before.length && (ai >= after.length || !before[bi].equals(after[ai]))) {
                    bi++;
                }
                while (ai < after.length && (bi >= before.length || !before[bi].equals(after[ai]))) {
                    ai++;
                }

                int hunkStart = Math.max(0, changeStartBi - contextLines);
                int hunkEndBefore = Math.min(before.length, bi + contextLines);
                int hunkEndAfter = Math.min(after.length, ai + contextLines);

                changes.add(new int[]{
                        hunkStart,
                        hunkEndBefore - hunkStart,
                        Math.max(0, changeStartAi - contextLines),
                        hunkEndAfter - Math.max(0, changeStartAi - contextLines)
                });
            }
        }

        if (changes.isEmpty() && (bi < before.length || ai < after.length)) {
            changes.add(new int[]{
                    Math.max(0, bi - contextLines),
                    before.length - Math.max(0, bi - contextLines),
                    Math.max(0, ai - contextLines),
                    after.length - Math.max(0, ai - contextLines)
            });
        }

        return changes;
    }

    /**
     * Detects which test command to use based on project structure.
     */
    private String detectTestCommand(Path sourceRoot) {
        if (Files.exists(sourceRoot.resolve("pom.xml"))) {
            return "mvn test -q";
        }
        if (Files.exists(sourceRoot.resolve("build.gradle"))
                || Files.exists(sourceRoot.resolve("build.gradle.kts"))) {
            return "gradle test --quiet";
        }
        return null;
    }

    /**
     * Truncates a string to the specified maximum length, appending "..." if truncated.
     */
    private String truncate(String text, int maxLength) {
        if (text == null || text.length() <= maxLength) return text;
        return text.substring(0, maxLength) + "... [truncated]";
    }

    /**
     * Converts a byte array to a lowercase hex string.
     */
    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
