package com.shadowstack.verify;

import com.shadowstack.refactor.model.PatchUnit;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.dom.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Generates characterization tests when no existing test suite covers the code under transformation.
 *
 * <p>Characterization tests (also called "golden master tests" or "approval tests") capture
 * the current behavior of code as a test baseline. They serve as regression guards during
 * refactoring by asserting that the output of the code does not change.</p>
 *
 * <h3>Generation Strategy</h3>
 * <ol>
 *   <li>Parse the source file and identify public methods in the affected class</li>
 *   <li>For each public method, generate a JUnit 5 test that:</li>
 *   <ul>
 *     <li>Creates an instance of the class (if not static)</li>
 *     <li>Calls the method with representative inputs</li>
 *     <li>Captures the return value / side effects as a golden master</li>
 *     <li>Asserts equality against the captured snapshot</li>
 *   </ul>
 *   <li>Generate a complete JUnit 5 test class with proper imports and annotations</li>
 *   <li>Include setup/teardown for golden master snapshot loading</li>
 * </ol>
 *
 * <p>The generated tests are meant to be reviewed and refined by developers before
 * being committed to the test suite. They provide a safety net, not a specification.</p>
 */
public class CharacterizationTestGenerator {

    private static final Logger log = LoggerFactory.getLogger(CharacterizationTestGenerator.class);

    private static final DateTimeFormatter TIMESTAMP_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    /**
     * Generates a JUnit 5 characterization test class for the code affected by a patch.
     *
     * @param patch      the patch unit describing the transformation
     * @param sourceCode the original source code of the file being transformed
     * @return the generated test class source code as a string
     */
    public String generateTestClass(PatchUnit patch, String sourceCode) {
        Objects.requireNonNull(patch, "patch must not be null");
        Objects.requireNonNull(sourceCode, "sourceCode must not be null");

        log.info("Generating characterization test for patch {} in '{}'",
                patch.getPatchId(), patch.getSourceFile());

        // Parse the source
        CompilationUnit cu = parseSource(sourceCode);
        if (cu == null) {
            log.error("Failed to parse source code for test generation");
            return generateFallbackTest(patch);
        }

        // Extract class and method information
        ClassInfo classInfo = extractClassInfo(cu);
        if (classInfo == null) {
            log.warn("No public class found in source — generating minimal test");
            return generateFallbackTest(patch);
        }

        log.info("  Found class '{}' with {} public methods",
                classInfo.className, classInfo.publicMethods.size());

        return buildTestClass(patch, classInfo);
    }

    /**
     * Generates a test harness that captures current behavior as golden master snapshots.
     *
     * @param patch      the patch unit
     * @param sourceCode the original source code
     * @return the golden master capture harness source code
     */
    public String generateGoldenMasterHarness(PatchUnit patch, String sourceCode) {
        Objects.requireNonNull(patch, "patch must not be null");
        Objects.requireNonNull(sourceCode, "sourceCode must not be null");

        log.info("Generating golden master harness for patch {} in '{}'",
                patch.getPatchId(), patch.getSourceFile());

        CompilationUnit cu = parseSource(sourceCode);
        if (cu == null) {
            return generateFallbackHarness(patch);
        }

        ClassInfo classInfo = extractClassInfo(cu);
        if (classInfo == null) {
            return generateFallbackHarness(patch);
        }

        return buildGoldenMasterHarness(patch, classInfo);
    }

    /**
     * Builds the complete JUnit 5 test class.
     */
    private String buildTestClass(PatchUnit patch, ClassInfo classInfo) {
        StringBuilder sb = new StringBuilder();
        String testClassName = classInfo.className + "CharacterizationTest";
        String timestamp = TIMESTAMP_FORMAT.format(Instant.now());

        // Package declaration
        if (classInfo.packageName != null && !classInfo.packageName.isEmpty()) {
            sb.append("package ").append(classInfo.packageName).append(";\n\n");
        }

        // Imports
        sb.append("""
                import org.junit.jupiter.api.BeforeEach;
                import org.junit.jupiter.api.DisplayName;
                import org.junit.jupiter.api.Test;
                import org.junit.jupiter.api.io.TempDir;
                import static org.junit.jupiter.api.Assertions.*;
                
                import java.io.*;
                import java.nio.file.*;
                import java.util.*;
                
                """);

        // Class Javadoc
        sb.append("""
                /**
                 * Characterization tests for {@link %s}.
                 *
                 * <p>Auto-generated by ShadowStack CharacterizationTestGenerator on %s.
                 * These tests capture the current behavior of the code as a regression baseline
                 * for the refactoring applied by patch %s (rule: %s).</p>
                 *
                 * <p><strong>IMPORTANT:</strong> Review and refine these tests before committing.
                 * They capture behavior, not intent — some assertions may be overly specific.</p>
                 */
                """.formatted(classInfo.className, timestamp, patch.getPatchId(), patch.getRuleId()));

        sb.append("public class ").append(testClassName).append(" {\n\n");

        // Instance variable
        if (!classInfo.isAbstract && !classInfo.isInterface) {
            sb.append("    private %s instance;\n\n".formatted(classInfo.className));

            // @BeforeEach setup
            sb.append("""
                        @BeforeEach
                        void setUp() {
                    """);

            if (classInfo.hasDefaultConstructor) {
                sb.append("        instance = new %s();\n".formatted(classInfo.className));
            } else {
                sb.append("        // TODO: Provide constructor arguments for %s\n".formatted(classInfo.className));
                sb.append("        // instance = new %s(/* args */);\n".formatted(classInfo.className));
            }

            sb.append("    }\n\n");
        }

        // Generate test method for each public method
        for (MethodInfo method : classInfo.publicMethods) {
            sb.append(generateTestMethod(classInfo, method, patch));
        }

        // Golden master snapshot helper
        sb.append(generateSnapshotHelpers());

        sb.append("}\n");

        String result = sb.toString();
        log.info("  Generated {} lines of test code with {} test methods",
                result.lines().count(), classInfo.publicMethods.size());

        return result;
    }

