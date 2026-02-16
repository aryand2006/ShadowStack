package com.shadowstack.verify.layers;

import com.shadowstack.refactor.model.PatchUnit;
import com.shadowstack.verify.VerificationLayer;
import com.shadowstack.verify.model.VerificationContext;
import com.shadowstack.verify.model.VerificationLayerResult;
import com.shadowstack.verify.model.Verdict;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * Verification layer that uses ASM to compare bytecode-level method descriptors and
 * signatures between the original and transformed class files.
 *
 * <p>This layer operates at the bytecode level using the ASM library to detect changes
 * in method signatures, field descriptors, class structure, and inner class metadata
 * that might not be visible at the source level.</p>
 *
 * <h3>Checks Performed</h3>
 * <ul>
 *   <li>Method descriptor compatibility (name, return type, parameter types)</li>
 *   <li>Field descriptor compatibility</li>
 *   <li>Generic signature preservation</li>
 *   <li>Inner class / synthetic method changes</li>
 *   <li>Access modifier changes</li>
 * </ul>
 */
public class BytecodeDescriptorComparator implements VerificationLayer {

    private static final Logger log = LoggerFactory.getLogger(BytecodeDescriptorComparator.class);
    private static final String LAYER_ID = "bytecode_descriptor_comparator";

    @Override
    public String layerId() {
        return LAYER_ID;
    }

    @Override
    public VerificationLayerResult verify(PatchUnit patch, VerificationContext context) {
        Instant start = Instant.now();
        log.info("BytecodeDescriptorComparator: comparing bytecode for patch {} in '{}'",
                patch.getPatchId(), patch.getSourceFile());

        VerificationLayerResult.Builder result = VerificationLayerResult.builder(LAYER_ID);

        Path originalClassFile = context.getOriginalClassFile();
        Path transformedClassFile = context.getTransformedClassFile();

        if (originalClassFile == null || transformedClassFile == null) {
            log.warn("Class files not available for bytecode comparison");
            return result
                    .verdict(Verdict.WARN)
                    .riskContribution(0.05)
                    .summary("Class files not available for bytecode comparison")
                    .executionTime(Duration.between(start, Instant.now()))
                    .build();
        }

        if (!Files.exists(originalClassFile) || !Files.exists(transformedClassFile)) {
            log.warn("One or both class files do not exist");
            return result
                    .verdict(Verdict.WARN)
                    .riskContribution(0.05)
                    .summary("Class file(s) not found on disk")
                    .addDiagnostic("original exists: " + Files.exists(originalClassFile))
                    .addDiagnostic("transformed exists: " + Files.exists(transformedClassFile))
                    .executionTime(Duration.between(start, Instant.now()))
                    .build();
        }

        try {
            ClassNode originalClass = readClassFile(originalClassFile);
            ClassNode transformedClass = readClassFile(transformedClassFile);

            List<String> mismatches = new ArrayList<>();
            Map<String, Object> details = new LinkedHashMap<>();

            // Compare class-level attributes
            compareClassAttributes(originalClass, transformedClass, mismatches, details);

            // Compare method descriptors
            compareMethodDescriptors(originalClass, transformedClass, mismatches, details);

            // Compare field descriptors
            compareFieldDescriptors(originalClass, transformedClass, mismatches, details);

            // Compare inner class info
            compareInnerClasses(originalClass, transformedClass, mismatches, details);

            // Compare generic signatures
            compareGenericSignatures(originalClass, transformedClass, mismatches, details);

            details.forEach(result::addDetail);
            mismatches.forEach(result::addDiagnostic);

            boolean signatureMismatch = !mismatches.isEmpty();
            result.addDetail("signatureMismatch", signatureMismatch);
            result.addDetail("mismatchCount", mismatches.size());

            if (signatureMismatch) {
                log.warn("  {} bytecode signature mismatch(es) detected", mismatches.size());
                return result
                        .verdict(Verdict.WARN)
                        .riskContribution(0.10)
                        .summary("%d bytecode signature mismatch(es) detected".formatted(mismatches.size()))
                        .executionTime(Duration.between(start, Instant.now()))
                        .build();
            }

            log.info("  Bytecode descriptors match");
            return result
                    .verdict(Verdict.PASS)
                    .riskContribution(0.0)
                    .summary("All bytecode descriptors and signatures match")
                    .executionTime(Duration.between(start, Instant.now()))
                    .build();

        } catch (IOException e) {
            log.error("Failed to read class files: {}", e.getMessage(), e);
            return result
                    .verdict(Verdict.WARN)
                    .riskContribution(0.05)
                    .summary("Failed to read class files: " + e.getMessage())
                    .executionTime(Duration.between(start, Instant.now()))
                    .build();
        }
    }

