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
    void catalogRegistersNineIndustryRules() {
        List<RefactorRule> rules = RuleCatalog.javaRules();
        assertEquals(9, rules.size());
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
    }

    @Test
    void detectsOpenRewriteAndSonarClassics() {
        String source = """
                import java.util.*;
                class Demo {
                  void m(List<String> list) {
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
        assertTrue(patches.stream().anyMatch(p ->
                p.getRuleId().equals("BOXING_CONSTRUCTOR_TO_VALUEOF")
                        && p.getAfterSnippet().contains("Integer.valueOf")));
        assertTrue(patches.stream().anyMatch(p ->
                p.getRuleId().equals("SIZE_ZERO_TO_ISEMPTY")
                        && p.getAfterSnippet().contains("isEmpty()")));
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
