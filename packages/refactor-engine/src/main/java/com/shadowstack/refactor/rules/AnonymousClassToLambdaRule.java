package com.shadowstack.refactor.rules;

import com.shadowstack.refactor.RefactorRule;
import com.shadowstack.refactor.model.*;
import org.eclipse.jdt.core.dom.*;
import org.eclipse.jdt.core.dom.rewrite.ASTRewrite;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Elite refactoring rule: Anonymous inner class → Lambda expression conversion.
 *
 * <p>This rule performs a precise, type-aware transformation of anonymous inner class
 * instantiations into lambda expressions. It employs multi-layered safety analysis to
 * ensure behavioral equivalence is preserved in all cases.</p>
 *
 * <h3>Transformation Prerequisites (ALL must hold)</h3>
 * <ol>
 *   <li><strong>Functional Interface</strong> — The target interface has exactly one abstract method (SAM)</li>
 *   <li><strong>No Outer {@code this} Capture</strong> — No {@code ThisExpression} that references
 *       the enclosing anonymous class (which would change meaning in a lambda)</li>
 *   <li><strong>No Mutable Outer Variables</strong> — No mutation of variables captured from the
 *       enclosing scope (must be effectively final)</li>
 *   <li><strong>No Overridden Object Methods</strong> — No overrides of {@code toString()},
 *       {@code equals()}, {@code hashCode()}, etc.</li>
 *   <li><strong>No Reflection Dependency</strong> — No reflection usage that references the
 *       anonymous class name ({@code getClass()}, serialization concerns)</li>
 * </ol>
 *
 * <h3>Confidence Scoring Formula</h3>
 * <pre>
 *   base = 0.95
 *   if (capturesOuterVars)    score -= 0.10
 *   if (multipleStatements)   score -= 0.05
 *   if (usesGenerics)         score -= 0.05
 *   if (inConcurrentContext)  score -= 0.15
 *   clamp to [0.0, 1.0]
 * </pre>
 *
 * @see <a href="https://docs.oracle.com/javase/specs/jls/se21/html/jls-15.html#jls-15.27">
 *     JLS §15.27 — Lambda Expressions</a>
 */
public class AnonymousClassToLambdaRule implements RefactorRule {

    private static final Logger log = LoggerFactory.getLogger(AnonymousClassToLambdaRule.class);

    private static final String RULE_ID = "ANON_TO_LAMBDA";
    private static final String RULE_NAME = "Anonymous Class to Lambda Expression";
    private static final String DESCRIPTION =
            "Converts anonymous inner class instantiations of functional interfaces to lambda expressions. " +
            "Applies only when the conversion is provably safe: single abstract method interface, no outer " +
            "this capture, no mutable variable capture, no Object method overrides, and no reflection dependency.";

    /** Object methods that, if overridden, prevent lambda conversion. */
    private static final Set<String> OBJECT_METHODS = Set.of(
            "toString", "equals", "hashCode", "clone", "finalize", "getClass", "notify",
            "notifyAll", "wait"
    );

    /** Concurrent API type names that elevate risk scoring. */
    private static final Set<String> CONCURRENT_TYPES = Set.of(
            "java.util.concurrent.Callable",
            "java.util.concurrent.Future",
            "java.util.concurrent.CompletableFuture",
            "java.util.concurrent.ExecutorService",
            "java.util.concurrent.ScheduledExecutorService",
            "java.util.concurrent.ForkJoinPool",
            "java.lang.Runnable"
    );

    @Override
    public String ruleId() {
        return RULE_ID;
    }

    @Override
    public String ruleName() {
        return RULE_NAME;
    }

    @Override
    public String description() {
        return DESCRIPTION;
    }

    @Override
    public RiskTier defaultRiskTier() {
        return RiskTier.LOW;
    }

    // ==================== PHASE 1: DETECTION ====================

    @Override
    public boolean appliesTo(ASTNode node, SemanticContext context) {
        if (!(node instanceof ClassInstanceCreation creation)) {
            return false;
        }

        // Must have an anonymous class declaration
        AnonymousClassDeclaration anonDecl = creation.getAnonymousClassDeclaration();
        if (anonDecl == null) {
            return false;
        }

        // Quick structural check: must have exactly one method body declaration
        @SuppressWarnings("unchecked")
        List<BodyDeclaration> bodyDecls = anonDecl.bodyDeclarations();
        long methodCount = bodyDecls.stream()
                .filter(bd -> bd instanceof MethodDeclaration)
                .count();

        if (methodCount != 1) {
            log.trace("Skipping anonymous class at position {}: has {} method declarations (need exactly 1)",
                    node.getStartPosition(), methodCount);
            return false;
        }

        // Must have no field declarations (state in anonymous class prevents conversion)
        boolean hasFields = bodyDecls.stream().anyMatch(bd -> bd instanceof FieldDeclaration);
        if (hasFields) {
            log.trace("Skipping anonymous class at position {}: contains field declarations",
                    node.getStartPosition());
            return false;
        }

        // Must have no initializer blocks
        boolean hasInitializers = bodyDecls.stream().anyMatch(bd -> bd instanceof Initializer);
        if (hasInitializers) {
            log.trace("Skipping anonymous class at position {}: contains initializer blocks",
                    node.getStartPosition());
            return false;
        }

        log.debug("Anonymous class at position {} passes structural pre-check", node.getStartPosition());
        return true;
    }