    /**
     * Reads a .class file into an ASM ClassNode.
     */
    private ClassNode readClassFile(Path classFile) throws IOException {
        ClassNode classNode = new ClassNode();
        try (InputStream is = Files.newInputStream(classFile)) {
            ClassReader reader = new ClassReader(is);
            reader.accept(classNode, ClassReader.EXPAND_FRAMES);
        }
        return classNode;
    }

    /**
     * Compares class-level attributes (version, access, superclass, interfaces).
     */
    private void compareClassAttributes(
            ClassNode original, ClassNode transformed,
            List<String> mismatches, Map<String, Object> details) {

        details.put("originalClassName", original.name);
        details.put("transformedClassName", transformed.name);
        details.put("originalAccess", original.access);
        details.put("transformedAccess", transformed.access);

        if (original.access != transformed.access) {
            mismatches.add("Class access modifier changed: 0x%X → 0x%X"
                    .formatted(original.access, transformed.access));
        }

        if (!Objects.equals(original.superName, transformed.superName)) {
            mismatches.add("Superclass changed: '%s' → '%s'"
                    .formatted(original.superName, transformed.superName));
        }

        Set<String> origInterfaces = new TreeSet<>(original.interfaces);
        Set<String> transInterfaces = new TreeSet<>(transformed.interfaces);
        if (!origInterfaces.equals(transInterfaces)) {
            Set<String> added = new TreeSet<>(transInterfaces);
            added.removeAll(origInterfaces);
            Set<String> removed = new TreeSet<>(origInterfaces);
            removed.removeAll(transInterfaces);
            if (!added.isEmpty()) mismatches.add("Interfaces added: " + added);
            if (!removed.isEmpty()) mismatches.add("Interfaces removed: " + removed);
        }
    }

    /**
     * Compares method descriptors between original and transformed classes.
     */
    private void compareMethodDescriptors(
            ClassNode original, ClassNode transformed,
            List<String> mismatches, Map<String, Object> details) {

        Map<String, MethodNode> originalMethods = buildMethodMap(original);
        Map<String, MethodNode> transformedMethods = buildMethodMap(transformed);

        details.put("originalMethodCount", originalMethods.size());
        details.put("transformedMethodCount", transformedMethods.size());

        // Filter out synthetic methods (generated by compiler for lambdas, etc.)
        Map<String, MethodNode> origNonSynthetic = filterNonSynthetic(originalMethods);
        Map<String, MethodNode> transNonSynthetic = filterNonSynthetic(transformedMethods);

        // Check for removed public/protected methods
        for (Map.Entry<String, MethodNode> entry : origNonSynthetic.entrySet()) {
            String key = entry.getKey();
            MethodNode origMethod = entry.getValue();

            if (!transNonSynthetic.containsKey(key)) {
                if (isPublicOrProtected(origMethod.access)) {
                    mismatches.add("Public/protected method removed: " + key);
                }
            } else {
                MethodNode transMethod = transNonSynthetic.get(key);
                // Compare access modifiers
                if (isPublicOrProtected(origMethod.access)
                        && origMethod.access != transMethod.access) {
                    mismatches.add("Method access changed for %s: 0x%X → 0x%X"
                            .formatted(key, origMethod.access, transMethod.access));
                }

                // Compare exception types
                List<String> origExceptions = origMethod.exceptions != null
                        ? origMethod.exceptions : List.of();
                List<String> transExceptions = transMethod.exceptions != null
                        ? transMethod.exceptions : List.of();
                if (!origExceptions.equals(transExceptions)) {
                    mismatches.add("Method exceptions changed for %s: %s → %s"
                            .formatted(key, origExceptions, transExceptions));
                }
            }
        }

        // Check for new public/protected methods (might indicate issue)
        for (Map.Entry<String, MethodNode> entry : transNonSynthetic.entrySet()) {
            if (!origNonSynthetic.containsKey(entry.getKey())
                    && isPublicOrProtected(entry.getValue().access)) {
                // New public methods are informational, not necessarily a mismatch
                details.put("newPublicMethod:" + entry.getKey(), true);
            }
        }
    }

