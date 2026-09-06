package com.shadowstack.refactor.rules;

import com.shadowstack.refactor.RefactorEngine;
import com.shadowstack.refactor.RefactorRule;
import com.shadowstack.refactor.RuleCatalog;
import com.shadowstack.refactor.model.PatchUnit;
import com.shadowstack.refactor.model.RiskTier;
import com.shadowstack.refactor.model.SemanticContext;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class IndustryJavaModernizationRulesTest {

    @Test
    void catalogRegistersExpandedIndustryRules() {
        List<RefactorRule> rules = RuleCatalog.javaRules();
        assertEquals(45, rules.size());
        Set<String> ids = rules.stream().map(RefactorRule::ruleId).collect(Collectors.toSet());
        assertTrue(ids.contains("ANON_TO_LAMBDA"));
        assertTrue(ids.contains("DIAMOND_OPERATOR"));
        assertTrue(ids.contains("COLLECTIONS_SORT_TO_LIST_SORT"));
        assertTrue(ids.contains("STRINGBUFFER_TO_STRINGBUILDER"));
        assertTrue(ids.contains("VECTOR_TO_ARRAYLIST"));
        assertTrue(ids.contains("HASHTABLE_TO_HASHMAP"));
        assertTrue(ids.contains("STACK_TO_ARRAYDEQUE"));
        assertTrue(ids.contains("BOXING_CONSTRUCTOR_TO_VALUEOF"));
        assertTrue(ids.contains("SIZE_ZERO_TO_ISEMPTY"));
        assertTrue(ids.contains("INDEXOF_TO_CONTAINS"));
        assertTrue(ids.contains("CLASS_NEWINSTANCE_TO_GETDECLAREDCONSTRUCTOR"));
        assertTrue(ids.contains("STRING_EQUALS_LITERAL_FIRST"));
        assertTrue(ids.contains("TOUPPERLOWER_LOCALE_ROOT"));
        assertTrue(ids.contains("COLLECTIONS_EMPTY_CONSTANT"));
        assertTrue(ids.contains("STRING_GETBYTES_CHARSET"));
        assertTrue(ids.contains("URLENCODER_CHARSET"));
        assertTrue(ids.contains("STRING_TRIM_TO_STRIP"));

        assertTrue(ids.contains("PATHS_GET_TO_PATH_OF"));
        assertTrue(ids.contains("ARRAYS_ASLIST_TO_LISTOF"));
        assertTrue(ids.contains("UNMODIFIABLE_TO_COPYOF"));
        assertTrue(ids.contains("URL_CTOR_TO_URI"));
        assertTrue(ids.contains("BOOLEAN_CTOR_TO_VALUEOF"));
    }

    @Test
    void detectsOpenRewriteAndSonarClassics() {
        String source = """
                import java.util.*;
                class Demo {
                  void m(List<String> list, Class<?> type, String name) throws Exception {
                    StringBuffer sb = new StringBuffer("x");
                    Vector<String> v = new Vector<String>();
                    Hashtable<String, String> h = new Hashtable<String, String>();
                    Stack<String> st = new Stack<String>();
                    Integer n = new Integer(3);
                    if (list.size() == 0) {
                      return;
                    }
                    Collections.sort(list);
                    List<String> copy = new ArrayList<String>();
                    if (name.indexOf("x") >= 0) {
                      return;
                    }
                    Object o = type.newInstance();
                    if (name.equals("admin")) {
                      return;
                    }
                    String up = name.toUpperCase();
                    List empty = Collections.EMPTY_LIST;
                    byte[] bytes = name.getBytes();
                    String enc = java.net.URLEncoder.encode(name);
                    String trimmed = name.trim();
                  }
                }
                """;
        CompilationUnit cu = parse(source);
        SemanticContext ctx = SemanticContext.builder()
                .compilationUnit(cu)
                .sourceFilePath("Demo.java")
                .sourceCode(source)
                .build();
        RefactorEngine engine = new RefactorEngine(0.5, RiskTier.CRITICAL);
        for (RefactorRule rule : RuleCatalog.javaRules()) {
            engine.registerRule(rule);
        }
        List<PatchUnit> patches = engine.scan(cu, ctx);
        Set<String> ids = patches.stream().map(PatchUnit::getRuleId).collect(Collectors.toSet());

        assertTrue(ids.contains("STRINGBUFFER_TO_STRINGBUILDER"), ids.toString());
        assertTrue(ids.contains("VECTOR_TO_ARRAYLIST"), ids.toString());
        assertTrue(ids.contains("HASHTABLE_TO_HASHMAP"), ids.toString());
        assertTrue(ids.contains("STACK_TO_ARRAYDEQUE"), ids.toString());
        assertTrue(ids.contains("BOXING_CONSTRUCTOR_TO_VALUEOF"), ids.toString());
        assertTrue(ids.contains("SIZE_ZERO_TO_ISEMPTY"), ids.toString());
        assertTrue(ids.contains("COLLECTIONS_SORT_TO_LIST_SORT"), ids.toString());
        assertTrue(ids.contains("DIAMOND_OPERATOR"), ids.toString());
        assertTrue(ids.contains("INDEXOF_TO_CONTAINS"), ids.toString());
        assertTrue(ids.contains("CLASS_NEWINSTANCE_TO_GETDECLAREDCONSTRUCTOR"), ids.toString());
        assertTrue(ids.contains("STRING_EQUALS_LITERAL_FIRST"), ids.toString());
        assertTrue(ids.contains("TOUPPERLOWER_LOCALE_ROOT"), ids.toString());
        assertTrue(patches.stream().anyMatch(p ->
                p.getRuleId().equals("INDEXOF_TO_CONTAINS")
                        && p.getAfterSnippet().contains("contains(")));
        assertTrue(patches.stream().anyMatch(p ->
                p.getRuleId().equals("STRING_EQUALS_LITERAL_FIRST")
                        && p.getAfterSnippet().contains("\"admin\".equals")));
        assertTrue(ids.contains("COLLECTIONS_EMPTY_CONSTANT"), ids.toString());
        assertTrue(ids.contains("STRING_GETBYTES_CHARSET"), ids.toString());
        assertTrue(ids.contains("URLENCODER_CHARSET"), ids.toString());
        assertTrue(ids.contains("STRING_TRIM_TO_STRIP"), ids.toString());
    }

    @Test
    void collectionsEmptyConstant_migrates() {
        assertRuleFires("""
                import java.util.Collections;
                import java.util.List;
                class Demo {
                  List empty = Collections.EMPTY_LIST;
                }
                """, "COLLECTIONS_EMPTY_CONSTANT", "emptyList()");
    }

    @Test
    void stringGetBytesCharset_migrates() {
        assertRuleFires("""
                class Demo {
                  byte[] b = "hi".getBytes();
                }
                """, "STRING_GETBYTES_CHARSET", "StandardCharsets.UTF_8");
    }

    @Test
    void urlEncoderCharset_migrates() {
        assertRuleFires("""
                import java.net.URLEncoder;
                class Demo {
                  String e = URLEncoder.encode("a=b");
                }
                """, "URLENCODER_CHARSET", "StandardCharsets.UTF_8");
    }

    @Test
    void stringTrimToStrip_migrates() {
        assertRuleFires("""
                class Demo {
                  String s = "  x  ".trim();
                }
                """, "STRING_TRIM_TO_STRIP", "strip()");
    }


    @Test
    void pathsGetToPathOf_migrates() {
        assertRuleFires("""
                import java.nio.file.Paths;
                class Demo {
                  Object p = Paths.get("a");
                }
                """, "PATHS_GET_TO_PATH_OF", "Path.of");
    }

    @Test
    void arraysAsListToListOf_migrates() {
        assertRuleFires("""
                import java.util.Arrays;
                class Demo {
                  Object x = Arrays.asList("a", "b");
                }
                """, "ARRAYS_ASLIST_TO_LISTOF", "List.of");
    }

    @Test
    void booleanCtorToValueOf_migrates() {
        assertRuleFires("""
                class Demo {
                  Boolean b = new Boolean(true);
                }
                """, "BOOLEAN_CTOR_TO_VALUEOF", "Boolean.valueOf");
    }

    @Test
    void collectionsEmptyListToListOf_migrates() {
        assertRuleFires("""
                import java.util.Collections;
                class Demo {
                  Object x = Collections.emptyList();
                }
                """, "COLLECTIONS_EMPTYLIST_TO_LISTOF", "List.of()");
    }

    private static void assertRuleFires(String source, String ruleId, String afterContains) {
        CompilationUnit cu = parse(source);
        SemanticContext ctx = SemanticContext.builder()
                .compilationUnit(cu)
                .sourceFilePath("Demo.java")
                .sourceCode(source)
                .build();
        RefactorEngine engine = new RefactorEngine(0.5, RiskTier.CRITICAL);
        for (RefactorRule rule : RuleCatalog.javaRules()) {
            if (ruleId.equals(rule.ruleId())) {
                engine.registerRule(rule);
            }
        }
        List<PatchUnit> patches = engine.scan(cu, ctx);
        assertTrue(patches.stream().anyMatch(p -> ruleId.equals(p.getRuleId())
                        && p.getAfterSnippet().contains(afterContains)),
                () -> patches.stream().map(PatchUnit::getRuleId).toList().toString());
    }

    private static CompilationUnit parse(String source) {
        ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
        parser.setKind(ASTParser.K_COMPILATION_UNIT);
        parser.setSource(source.toCharArray());
        parser.setResolveBindings(false);
        Map<String, String> options = new HashMap<>();
        options.put("org.eclipse.jdt.core.compiler.source", "21");
        options.put("org.eclipse.jdt.core.compiler.compliance", "21");
        options.put("org.eclipse.jdt.core.compiler.codegen.targetPlatform", "21");
        parser.setCompilerOptions(options);
        parser.setUnitName("Demo.java");
        return (CompilationUnit) parser.createAST(null);
    }
}
