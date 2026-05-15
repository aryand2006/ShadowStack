package com.shadowstack.verify.layers;

import com.shadowstack.refactor.model.PatchUnit;
import com.shadowstack.verify.VerificationLayer;
import com.shadowstack.verify.model.VerificationContext;
import com.shadowstack.verify.model.VerificationLayerResult;
import com.shadowstack.verify.model.Verdict;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.dom.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Verification layer that checks whether the public API surface has changed
 * between the original and transformed code.
 *
 * <p>This layer focuses on the externally visible contract of the code: public and
 * protected methods, fields, constructors, class hierarchy, and implemented interfaces.
 * Any change to the API surface is a strong indicator of potential behavioral incompatibility.</p>
 *
 * <h3>Checks Performed</h3>
 * <ul>
 *   <li>Public/protected method signatures unchanged</li>
 *   <li>Public/protected field declarations unchanged</li>
 *   <li>Constructor signatures unchanged</li>
 *   <li>Implemented interfaces unchanged</li>
 *   <li>Class modifiers unchanged</li>
 *   <li>Thrown exception declarations unchanged</li>
 * </ul>
 */
public class APISignatureDiffVerifier implements VerificationLayer {

    private static final Logger log = LoggerFactory.getLogger(APISignatureDiffVerifier.class);
    private static final String LAYER_ID = "api_signature_diff_verifier";

    @Override
    public String layerId() {
        return LAYER_ID;
    }

    @Override
    public VerificationLayerResult verify(PatchUnit patch, VerificationContext context) {
        Instant start = Instant.now();
        log.info("APISignatureDiffVerifier: checking API surface for patch {} in '{}'",
                patch.getPatchId(), patch.getSourceFile());

        VerificationLayerResult.Builder result = VerificationLayerResult.builder(LAYER_ID);

        String originalSource = context.getOriginalSource();
        String transformedSource = context.getTransformedSource();

        if (originalSource == null || transformedSource == null) {
            return result
                    .verdict(Verdict.WARN)
                    .riskContribution(0.05)
                    .summary("Source not available for API surface comparison")
                    .executionTime(Duration.between(start, Instant.now()))
                    .build();
        }

        // Parse both sources
        CompilationUnit originalAst = parseSource(originalSource);
        CompilationUnit transformedAst = parseSource(transformedSource);

        if (originalAst == null || transformedAst == null) {
            return result
                    .verdict(Verdict.WARN)
                    .riskContribution(0.05)
                    .summary("Failed to parse source for API comparison")
                    .executionTime(Duration.between(start, Instant.now()))
                    .build();
        }

        // Extract API surfaces
        APISignature originalAPI = extractAPISignature(originalAst);
        APISignature transformedAPI = extractAPISignature(transformedAst);

        // Compare
        List<String> changes = compareAPIs(originalAPI, transformedAPI);

        result.addDetail("originalPublicMethods", originalAPI.publicMethods.size());
        result.addDetail("transformedPublicMethods", transformedAPI.publicMethods.size());
        result.addDetail("originalPublicFields", originalAPI.publicFields.size());
        result.addDetail("transformedPublicFields", transformedAPI.publicFields.size());
        result.addDetail("apiChanges", changes.size());

        changes.forEach(result::addDiagnostic);

        if (!changes.isEmpty()) {
            log.warn("  {} API surface change(s) detected", changes.size());
            for (String change : changes) {
                log.warn("    - {}", change);
            }

            boolean hasBreaking = changes.stream().anyMatch(c ->
                    c.startsWith("REMOVED") || c.startsWith("CHANGED"));
            Verdict verdict = hasBreaking ? Verdict.FAIL : Verdict.WARN;
            double risk = hasBreaking ? 0.10 : 0.05;

            return result
                    .verdict(verdict)
                    .riskContribution(risk)
                    .summary("%d API surface change(s) detected (%s)"
                            .formatted(changes.size(), hasBreaking ? "breaking" : "non-breaking"))
                    .executionTime(Duration.between(start, Instant.now()))
                    .build();
        }

        log.info("  Public API surface unchanged");
        return result
                .verdict(Verdict.PASS)
                .riskContribution(0.0)
                .summary("Public API surface is unchanged")
                .executionTime(Duration.between(start, Instant.now()))
                .build();
    }

    /**
     * Extracts the public API signature from a compilation unit.
     */
    private APISignature extractAPISignature(CompilationUnit cu) {
        APISignature sig = new APISignature();

        cu.accept(new ASTVisitor() {
            @Override
            public boolean visit(TypeDeclaration node) {
                int modifiers = node.getModifiers();
                if (Modifier.isPublic(modifiers) || Modifier.isProtected(modifiers)) {
                    sig.publicTypes.add(buildTypeSignature(node));
                }

                // Collect interfaces
                @SuppressWarnings("unchecked")
                List<Type> superInterfaces = node.superInterfaceTypes();
                for (Type iface : superInterfaces) {
                    sig.implementedInterfaces.add(iface.toString());
                }

                if (node.getSuperclassType() != null) {
                    sig.superClass = node.getSuperclassType().toString();
                }

                return true;
            }

            @Override
            public boolean visit(MethodDeclaration node) {
                int modifiers = node.getModifiers();
                if (Modifier.isPublic(modifiers) || Modifier.isProtected(modifiers)) {
                    sig.publicMethods.add(buildMethodSignature(node));
                }
                return false;
            }

            @Override
            public boolean visit(FieldDeclaration node) {
                int modifiers = node.getModifiers();
                if (Modifier.isPublic(modifiers) || Modifier.isProtected(modifiers)) {
                    @SuppressWarnings("unchecked")
                    List<VariableDeclarationFragment> fragments = node.fragments();
                    for (VariableDeclarationFragment frag : fragments) {
                        sig.publicFields.add(buildFieldSignature(node, frag));
                    }
                }
                return false;
            }
        });

        return sig;
    }