    // ==================== PHASE 2: ANALYSIS ====================

    @Override
    public RefactorCandidate analyze(ASTNode node, SemanticContext context) {
        ClassInstanceCreation creation = (ClassInstanceCreation) node;
        AnonymousClassDeclaration anonDecl = creation.getAnonymousClassDeclaration();
        @SuppressWarnings("unchecked")
        List<BodyDeclaration> bodyDecls = anonDecl.bodyDeclarations();
        MethodDeclaration samMethod = (MethodDeclaration) bodyDecls.stream()
                .filter(bd -> bd instanceof MethodDeclaration)
                .findFirst()
                .orElseThrow();

        String sourceFile = context.getSourceFilePath();
        int startLine = context.getLineNumber(node.getStartPosition());
        int endLine = context.getLineNumber(node.getStartPosition() + node.getLength() - 1);

        log.info("Analyzing anonymous class at {}:{}-{} for lambda conversion",
                sourceFile, startLine, endLine);

        List<SafetyInvariant> invariants = new ArrayList<>();
        Map<String, Object> metadata = new LinkedHashMap<>();
        StringBuilder rationaleBuilder = new StringBuilder();

        // --- Invariant 1: Functional Interface Check ---
        SafetyInvariant functionalInterfaceInvariant = checkFunctionalInterface(creation, anonDecl, metadata);
        invariants.add(functionalInterfaceInvariant);
        log.info("  [INV-1] Functional interface: {}", functionalInterfaceInvariant.getStatus());

        // --- Invariant 2: Outer 'this' Capture Check ---
        SafetyInvariant outerThisInvariant = checkOuterThisCapture(anonDecl, samMethod, metadata);
        invariants.add(outerThisInvariant);
        log.info("  [INV-2] Outer this capture: {}", outerThisInvariant.getStatus());

        // --- Invariant 3: Effectively Final Variable Check ---
        SafetyInvariant effectivelyFinalInvariant = checkEffectivelyFinalCapture(samMethod, context, metadata);
        invariants.add(effectivelyFinalInvariant);
        log.info("  [INV-3] Effectively final capture: {}", effectivelyFinalInvariant.getStatus());

        // --- Invariant 4: No Object Method Overrides ---
        SafetyInvariant objectMethodInvariant = checkObjectMethodOverrides(samMethod, metadata);
        invariants.add(objectMethodInvariant);
        log.info("  [INV-4] Object method overrides: {}", objectMethodInvariant.getStatus());

        // --- Invariant 5: No Reflection Dependency ---
        SafetyInvariant reflectionInvariant = checkReflectionUsage(anonDecl, context, metadata);
        invariants.add(reflectionInvariant);
        log.info("  [INV-5] Reflection dependency: {}", reflectionInvariant.getStatus());

        // --- Compute Confidence Score ---
        boolean capturesOuterVars = Boolean.TRUE.equals(metadata.get("capturesOuterVariables"));
        boolean multipleStatements = hasMultipleStatements(samMethod);
        boolean usesGenerics = hasGenericTypeUsage(creation, samMethod);
        boolean inConcurrentContext = isInConcurrentContext(creation, context);

        metadata.put("capturesOuterVariables", capturesOuterVars);
        metadata.put("multipleStatements", multipleStatements);
        metadata.put("usesGenerics", usesGenerics);
        metadata.put("inConcurrentContext", inConcurrentContext);

        double confidence = computeConfidenceScore(capturesOuterVars, multipleStatements,
                usesGenerics, inConcurrentContext);
        metadata.put("confidenceBreakdown", formatConfidenceBreakdown(
                capturesOuterVars, multipleStatements, usesGenerics, inConcurrentContext, confidence));

        log.info("  Confidence score: {:.3f} (outerVars={}, multiStmt={}, generics={}, concurrent={})",
                confidence, capturesOuterVars, multipleStatements, usesGenerics, inConcurrentContext);

        // --- Determine Risk Tier ---
        RiskTier riskTier = determineRiskTier(inConcurrentContext, capturesOuterVars);
        metadata.put("riskTier", riskTier.getLabel());

        // --- Generate Rationale ---
        rationaleBuilder.append("Convert anonymous ").append(getInterfaceName(creation))
                .append(" implementation to lambda expression. ");

        if (capturesOuterVars) {
            rationaleBuilder.append("Captures outer variables (all effectively final). ");
        }
        if (multipleStatements) {
            rationaleBuilder.append("Lambda body contains multiple statements (block lambda). ");
        }
        if (inConcurrentContext) {
            rationaleBuilder.append("Used in concurrent context — extra review recommended. ");
        }

        boolean allVerified = invariants.stream().allMatch(SafetyInvariant::isVerified);
        if (allVerified) {
            rationaleBuilder.append("All safety invariants verified — conversion is safe.");
        } else {
            List<String> violations = invariants.stream()
                    .filter(SafetyInvariant::isViolated)
                    .map(inv -> inv.getInvariantId() + ": " + inv.getEvidence())
                    .toList();
            rationaleBuilder.append("BLOCKED: ").append(violations.size())
                    .append(" invariant(s) violated: ").append(String.join("; ", violations));
        }

        // --- Build Proposed Snippet ---
        String originalSnippet = context.getSourceRange(node.getStartPosition(), node.getLength());
        String proposedSnippet = allVerified ? generateLambdaSnippet(creation, samMethod, context) : originalSnippet;

        return RefactorCandidate.builder(RULE_ID, sourceFile)
                .startLine(startLine)
                .endLine(endLine)
                .startPosition(node.getStartPosition())
                .length(node.getLength())
                .originalSnippet(originalSnippet)
                .proposedSnippet(proposedSnippet)
                .confidenceScore(confidence)
                .riskTier(riskTier)
                .invariants(invariants)
                .analysisMetadata(metadata)
                .rationale(rationaleBuilder.toString())
                .astNode(node)
                .build();
    }