    /**
     * Generates a test method for a single public method.
     */
    private String generateTestMethod(ClassInfo classInfo, MethodInfo method, PatchUnit patch) {
        StringBuilder sb = new StringBuilder();

        String displayName = "Characterization: %s.%s".formatted(classInfo.className, method.name);
        sb.append("    @Test\n");
        sb.append("    @DisplayName(\"%s\")\n".formatted(displayName));
        sb.append("    void test_%s_characterization() {\n".formatted(sanitizeMethodName(method.name)));

        // Build parameter placeholders
        if (method.isStatic) {
            sb.append("        // Static method — no instance needed\n");
        } else if (classInfo.isAbstract || classInfo.isInterface) {
            sb.append("        // Abstract/interface — TODO: provide concrete implementation\n");
            sb.append("        // %s instance = new Concrete%s();\n".formatted(
                    classInfo.className, classInfo.className));
        }

        // Parameter setup
        for (int i = 0; i < method.parameterTypes.size(); i++) {
            String paramType = method.parameterTypes.get(i);
            String paramName = method.parameterNames.size() > i
                    ? method.parameterNames.get(i) : "param" + i;
            String defaultValue = getDefaultValue(paramType);

            sb.append("        %s %s = %s; // TODO: provide representative test value\n"
                    .formatted(paramType, paramName, defaultValue));
        }

        sb.append("\n");

        // Method invocation
        String target = method.isStatic ? classInfo.className : "instance";
        String params = method.parameterNames.isEmpty()
                ? method.parameterTypes.isEmpty() ? "" :
                    java.util.stream.IntStream.range(0, method.parameterTypes.size())
                            .mapToObj(i -> method.parameterNames.size() > i ? method.parameterNames.get(i) : "param" + i)
                            .collect(Collectors.joining(", "))
                : String.join(", ", method.parameterNames);

        if ("void".equals(method.returnType)) {
            sb.append("        // Void method — capture side effects\n");
            if (method.throwsExceptions) {
                sb.append("        assertDoesNotThrow(() -> %s.%s(%s));\n"
                        .formatted(target, method.name, params));
            } else {
                sb.append("        %s.%s(%s);\n".formatted(target, method.name, params));
                sb.append("        // TODO: Assert expected side effects\n");
            }
        } else {
            sb.append("        %s result = %s.%s(%s);\n"
                    .formatted(method.returnType, target, method.name, params));
            sb.append("\n");
            sb.append("        // Golden master assertion — replace with actual expected value\n");
            sb.append("        // after running against the original code\n");

            if (isPrimitiveOrWrapper(method.returnType)) {
                sb.append("        assertNotNull(result); // TODO: assertEquals(expectedValue, result);\n");
            } else if ("String".equals(method.returnType)) {
                sb.append("        assertNotNull(result);\n");
                sb.append("        // saveSnapshot(\"%s_%s\", result);\n"
                        .formatted(classInfo.className, method.name));
            } else {
                sb.append("        assertNotNull(result);\n");
                sb.append("        // String snapshot = result.toString();\n");
                sb.append("        // saveSnapshot(\"%s_%s\", snapshot);\n"
                        .formatted(classInfo.className, method.name));
            }
        }

        sb.append("    }\n\n");
        return sb.toString();
    }

