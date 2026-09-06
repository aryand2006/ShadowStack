package com.shadowstack.refactor;

import com.shadowstack.refactor.rules.AnonymousClassToLambdaRule;
import com.shadowstack.refactor.rules.BoxingConstructorRule;
import com.shadowstack.refactor.rules.ClassNewInstanceRule;
import com.shadowstack.refactor.rules.CollectionsSortToListSortRule;
import com.shadowstack.refactor.rules.DiamondOperatorRule;
import com.shadowstack.refactor.rules.IndexOfToContainsRule;
import com.shadowstack.refactor.rules.LegacyTypeMigrationRule;
import com.shadowstack.refactor.rules.SizeZeroToIsEmptyRule;
import com.shadowstack.refactor.rules.StringEqualsLiteralFirstRule;
import com.shadowstack.refactor.rules.CollectionsEmptyConstantRule;
import com.shadowstack.refactor.rules.StringGetBytesCharsetRule;
import com.shadowstack.refactor.rules.StringTrimToStripRule;
import com.shadowstack.refactor.rules.ToUpperLowerLocaleRootRule;
import com.shadowstack.refactor.rules.UrlEncoderCharsetRule;

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
        rules.add(new IndexOfToContainsRule());
        rules.add(new ClassNewInstanceRule());
        rules.add(new StringEqualsLiteralFirstRule());
        rules.add(new ToUpperLowerLocaleRootRule());
        rules.add(new CollectionsEmptyConstantRule());
        rules.add(new StringGetBytesCharsetRule());
        rules.add(new UrlEncoderCharsetRule());
        rules.add(new StringTrimToStripRule());
        return rules;
    }

    public static String supportSummary() {
        return """
                Java (17): ANON_TO_LAMBDA, DIAMOND_OPERATOR, COLLECTIONS_SORT_TO_LIST_SORT,
                  STRINGBUFFER_TO_STRINGBUILDER, VECTOR_TO_ARRAYLIST, HASHTABLE_TO_HASHMAP,
                  STACK_TO_ARRAYDEQUE, BOXING_CONSTRUCTOR_TO_VALUEOF, SIZE_ZERO_TO_ISEMPTY,
                  INDEXOF_TO_CONTAINS, CLASS_NEWINSTANCE_TO_GETDECLAREDCONSTRUCTOR,
                  STRING_EQUALS_LITERAL_FIRST, TOUPPERLOWER_LOCALE_ROOT,
                  COLLECTIONS_EMPTY_CONSTANT, STRING_GETBYTES_CHARSET, URLENCODER_CHARSET,
                  STRING_TRIM_TO_STRIP
                Python (lib2to3/modernize classics via PythonAdapter)
                COBOL (enterprise patterns via CobolAdapter)
                """;
    }
}