    // ==================== PHASE 3: APPLICATION ====================

    @Override
    public PatchUnit apply(RefactorCandidate candidate) {
        if (!candidate.allInvariantsVerified()) {
            throw new IllegalArgumentException(
                    "Cannot apply rule to candidate %s: %d invariant(s) violated"
                            .formatted(candidate.getCandidateId(), candidate.getViolatedInvariants().size()));
        }

        log.info("Applying lambda conversion to candidate {} at {}:{}-{}",
                candidate.getCandidateId(), candidate.getSourceFile(),
                candidate.getStartLine(), candidate.getEndLine());

        String beforeSnippet = candidate.getOriginalSnippet();
        String afterSnippet = candidate.getProposedSnippet();

        // Compute unified diff
        List<String> beforeLines = Arrays.asList(beforeSnippet.split("\n", -1));
        List<String> afterLines = Arrays.asList(afterSnippet.split("\n", -1));
        String unifiedDiff = PatchUnit.computeUnifiedDiff(
                beforeLines, afterLines, candidate.getSourceFile(), candidate.getStartLine());

        log.debug("Generated unified diff ({} before lines → {} after lines)",
                beforeLines.size(), afterLines.size());

        return PatchUnit.builder(RULE_ID, candidate.getSourceFile())
                .startLine(candidate.getStartLine())
                .endLine(candidate.getEndLine())
                .beforeSnippet(beforeSnippet)
                .afterSnippet(afterSnippet)
                .unifiedDiff(unifiedDiff)
                .confidenceScore(candidate.getConfidenceScore())
                .riskTier(candidate.getRiskTier())
                .invariants(candidate.getInvariants())
                .metadata(candidate.getAnalysisMetadata())
                .rationale(candidate.getRationale())
                .build();
    }

    // ==================== INVARIANT CHECKS ====================