    /**
     * Generates golden master snapshot helper methods.
     */
    private String generateSnapshotHelpers() {
        return """
                    // --- Golden Master Snapshot Helpers ---
                
                    private static final Path SNAPSHOT_DIR = Path.of("src/test/resources/golden-masters");
                
                    /**
                     * Saves a golden master snapshot to disk.
                     */
                    private void saveSnapshot(String name, String content) throws IOException {
                        Files.createDirectories(SNAPSHOT_DIR);
                        Path snapshotFile = SNAPSHOT_DIR.resolve(name + ".snapshot");
                        Files.writeString(snapshotFile, content);
                    }
                
                    /**
                     * Loads a golden master snapshot from disk.
                     */
                    private String loadSnapshot(String name) throws IOException {
                        Path snapshotFile = SNAPSHOT_DIR.resolve(name + ".snapshot");
                        if (Files.exists(snapshotFile)) {
                            return Files.readString(snapshotFile);
                        }
                        return null;
                    }
                
                    /**
                     * Asserts that the actual output matches the golden master snapshot.
                     * If no snapshot exists, saves the current output as the new golden master.
                     */
                    private void assertMatchesSnapshot(String name, String actual) throws IOException {
                        String expected = loadSnapshot(name);
                        if (expected == null) {
                            saveSnapshot(name, actual);
                            System.out.println("[GoldenMaster] New snapshot saved: " + name);
                        } else {
                            assertEquals(expected, actual,
                                    "Golden master mismatch for snapshot '" + name + "'");
                        }
                    }
                """;
    }

    /**
     * Builds a golden master capture harness.
     */
    private String buildGoldenMasterHarness(PatchUnit patch, ClassInfo classInfo) {
        StringBuilder sb = new StringBuilder();
        String harnessName = classInfo.className + "GoldenMasterCapture";

        if (classInfo.packageName != null && !classInfo.packageName.isEmpty()) {
            sb.append("package ").append(classInfo.packageName).append(";\n\n");
        }

        sb.append("""
                import java.io.*;
                import java.nio.file.*;
                import java.util.*;
                
                /**
                 * Golden master capture harness for %s.
                 * Runs the original code and captures output snapshots for regression testing.
                 *
                 * Auto-generated by ShadowStack for patch %s.
                 */
                public class %s {
                
                    private static final Path OUTPUT_DIR = Path.of(".shadowstack/golden-masters");
                
                    public static void main(String[] args) throws Exception {
                        Files.createDirectories(OUTPUT_DIR);
                        System.out.println("Capturing golden master snapshots for %s...");
                
                """.formatted(classInfo.className, patch.getPatchId(), harnessName, classInfo.className));

        // Generate capture calls for each public method
        int snapshotCount = 0;
        for (MethodInfo method : classInfo.publicMethods) {
            if (!method.isStatic && (classInfo.isAbstract || classInfo.isInterface)) continue;
            if ("void".equals(method.returnType)) continue;
            if (!method.parameterTypes.isEmpty()) continue; // Only zero-arg methods for auto-capture

            snapshotCount++;
            String snapshotId = "%s_%s".formatted(classInfo.className, method.name);

            if (method.isStatic) {
                sb.append("        capture(\"%s\", String.valueOf(%s.%s()));\n"
                        .formatted(snapshotId, classInfo.className, method.name));
            } else {
                sb.append("        {\n");
                sb.append("            %s instance = new %s();\n"
                        .formatted(classInfo.className, classInfo.className));
                sb.append("            capture(\"%s\", String.valueOf(instance.%s()));\n"
                        .formatted(snapshotId, method.name));
                sb.append("        }\n");
            }
        }

        if (snapshotCount == 0) {
            sb.append("        System.out.println(\"No auto-capturable methods found.\");\n");
            sb.append("        System.out.println(\"Add manual capture calls for methods with parameters.\");\n");
        }

        sb.append("""
                
                        System.out.println("Captured %d golden master snapshot(s).");
                    }
                
                    private static void capture(String id, String value) throws IOException {
                        Path file = OUTPUT_DIR.resolve(id + ".snapshot");
                        Files.writeString(file, value);
                        System.out.printf("  [CAPTURED] %%s = %%s%%n", id,
                                value.length() > 100 ? value.substring(0, 100) + "..." : value);
                    }
                }
                """.formatted(snapshotCount));

        return sb.toString();
    }

    // --- Parsing and extraction ---

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

