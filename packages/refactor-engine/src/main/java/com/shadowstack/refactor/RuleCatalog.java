package com.shadowstack.refactor;

import com.shadowstack.refactor.rules.AnonymousClassToLambdaRule;
import com.shadowstack.refactor.rules.BoxingConstructorRule;
import com.shadowstack.refactor.rules.CollectionsSortToListSortRule;
import com.shadowstack.refactor.rules.DiamondOperatorRule;
import com.shadowstack.refactor.rules.LegacyTypeMigrationRule;
import com.shadowstack.refactor.rules.SizeZeroToIsEmptyRule;

import java.util.ArrayList;
import java.util.List;

/**
 * Central catalog of industry-aligned Java modernization rules
 * (OpenRewrite / Sonar / JDK deprecation traffic patterns).
 */
public final class RuleCatalog {

    private RuleCatalog() {}

    public static List<RefactorRule> javaRules() {
        List<RefactorRule> rules = new ArrayList<>();
        rules.add(new AnonymousClassToLambdaRule());
        rules.add(new DiamondOperatorRule());
        rules.add(new CollectionsSortToListSortRule());
        rules.add(LegacyTypeMigrationRule.stringBuffer());
        rules.add(LegacyTypeMigrationRule.vector());
        rules.add(LegacyTypeMigrationRule.hashtable());
        rules.add(LegacyTypeMigrationRule.stack());
        rules.add(new BoxingConstructorRule());
        rules.add(new SizeZeroToIsEmptyRule());
        return rules;
    }

    public static String supportSummary() {
        return """
                Java (9): ANON_TO_LAMBDA, DIAMOND_OPERATOR, COLLECTIONS_SORT_TO_LIST_SORT,
                  STRINGBUFFER_TO_STRINGBUILDER, VECTOR_TO_ARRAYLIST, HASHTABLE_TO_HASHMAP,
                  STACK_TO_ARRAYDEQUE, BOXING_CONSTRUCTOR_TO_VALUEOF, SIZE_ZERO_TO_ISEMPTY
                Python (lib2to3/modernize classics via PythonAdapter)
                COBOL (enterprise patterns via CobolAdapter)
                """;
    }
}