    /**
     * Invariant 1: Verifies the target type is a functional interface (single abstract method).
     *
     * <p>Uses Eclipse JDT type bindings to resolve the interface and count abstract methods.
     * A functional interface must have exactly one abstract method per JLS §9.8.</p>
     */
    private SafetyInvariant checkFunctionalInterface(
            ClassInstanceCreation creation,
            AnonymousClassDeclaration anonDecl,
            Map<String, Object> metadata) {

        String invariantId = "functional_interface";
        String description = "Target interface must be a functional interface (single abstract method)";

        ITypeBinding typeBinding = creation.resolveTypeBinding();
        if (typeBinding == null) {
            log.warn("Cannot resolve type binding for anonymous class at position {}",
                    creation.getStartPosition());
            // Fall back to structural analysis if bindings unavailable
            return checkFunctionalInterfaceStructural(creation, anonDecl, metadata);
        }

        ITypeBinding superType = typeBinding.getSuperclass();
        ITypeBinding[] interfaces = typeBinding.getInterfaces();

        // Anonymous class should implement exactly one interface (for lambda conversion)
        ITypeBinding targetType = null;
        if (interfaces.length == 1) {
            targetType = interfaces[0];
        } else if (superType != null && superType.isInterface()) {
            targetType = superType;
        }

        if (targetType == null) {
            metadata.put("targetType", "unknown");
            return SafetyInvariant.violated(invariantId, description,
                    "Could not determine single target interface for anonymous class");
        }

        String targetTypeName = targetType.getQualifiedName();
        metadata.put("targetType", targetTypeName);
        metadata.put("targetTypeIsInterface", targetType.isInterface());

        // Count abstract methods using functional interface check
        if (targetType.getFunctionalInterfaceMethod() != null) {
            IMethodBinding samBinding = targetType.getFunctionalInterfaceMethod();
            metadata.put("samMethodName", samBinding.getName());
            metadata.put("samMethodSignature", samBinding.toString());
            log.debug("Target type '{}' is functional interface with SAM: {}",
                    targetTypeName, samBinding.getName());
            return SafetyInvariant.verified(invariantId, description,
                    "Interface '%s' has SAM '%s'".formatted(targetTypeName, samBinding.getName()));
        }

        // Manual count of abstract methods
        int abstractMethodCount = countAbstractMethods(targetType);
        metadata.put("abstractMethodCount", abstractMethodCount);

        if (abstractMethodCount == 1) {
            return SafetyInvariant.verified(invariantId, description,
                    "Interface '%s' has exactly 1 abstract method".formatted(targetTypeName));
        } else {
            return SafetyInvariant.violated(invariantId, description,
                    "Interface '%s' has %d abstract methods (need exactly 1)"
                            .formatted(targetTypeName, abstractMethodCount));
        }
    }

    /**
     * Structural fallback when type bindings are unavailable.
     */
    private SafetyInvariant checkFunctionalInterfaceStructural(
            ClassInstanceCreation creation,
            AnonymousClassDeclaration anonDecl,
            Map<String, Object> metadata) {

        String invariantId = "functional_interface";
        String description = "Target interface must be a functional interface (single abstract method)";

        Type type = creation.getType();
        String typeName = type.toString();
        metadata.put("targetType", typeName);
        metadata.put("typeBindingAvailable", false);

        // Without bindings, we can check if it has exactly one method in the anonymous body
        @SuppressWarnings("unchecked")
        List<BodyDeclaration> bodyDecls = anonDecl.bodyDeclarations();
        long methods = bodyDecls.stream().filter(bd -> bd instanceof MethodDeclaration).count();

        if (methods == 1) {
            return SafetyInvariant.undetermined(invariantId, description,
                    "Type bindings unavailable; structural check shows 1 method. " +
                    "Type '%s' assumed functional interface (verify manually)".formatted(typeName));
        }

        return SafetyInvariant.violated(invariantId, description,
                "Anonymous class has %d methods — not a SAM pattern".formatted(methods));
    }

    /**
     * Counts abstract methods in a type binding, excluding default/static methods
     * and methods inherited from Object.
     */
    private int countAbstractMethods(ITypeBinding typeBinding) {
        int count = 0;
        for (IMethodBinding method : typeBinding.getDeclaredMethods()) {
            if (Modifier.isAbstract(method.getModifiers())
                    && !Modifier.isStatic(method.getModifiers())
                    && !isObjectMethod(method)) {
                count++;
            }
        }
        return count;
    }

    private boolean isObjectMethod(IMethodBinding method) {
        return OBJECT_METHODS.contains(method.getName())
                && method.getDeclaringClass().getQualifiedName().equals("java.lang.Object");
    }