    private ClassInfo extractClassInfo(CompilationUnit cu) {
        ClassInfo[] result = {null};

        // Get package
        String packageName = cu.getPackage() != null
                ? cu.getPackage().getName().getFullyQualifiedName() : "";

        cu.accept(new ASTVisitor() {
            @Override
            public boolean visit(TypeDeclaration node) {
                if (result[0] != null) return false; // Take first public class

                int modifiers = node.getModifiers();
                if (!Modifier.isPublic(modifiers) && result[0] == null) {
                    // Accept first class even if not public
                }

                ClassInfo info = new ClassInfo();
                info.className = node.getName().getIdentifier();
                info.packageName = packageName;
                info.isAbstract = Modifier.isAbstract(modifiers);
                info.isInterface = node.isInterface();

                // Check for default constructor
                @SuppressWarnings("unchecked")
                List<BodyDeclaration> bodyDecls = node.bodyDeclarations();
                boolean hasExplicitConstructor = false;
                for (BodyDeclaration bd : bodyDecls) {
                    if (bd instanceof MethodDeclaration md && md.isConstructor()) {
                        hasExplicitConstructor = true;
                        @SuppressWarnings("unchecked")
                        List<SingleVariableDeclaration> params = md.parameters();
                        if (params.isEmpty()) {
                            info.hasDefaultConstructor = true;
                        }
                    }
                }
                if (!hasExplicitConstructor) {
                    info.hasDefaultConstructor = true; // Implicit default constructor
                }

                // Extract public methods
                for (BodyDeclaration bd : bodyDecls) {
                    if (bd instanceof MethodDeclaration md && !md.isConstructor()) {
                        int mods = md.getModifiers();
                        if (Modifier.isPublic(mods) || Modifier.isProtected(mods)) {
                            MethodInfo mi = new MethodInfo();
                            mi.name = md.getName().getIdentifier();
                            mi.returnType = md.getReturnType2() != null
                                    ? md.getReturnType2().toString() : "void";
                            mi.isStatic = Modifier.isStatic(mods);

                            @SuppressWarnings("unchecked")
                            List<SingleVariableDeclaration> params = md.parameters();
                            for (SingleVariableDeclaration p : params) {
                                mi.parameterTypes.add(p.getType().toString());
                                mi.parameterNames.add(p.getName().getIdentifier());
                            }

                            @SuppressWarnings("unchecked")
                            List<Type> thrown = md.thrownExceptionTypes();
                            mi.throwsExceptions = !thrown.isEmpty();

                            info.publicMethods.add(mi);
                        }
                    }
                }

                result[0] = info;
                return false;
            }
        });

        return result[0];
    }

    // --- Fallback generators ---

    private String generateFallbackTest(PatchUnit patch) {
        return """
                import org.junit.jupiter.api.Test;
                import org.junit.jupiter.api.DisplayName;
                import static org.junit.jupiter.api.Assertions.*;
                
                /**
                 * Characterization test stub for patch %s.
                 * Auto-generated by ShadowStack — source parsing failed.
                 * TODO: Implement manual characterization tests.
                 */
                public class CharacterizationTest {
                
                    @Test
                    @DisplayName("Patch %s characterization")
                    void testPatchCharacterization() {
                        // TODO: Add characterization test for:
                        //   File: %s
                        //   Lines: %d-%d
                        //   Rule: %s
                        fail("Characterization test not yet implemented");
                    }
                }
                """.formatted(patch.getPatchId(), patch.getPatchId(),
                patch.getSourceFile(), patch.getStartLine(), patch.getEndLine(),
                patch.getRuleId());
    }

    private String generateFallbackHarness(PatchUnit patch) {
        return """
                /**
                 * Golden master capture harness stub for patch %s.
                 * TODO: Implement manual capture for %s
                 */
                public class GoldenMasterCapture {
                    public static void main(String[] args) {
                        System.out.println("TODO: Implement golden master capture for patch %s");
                    }
                }
                """.formatted(patch.getPatchId(), patch.getSourceFile(), patch.getPatchId());
    }

    // --- Utility ---

    private String sanitizeMethodName(String name) {
        return name.replaceAll("[^a-zA-Z0-9_]", "_");
    }

    private String getDefaultValue(String type) {
        return switch (type) {
            case "int", "Integer" -> "0";
            case "long", "Long" -> "0L";
            case "double", "Double" -> "0.0";
            case "float", "Float" -> "0.0f";
            case "boolean", "Boolean" -> "false";
            case "char", "Character" -> "'\\0'";
            case "byte", "Byte" -> "(byte) 0";
            case "short", "Short" -> "(short) 0";
            case "String" -> "\"\"";
            default -> "null /* %s */".formatted(type);
        };
    }

    private boolean isPrimitiveOrWrapper(String type) {
        return Set.of("int", "Integer", "long", "Long", "double", "Double",
                "float", "Float", "boolean", "Boolean", "char", "Character",
                "byte", "Byte", "short", "Short").contains(type);
    }

    private static final class ClassInfo {
        String className;
        String packageName;
        boolean isAbstract;
        boolean isInterface;
        boolean hasDefaultConstructor;
        List<MethodInfo> publicMethods = new ArrayList<>();
    }

    private static final class MethodInfo {
        String name;
        String returnType;
        boolean isStatic;
        boolean throwsExceptions;
        List<String> parameterTypes = new ArrayList<>();
        List<String> parameterNames = new ArrayList<>();
    }
}