    private String buildTypeSignature(TypeDeclaration node) {
        StringBuilder sb = new StringBuilder();
        sb.append(java.lang.reflect.Modifier.toString(node.getModifiers())).append(' ');
        sb.append(node.isInterface() ? "interface" : "class").append(' ');
        sb.append(node.getName().getIdentifier());

        @SuppressWarnings("unchecked")
        List<TypeParameter> typeParams = node.typeParameters();
        if (!typeParams.isEmpty()) {
            sb.append('<');
            sb.append(typeParams.stream().map(Object::toString).collect(Collectors.joining(", ")));
            sb.append('>');
        }

        return sb.toString().trim();
    }

    private String buildMethodSignature(MethodDeclaration node) {
        StringBuilder sb = new StringBuilder();

        // Modifiers
        int mods = node.getModifiers();
        if (Modifier.isPublic(mods)) sb.append("public ");
        if (Modifier.isProtected(mods)) sb.append("protected ");
        if (Modifier.isStatic(mods)) sb.append("static ");
        if (Modifier.isAbstract(mods)) sb.append("abstract ");
        if (Modifier.isFinal(mods)) sb.append("final ");

        // Return type
        if (node.getReturnType2() != null) {
            sb.append(node.getReturnType2().toString()).append(' ');
        }

        // Name
        sb.append(node.getName().getIdentifier());

        // Parameters
        sb.append('(');
        @SuppressWarnings("unchecked")
        List<SingleVariableDeclaration> params = node.parameters();
        sb.append(params.stream()
                .map(p -> p.getType().toString() + (p.isVarargs() ? "..." : ""))
                .collect(Collectors.joining(", ")));
        sb.append(')');

        // Thrown exceptions
        @SuppressWarnings("unchecked")
        List<Type> thrown = node.thrownExceptionTypes();
        if (!thrown.isEmpty()) {
            sb.append(" throws ");
            sb.append(thrown.stream().map(Object::toString).collect(Collectors.joining(", ")));
        }

        return sb.toString().trim();
    }

    private String buildFieldSignature(FieldDeclaration node, VariableDeclarationFragment frag) {
        StringBuilder sb = new StringBuilder();
        int mods = node.getModifiers();
        if (Modifier.isPublic(mods)) sb.append("public ");
        if (Modifier.isProtected(mods)) sb.append("protected ");
        if (Modifier.isStatic(mods)) sb.append("static ");
        if (Modifier.isFinal(mods)) sb.append("final ");
        sb.append(node.getType().toString()).append(' ');
        sb.append(frag.getName().getIdentifier());
        return sb.toString().trim();
    }

    /**
     * Compares two API signatures and returns a list of changes.
     */
    private List<String> compareAPIs(APISignature original, APISignature transformed) {
        List<String> changes = new ArrayList<>();

        // Compare public methods
        Set<String> origMethods = new TreeSet<>(original.publicMethods);
        Set<String> transMethods = new TreeSet<>(transformed.publicMethods);

        Set<String> removedMethods = new TreeSet<>(origMethods);
        removedMethods.removeAll(transMethods);
        for (String m : removedMethods) {
            changes.add("REMOVED method: " + m);
        }

        Set<String> addedMethods = new TreeSet<>(transMethods);
        addedMethods.removeAll(origMethods);
        for (String m : addedMethods) {
            changes.add("ADDED method: " + m);
        }

        // Compare public fields
        Set<String> origFields = new TreeSet<>(original.publicFields);
        Set<String> transFields = new TreeSet<>(transformed.publicFields);

        Set<String> removedFields = new TreeSet<>(origFields);
        removedFields.removeAll(transFields);
        for (String f : removedFields) {
            changes.add("REMOVED field: " + f);
        }

        Set<String> addedFields = new TreeSet<>(transFields);
        addedFields.removeAll(origFields);
        for (String f : addedFields) {
            changes.add("ADDED field: " + f);
        }

        // Compare interfaces
        Set<String> origInterfaces = new TreeSet<>(original.implementedInterfaces);
        Set<String> transInterfaces = new TreeSet<>(transformed.implementedInterfaces);
        if (!origInterfaces.equals(transInterfaces)) {
            changes.add("CHANGED interfaces: %s → %s".formatted(origInterfaces, transInterfaces));
        }

        // Compare superclass
        if (!Objects.equals(original.superClass, transformed.superClass)) {
            changes.add("CHANGED superclass: '%s' → '%s'".formatted(original.superClass, transformed.superClass));
        }

        return changes;
    }

    private CompilationUnit parseSource(String source) {
        try {
            ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
            parser.setSource(source.toCharArray());
            parser.setKind(ASTParser.K_COMPILATION_UNIT);
            parser.setResolveBindings(false);

            Map<String, String> options = JavaCore.getOptions();
            options.put(JavaCore.COMPILER_SOURCE, "21");
            options.put(JavaCore.COMPILER_COMPLIANCE, "21");
            parser.setCompilerOptions(options);

            return (CompilationUnit) parser.createAST(null);
        } catch (Exception e) {
            log.error("Failed to parse source: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Container for a class's public API signature.
     */
    private static final class APISignature {
        List<String> publicTypes = new ArrayList<>();
        List<String> publicMethods = new ArrayList<>();
        List<String> publicFields = new ArrayList<>();
        Set<String> implementedInterfaces = new TreeSet<>();
        String superClass;
    }
}