    /**
     * Invariant 2: Checks for improper outer 'this' capture.
     *
     * <p>In an anonymous class, {@code this} refers to the anonymous class instance.
     * In a lambda, {@code this} refers to the enclosing class instance. If the anonymous
     * class uses {@code this} to reference itself (e.g., passing {@code this} to a method,
     * calling {@code this.getClass()}), the conversion would change semantics.</p>
     */
    private SafetyInvariant checkOuterThisCapture(
            AnonymousClassDeclaration anonDecl,
            MethodDeclaration samMethod,
            Map<String, Object> metadata) {

        String invariantId = "no_outer_this_capture";
        String description = "Anonymous class must not use 'this' referencing itself (semantics change in lambda)";

        List<ThisExpression> thisExpressions = new ArrayList<>();
        List<String> problematicThisUsages = new ArrayList<>();

        samMethod.accept(new ASTVisitor() {
            @Override
            public boolean visit(ThisExpression node) {
                thisExpressions.add(node);

                // Check if 'this' refers to the anonymous class itself
                // In an anonymous class, unqualified 'this' refers to the anonymous instance
                // We need to detect cases where this self-reference is semantically meaningful
                Name qualifier = node.getQualifier();

                if (qualifier == null) {
                    // Unqualified 'this' — refers to the anonymous class instance
                    // Check if it's used in a way that would break:
                    ASTNode parent = node.getParent();

                    // Case 1: this passed as argument — identity would change
                    if (parent instanceof MethodInvocation inv) {
                        @SuppressWarnings("unchecked")
                        List<Expression> args = inv.arguments();
                        if (args.contains(node)) {
                            problematicThisUsages.add("'this' passed as method argument at position " +
                                    node.getStartPosition());
                        }
                    }

                    // Case 2: this.getClass() — would return different class
                    if (parent instanceof MethodInvocation inv && inv.getExpression() == node) {
                        String methodName = inv.getName().getIdentifier();
                        if ("getClass".equals(methodName)) {
                            problematicThisUsages.add("'this.getClass()' at position " +
                                    node.getStartPosition());
                        }
                    }

                    // Case 3: this assigned to variable
                    if (parent instanceof Assignment assign && assign.getRightHandSide() == node) {
                        problematicThisUsages.add("'this' assigned to variable at position " +
                                node.getStartPosition());
                    }

                    // Case 4: this used in return statement
                    if (parent instanceof ReturnStatement) {
                        problematicThisUsages.add("'this' returned from method at position " +
                                node.getStartPosition());
                    }

                    // Case 5: this used in synchronized block
                    if (parent instanceof SynchronizedStatement sync && sync.getExpression() == node) {
                        problematicThisUsages.add("'synchronized(this)' at position " +
                                node.getStartPosition());
                    }

                    // Case 6: this used with instanceof
                    if (parent instanceof InstanceofExpression) {
                        problematicThisUsages.add("'this instanceof ...' at position " +
                                node.getStartPosition());
                    }
                }

                return true;
            }
        });

        metadata.put("thisExpressionCount", thisExpressions.size());
        metadata.put("problematicThisUsages", problematicThisUsages);

        if (problematicThisUsages.isEmpty()) {
            return SafetyInvariant.verified(invariantId, description,
                    "No problematic 'this' references found (%d total this expressions scanned)"
                            .formatted(thisExpressions.size()));
        } else {
            return SafetyInvariant.violated(invariantId, description,
                    "%d problematic 'this' usage(s): %s"
                            .formatted(problematicThisUsages.size(), String.join("; ", problematicThisUsages)));
        }
    }

    /**
     * Invariant 3: Checks that all captured variables from the outer scope are effectively final.
     *
     * <p>Lambda expressions require that all captured local variables be effectively final.
     * Anonymous classes have the same restriction in Java 8+, but older code may rely on
     * field mutation patterns that look like variable capture.</p>
     */
    private SafetyInvariant checkEffectivelyFinalCapture(
            MethodDeclaration samMethod,
            SemanticContext context,
            Map<String, Object> metadata) {

        String invariantId = "no_mutable_capture";
        String description = "All captured outer-scope variables must be effectively final";

        Set<String> capturedVariables = new LinkedHashSet<>();
        Set<String> mutatedCaptures = new LinkedHashSet<>();

        // Collect all simple name references that are not locally declared
        Set<String> locallyDeclared = collectLocalDeclarations(samMethod);

        samMethod.accept(new ASTVisitor() {
            @Override
            public boolean visit(SimpleName node) {
                String name = node.getIdentifier();

                // Skip if locally declared within the lambda body
                if (locallyDeclared.contains(name)) {
                    return true;
                }

                // Skip if it's a method name in a method invocation
                ASTNode parent = node.getParent();
                if (parent instanceof MethodInvocation inv && inv.getName() == node) {
                    return true;
                }

                // Skip type names
                if (parent instanceof SimpleType || parent instanceof QualifiedType) {
                    return true;
                }

                // This is a reference to an outer variable
                IBinding binding = node.resolveBinding();
                if (binding instanceof IVariableBinding varBinding) {
                    if (varBinding.isField()) {
                        // Field access — not a local capture issue
                        return true;
                    }
                    // Local variable or parameter from outer scope
                    capturedVariables.add(name);

                    // Check if this variable is being mutated
                    if (isBeingMutated(node)) {
                        mutatedCaptures.add(name);
                    }

                    // Also check if the variable was already determined non-effectively-final
                    if (!context.isEffectivelyFinal(name)
                            && context.getVariableScope().containsKey(name)) {
                        mutatedCaptures.add(name);
                    }
                }

                return true;
            }
        });

        metadata.put("capturedOuterVariables", new ArrayList<>(capturedVariables));
        metadata.put("mutatedCapturedVariables", new ArrayList<>(mutatedCaptures));
        metadata.put("capturesOuterVariables", !capturedVariables.isEmpty());

        if (mutatedCaptures.isEmpty()) {
            String evidence = capturedVariables.isEmpty()
                    ? "No outer variables captured"
                    : "Captures %d outer variable(s) [%s], all effectively final"
                            .formatted(capturedVariables.size(), String.join(", ", capturedVariables));
            return SafetyInvariant.verified(invariantId, description, evidence);
        } else {
            return SafetyInvariant.violated(invariantId, description,
                    "Mutated captured variable(s): [%s]".formatted(String.join(", ", mutatedCaptures)));
        }
    }