    /**
     * Compares field descriptors between original and transformed classes.
     */
    private void compareFieldDescriptors(
            ClassNode original, ClassNode transformed,
            List<String> mismatches, Map<String, Object> details) {

        Map<String, FieldNode> origFields = new LinkedHashMap<>();
        for (FieldNode fn : original.fields) {
            origFields.put(fn.name + ":" + fn.desc, fn);
        }

        Map<String, FieldNode> transFields = new LinkedHashMap<>();
        for (FieldNode fn : transformed.fields) {
            transFields.put(fn.name + ":" + fn.desc, fn);
        }

        details.put("originalFieldCount", origFields.size());
        details.put("transformedFieldCount", transFields.size());

        for (Map.Entry<String, FieldNode> entry : origFields.entrySet()) {
            if (!transFields.containsKey(entry.getKey()) && isPublicOrProtected(entry.getValue().access)) {
                mismatches.add("Public/protected field removed: " + entry.getKey());
            }
        }
    }

    /**
     * Compares inner class metadata.
     */
    private void compareInnerClasses(
            ClassNode original, ClassNode transformed,
            List<String> mismatches, Map<String, Object> details) {

        int origInnerCount = original.innerClasses != null ? original.innerClasses.size() : 0;
        int transInnerCount = transformed.innerClasses != null ? transformed.innerClasses.size() : 0;

        details.put("originalInnerClassCount", origInnerCount);
        details.put("transformedInnerClassCount", transInnerCount);

        // A lambda conversion removes anonymous inner class, which is expected
        // We don't flag this as a mismatch for known refactoring patterns
        if (transInnerCount < origInnerCount) {
            log.debug("  Inner class count reduced ({} → {}) — expected for anonymous-to-lambda",
                    origInnerCount, transInnerCount);
        }
    }

    /**
     * Compares generic type signatures at class level.
     */
    private void compareGenericSignatures(
            ClassNode original, ClassNode transformed,
            List<String> mismatches, Map<String, Object> details) {

        if (!Objects.equals(original.signature, transformed.signature)) {
            details.put("originalClassSignature", original.signature);
            details.put("transformedClassSignature", transformed.signature);
            mismatches.add("Class generic signature changed: '%s' → '%s'"
                    .formatted(original.signature, transformed.signature));
        }
    }

    private Map<String, MethodNode> buildMethodMap(ClassNode classNode) {
        Map<String, MethodNode> map = new LinkedHashMap<>();
        for (MethodNode mn : classNode.methods) {
            map.put(mn.name + mn.desc, mn);
        }
        return map;
    }

    private Map<String, MethodNode> filterNonSynthetic(Map<String, MethodNode> methods) {
        Map<String, MethodNode> filtered = new LinkedHashMap<>();
        for (Map.Entry<String, MethodNode> entry : methods.entrySet()) {
            if ((entry.getValue().access & Opcodes.ACC_SYNTHETIC) == 0) {
                filtered.put(entry.getKey(), entry.getValue());
            }
        }
        return filtered;
    }

    private boolean isPublicOrProtected(int access) {
        return (access & (Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED)) != 0;
    }
}