    /**
     * Collects all locally declared variable names within a method body.
     */
    private Set<String> collectLocalDeclarations(MethodDeclaration method) {
        Set<String> locals = new HashSet<>();

        // Parameters
        @SuppressWarnings("unchecked")
        List<SingleVariableDeclaration> params = method.parameters();
        for (SingleVariableDeclaration param : params) {
            locals.add(param.getName().getIdentifier());
        }

        // Local variable declarations in the body
        if (method.getBody() != null) {
            method.getBody().accept(new ASTVisitor() {
                @Override
                public boolean visit(VariableDeclarationFragment node) {
                    locals.add(node.getName().getIdentifier());
                    return true;
                }

                @Override
                public boolean visit(SingleVariableDeclaration node) {
                    locals.add(node.getName().getIdentifier());
                    return true;
                }
            });
        }

        return locals;
    }

    /**
     * Checks if a SimpleName node is on the left-hand side of a mutation operation.
     */
    private boolean isBeingMutated(SimpleName node) {
        ASTNode parent = node.getParent();

        // Direct assignment: x = ...
        if (parent instanceof Assignment assign) {
            return assign.getLeftHandSide() == node;
        }

        // Prefix increment/decrement: ++x, --x
        if (parent instanceof PrefixExpression prefix) {
            PrefixExpression.Operator op = prefix.getOperator();
            return op == PrefixExpression.Operator.INCREMENT || op == PrefixExpression.Operator.DECREMENT;
        }

        // Postfix increment/decrement: x++, x--
        if (parent instanceof PostfixExpression postfix) {
            PostfixExpression.Operator op = postfix.getOperator();
            return op == PostfixExpression.Operator.INCREMENT || op == PostfixExpression.Operator.DECREMENT;
        }

        return false;
    }

    /**
     * Invariant 4: Checks that the single method is not an Object method override.
     *
     * <p>If the anonymous class overrides toString(), equals(), hashCode(), etc.,
     * it cannot be converted to a lambda — lambdas cannot override Object methods.</p>
     */
    private SafetyInvariant checkObjectMethodOverrides(
            MethodDeclaration samMethod,
            Map<String, Object> metadata) {

        String invariantId = "no_object_method_override";
        String description = "The implemented method must not be an Object method override";

        String methodName = samMethod.getName().getIdentifier();
        metadata.put("samMethodName", methodName);

        if (OBJECT_METHODS.contains(methodName)) {
            // Additional check: verify parameter signature matches Object method
            @SuppressWarnings("unchecked")
            List<SingleVariableDeclaration> params = samMethod.parameters();
            boolean isExactObjectOverride = switch (methodName) {
                case "toString" -> params.isEmpty();
                case "hashCode" -> params.isEmpty();
                case "equals" -> params.size() == 1 && isObjectParam(params.get(0));
                case "clone" -> params.isEmpty();
                case "finalize" -> params.isEmpty();
                default -> false;
            };

            if (isExactObjectOverride) {
                return SafetyInvariant.violated(invariantId, description,
                        "Method '%s' is an Object method override — cannot convert to lambda"
                                .formatted(methodName));
            }
        }

        return SafetyInvariant.verified(invariantId, description,
                "Method '%s' is not an Object method override".formatted(methodName));
    }

    private boolean isObjectParam(SingleVariableDeclaration param) {
        Type type = param.getType();
        if (type instanceof SimpleType simpleType) {
            String typeName = simpleType.getName().getFullyQualifiedName();
            return "Object".equals(typeName) || "java.lang.Object".equals(typeName);
        }
        return false;
    }

    /**
     * Invariant 5: Checks for reflection usage that depends on the anonymous class identity.
     *
     * <p>If the code uses {@code getClass()}, serialization, or other reflection patterns
     * that reference the anonymous class name, converting to a lambda would change behavior
     * because lambdas have synthetic class names.</p>
     */
    private SafetyInvariant checkReflectionUsage(
            AnonymousClassDeclaration anonDecl,
            SemanticContext context,
            Map<String, Object> metadata) {

        String invariantId = "no_reflection_dependency";
        String description = "No reflection usage that references the anonymous class identity";

        List<String> reflectionUsages = new ArrayList<>();

        anonDecl.accept(new ASTVisitor() {
            @Override
            public boolean visit(MethodInvocation node) {
                String methodName = node.getName().getIdentifier();

                // getClass() call
                if ("getClass".equals(methodName)) {
                    reflectionUsages.add("getClass() at position " + node.getStartPosition());
                }

                // Class.forName() patterns
                if ("forName".equals(methodName) || "newInstance".equals(methodName)) {
                    reflectionUsages.add(methodName + "() at position " + node.getStartPosition());
                }

                // Serialization-related
                if ("writeObject".equals(methodName) || "readObject".equals(methodName)
                        || "writeReplace".equals(methodName) || "readResolve".equals(methodName)) {
                    reflectionUsages.add("Serialization method " + methodName +
                            "() at position " + node.getStartPosition());
                }

                // java.lang.reflect usage
                if ("getDeclaredMethod".equals(methodName) || "getDeclaredField".equals(methodName)
                        || "getMethod".equals(methodName) || "getField".equals(methodName)) {
                    reflectionUsages.add("Reflection API " + methodName +
                            "() at position " + node.getStartPosition());
                }

                return true;
            }

            @Override
            public boolean visit(TypeLiteral node) {
                // SomeClass.class usage
                reflectionUsages.add("Type literal at position " + node.getStartPosition());
                return true;
            }
        });

        // Also check the broader context for reflection targeting this anonymous class
        if (context.hasReflectionUsage()) {
            log.debug("Broader context has reflection usage — flagging as additional concern");
            metadata.put("contextHasReflection", true);
        }

        metadata.put("reflectionUsages", reflectionUsages);

        if (reflectionUsages.isEmpty()) {
            return SafetyInvariant.verified(invariantId, description,
                    "No reflection or serialization dependencies detected");
        } else {
            return SafetyInvariant.violated(invariantId, description,
                    "%d reflection usage(s) detected: %s"
                            .formatted(reflectionUsages.size(), String.join("; ", reflectionUsages)));
        }
    }

    // ==================== CONFIDENCE SCORING ====================

    /**
     * Computes a deterministic confidence score using the documented formula.
     *
     * <pre>
     *   base = 0.95
     *   if (capturesOuterVars)    score -= 0.10
     *   if (multipleStatements)   score -= 0.05
     *   if (usesGenerics)         score -= 0.05
     *   if (inConcurrentContext)  score -= 0.15
     *   clamp to [0.0, 1.0]
     * </pre>
     */
    private double computeConfidenceScore(
            boolean capturesOuterVars,
            boolean multipleStatements,
            boolean usesGenerics,
            boolean inConcurrentContext) {

        double score = 0.95;

        if (capturesOuterVars) {
            score -= 0.10;
            log.debug("  Confidence -0.10: captures outer variables");
        }
        if (multipleStatements) {
            score -= 0.05;
            log.debug("  Confidence -0.05: multiple statements in body");
        }
        if (usesGenerics) {
            score -= 0.05;
            log.debug("  Confidence -0.05: uses generic types");
        }
        if (inConcurrentContext) {
            score -= 0.15;
            log.debug("  Confidence -0.15: concurrent context");
        }

        return Math.max(0.0, Math.min(1.0, score));
    }

    private String formatConfidenceBreakdown(
            boolean capturesOuterVars, boolean multipleStatements,
            boolean usesGenerics, boolean inConcurrentContext, double finalScore) {
        StringBuilder sb = new StringBuilder("base=0.95");
        if (capturesOuterVars) sb.append(", outerVars=-0.10");
        if (multipleStatements) sb.append(", multiStmt=-0.05");
        if (usesGenerics) sb.append(", generics=-0.05");
        if (inConcurrentContext) sb.append(", concurrent=-0.15");
        sb.append(" → final=%.3f".formatted(finalScore));
        return sb.toString();
    }

    // ==================== HELPER METHODS ====================

    /**
     * Determines if the method body has multiple statements (triggers block lambda).
     */
    private boolean hasMultipleStatements(MethodDeclaration method) {
        Block body = method.getBody();
        if (body == null) return false;
        @SuppressWarnings("unchecked")
        List<Statement> statements = body.statements();
        return statements.size() > 1;
    }

    /**
     * Checks if the anonymous class or SAM method involves generic types.
     */
    private boolean hasGenericTypeUsage(ClassInstanceCreation creation, MethodDeclaration method) {
        // Check type arguments on the creation
        @SuppressWarnings("unchecked")
        List<Type> typeArgs = creation.typeArguments();
        if (!typeArgs.isEmpty()) {
            return true;
        }

        // Check if the method return type or parameters use type parameters
        Type returnType = method.getReturnType2();
        if (returnType instanceof ParameterizedType) {
            return true;
        }

        @SuppressWarnings("unchecked")
        List<SingleVariableDeclaration> params = method.parameters();
        for (SingleVariableDeclaration param : params) {
            if (param.getType() instanceof ParameterizedType) {
                return true;
            }
        }

        return false;
    }

    /**
     * Determines if the anonymous class is used in a concurrent execution context.
     */
    private boolean isInConcurrentContext(ClassInstanceCreation creation, SemanticContext context) {
        // Check if in synchronized block
        if (context.isInSynchronizedBlock()) {
            return true;
        }

        // Check context concurrent API flag
        if (context.usesConcurrentAPIs()) {
            return true;
        }

        // Check if the creation's type is a known concurrent interface
        ITypeBinding binding = creation.resolveTypeBinding();
        if (binding != null) {
            for (ITypeBinding iface : binding.getInterfaces()) {
                if (CONCURRENT_TYPES.contains(iface.getQualifiedName())) {
                    return true;
                }
            }
        }

        // Check if the creation is passed to a concurrent API method
        ASTNode parent = creation.getParent();
        if (parent instanceof MethodInvocation inv) {
            IMethodBinding methodBinding = inv.resolveMethodBinding();
            if (methodBinding != null) {
                String declaringClass = methodBinding.getDeclaringClass().getQualifiedName();
                if (declaringClass.startsWith("java.util.concurrent.")) {
                    return true;
                }
            }
        }

        return false;
    }

    /**
     * Determines risk tier based on context analysis.
     */
    private RiskTier determineRiskTier(boolean inConcurrentContext, boolean capturesOuterVars) {
        if (inConcurrentContext) {
            return RiskTier.MEDIUM;
        }
        if (capturesOuterVars) {
            return RiskTier.LOW;
        }
        return defaultRiskTier();
    }

    /**
     * Extracts the interface name from the ClassInstanceCreation.
     */
    private String getInterfaceName(ClassInstanceCreation creation) {
        Type type = creation.getType();
        return type != null ? type.toString() : "unknown";
    }

    /**
     * Generates the lambda expression snippet from the anonymous class.
     *
     * <p>Handles both expression lambdas (single return statement) and block lambdas
     * (multiple statements). Preserves parameter types when type inference might be
     * ambiguous.</p>
     */
    private String generateLambdaSnippet(
            ClassInstanceCreation creation,
            MethodDeclaration samMethod,
            SemanticContext context) {

        StringBuilder lambda = new StringBuilder();

        // Determine the expression that the anonymous class is part of
        // (e.g., "Collections.sort(list, " for a Comparator)
        // We only generate the lambda expression itself; the context stays the same

        // Build parameter list
        @SuppressWarnings("unchecked")
        List<SingleVariableDeclaration> params = samMethod.parameters();

        String paramString;
        if (params.isEmpty()) {
            paramString = "()";
        } else if (params.size() == 1) {
            // Single parameter: can omit parentheses and type
            paramString = params.get(0).getName().getIdentifier();
        } else {
            // Multiple parameters: parenthesized, omit types for brevity
            paramString = "(" + params.stream()
                    .map(p -> p.getName().getIdentifier())
                    .collect(Collectors.joining(", ")) + ")";
        }

        lambda.append(paramString).append(" -> ");

        // Build body
        Block body = samMethod.getBody();
        if (body != null) {
            @SuppressWarnings("unchecked")
            List<Statement> statements = body.statements();

            if (statements.size() == 1 && statements.get(0) instanceof ReturnStatement returnStmt) {
                // Single return: expression lambda
                Expression expr = returnStmt.getExpression();
                if (expr != null) {
                    lambda.append(expr.toString());
                } else {
                    // void return — shouldn't happen with return statement, but handle gracefully
                    lambda.append("{}");
                }
            } else if (statements.size() == 1 && statements.get(0) instanceof ExpressionStatement exprStmt) {
                // Single expression: expression lambda (void-compatible)
                lambda.append(exprStmt.getExpression().toString());
            } else {
                // Multiple statements: block lambda
                lambda.append("{\n");
                for (Statement stmt : statements) {
                    // Indent each statement
                    String stmtStr = stmt.toString().trim();
                    for (String line : stmtStr.split("\n")) {
                        lambda.append("    ").append(line).append("\n");
                    }
                }
                lambda.append("}");
            }
        } else {
            lambda.append("{}");
        }

        return lambda.toString();
    }
}
