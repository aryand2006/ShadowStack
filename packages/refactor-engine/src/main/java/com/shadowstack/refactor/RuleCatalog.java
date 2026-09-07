package com.shadowstack.refactor;

import com.shadowstack.refactor.rules.LegacyTypeMigrationRule;
import com.shadowstack.refactor.rules.AnonymousClassToLambdaRule;
import com.shadowstack.refactor.rules.ArraysAsListToListOfRule;
import com.shadowstack.refactor.rules.BooleanConstructorRule;
import com.shadowstack.refactor.rules.BoxingConstructorRule;
import com.shadowstack.refactor.rules.ByteConstructorRule;
import com.shadowstack.refactor.rules.CharacterConstructorRule;
import com.shadowstack.refactor.rules.ClassForNameRule;
import com.shadowstack.refactor.rules.ClassNewInstanceRule;
import com.shadowstack.refactor.rules.CollectionsEmptyConstantRule;
import com.shadowstack.refactor.rules.CollectionsEmptyListToListOfRule;
import com.shadowstack.refactor.rules.CollectionsEmptyMapToMapOfRule;
import com.shadowstack.refactor.rules.CollectionsEmptySetToSetOfRule;
import com.shadowstack.refactor.rules.CollectionsSingletonListToListOfRule;
import com.shadowstack.refactor.rules.CollectionsSingletonMapToMapOfRule;
import com.shadowstack.refactor.rules.CollectionsSingletonToSetOfRule;
import com.shadowstack.refactor.rules.CollectionsSortToListSortRule;
import com.shadowstack.refactor.rules.ComputeIfAbsentDetectRule;
import com.shadowstack.refactor.rules.DeprecatedThreadApiRule;
import com.shadowstack.refactor.rules.DiamondOperatorRule;
import com.shadowstack.refactor.rules.DoubleConstructorRule;
import com.shadowstack.refactor.rules.FilesReadAllBytesToReadStringRule;
import com.shadowstack.refactor.rules.FilesWriteToWriteStringRule;
import com.shadowstack.refactor.rules.FloatConstructorRule;
import com.shadowstack.refactor.rules.GuavaImmutableListOfRule;
import com.shadowstack.refactor.rules.GuavaImmutableMapOfRule;
import com.shadowstack.refactor.rules.GuavaImmutableSetOfRule;
import com.shadowstack.refactor.rules.HttpUrlConnectionToHttpClientRule;
import com.shadowstack.refactor.rules.IndexOfToContainsRule;
import com.shadowstack.refactor.rules.InputStreamReadAllBytesRule;
import com.shadowstack.refactor.rules.JUnit4AssertToJupiterRule;
import com.shadowstack.refactor.rules.JavaxToJakartaAnnotationRule;
import com.shadowstack.refactor.rules.JavaxToJakartaInjectRule;
import com.shadowstack.refactor.rules.JavaxToJakartaServletRule;
import com.shadowstack.refactor.rules.LongConstructorRule;
import com.shadowstack.refactor.rules.MapGetOrDefaultRule;
import com.shadowstack.refactor.rules.NewDateToInstantRule;
import com.shadowstack.refactor.rules.ObjectsEqualsNullSafeRule;
import com.shadowstack.refactor.rules.OptionalIsPresentGetRule;
import com.shadowstack.refactor.rules.PathsGetToPathOfRule;
import com.shadowstack.refactor.rules.ReaderWriterCharsetCtorRule;
import com.shadowstack.refactor.rules.RunFinalizersOnExitRule;
import com.shadowstack.refactor.rules.RuntimeExecToProcessBuilderRule;
import com.shadowstack.refactor.rules.SequencedCollectionGetFirstRule;
import com.shadowstack.refactor.rules.SequencedCollectionGetLastRule;
import com.shadowstack.refactor.rules.ShortConstructorRule;
import com.shadowstack.refactor.rules.SimpleDateFormatToDateTimeFormatterRule;
import com.shadowstack.refactor.rules.SizeZeroToIsEmptyRule;
import com.shadowstack.refactor.rules.StringEqualsLiteralFirstRule;
import com.shadowstack.refactor.rules.StringFormattedRule;
import com.shadowstack.refactor.rules.StringGetBytesCharsetRule;
import com.shadowstack.refactor.rules.StringIsEmptyRule;
import com.shadowstack.refactor.rules.StringTrimToStripRule;
import com.shadowstack.refactor.rules.SystemSetSecurityManagerRule;
import com.shadowstack.refactor.rules.ThreadYieldRule;
import com.shadowstack.refactor.rules.ToUpperLowerLocaleRootRule;
import com.shadowstack.refactor.rules.UnmodifiableToCopyOfRule;
import com.shadowstack.refactor.rules.UrlConstructorToUriRule;
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
        rules.add(LegacyTypeMigrationRule.stringBuffer());
        rules.add(LegacyTypeMigrationRule.vector());
        rules.add(LegacyTypeMigrationRule.hashtable());
        rules.add(LegacyTypeMigrationRule.stack());
        rules.add(new AnonymousClassToLambdaRule());
        rules.add(new ArraysAsListToListOfRule());
        rules.add(new BooleanConstructorRule());
        rules.add(new BoxingConstructorRule());
        rules.add(new ByteConstructorRule());
        rules.add(new CharacterConstructorRule());
        rules.add(new ClassForNameRule());
        rules.add(new ClassNewInstanceRule());
        rules.add(new CollectionsEmptyConstantRule());
        rules.add(new CollectionsEmptyListToListOfRule());
        rules.add(new CollectionsEmptyMapToMapOfRule());
        rules.add(new CollectionsEmptySetToSetOfRule());
        rules.add(new CollectionsSingletonListToListOfRule());
        rules.add(new CollectionsSingletonMapToMapOfRule());
        rules.add(new CollectionsSingletonToSetOfRule());
        rules.add(new CollectionsSortToListSortRule());
        rules.add(new DeprecatedThreadApiRule());
        rules.add(new DiamondOperatorRule());
        rules.add(new DoubleConstructorRule());
        rules.add(new FilesReadAllBytesToReadStringRule());
        rules.add(new FilesWriteToWriteStringRule());
        rules.add(new FloatConstructorRule());
        rules.add(new IndexOfToContainsRule());
        rules.add(new LongConstructorRule());
        rules.add(new NewDateToInstantRule());
        rules.add(new PathsGetToPathOfRule());
        rules.add(new ReaderWriterCharsetCtorRule());
        rules.add(new RunFinalizersOnExitRule());
        rules.add(new RuntimeExecToProcessBuilderRule());
        rules.add(new ShortConstructorRule());
        rules.add(new SimpleDateFormatToDateTimeFormatterRule());
        rules.add(new SizeZeroToIsEmptyRule());
        rules.add(new StringEqualsLiteralFirstRule());
        rules.add(new StringGetBytesCharsetRule());
        rules.add(new StringTrimToStripRule());
        rules.add(new SystemSetSecurityManagerRule());
        rules.add(new ThreadYieldRule());
        rules.add(new ToUpperLowerLocaleRootRule());
        rules.add(new UnmodifiableToCopyOfRule());
        rules.add(new UrlConstructorToUriRule());
        rules.add(new UrlEncoderCharsetRule());
        // OpenRewrite / Sonar high-traffic parity expansions
        rules.add(new JavaxToJakartaServletRule());
        rules.add(new JavaxToJakartaInjectRule());
        rules.add(new JavaxToJakartaAnnotationRule());
        rules.add(new OptionalIsPresentGetRule());
        rules.add(new ObjectsEqualsNullSafeRule());
        rules.add(new StringIsEmptyRule());
        rules.add(new GuavaImmutableListOfRule());
        rules.add(new GuavaImmutableSetOfRule());
        rules.add(new GuavaImmutableMapOfRule());
        rules.add(new InputStreamReadAllBytesRule());
        rules.add(new StringFormattedRule());
        rules.add(new SequencedCollectionGetFirstRule());
        rules.add(new SequencedCollectionGetLastRule());
        rules.add(new HttpUrlConnectionToHttpClientRule());
        rules.add(new JUnit4AssertToJupiterRule());
        rules.add(new MapGetOrDefaultRule());
        rules.add(new ComputeIfAbsentDetectRule());
        return rules;
    }

    public static String supportSummary() {
        return """
                Java (62):
                  STRINGBUFFER_TO_STRINGBUILDER, VECTOR_TO_ARRAYLIST, HASHTABLE_TO_HASHMAP,
                  STACK_TO_ARRAYDEQUE, ANON_TO_LAMBDA, ARRAYS_ASLIST_TO_LISTOF,
                  BOOLEAN_CTOR_TO_VALUEOF, BOXING_CONSTRUCTOR_TO_VALUEOF, BYTE_CTOR_TO_VALUEOF,
                  CHARACTER_CTOR_TO_VALUEOF, CLASS_FORNAME_TRUE_LOADER,
                  CLASS_NEWINSTANCE_TO_GETDECLAREDCONSTRUCTOR, COLLECTIONS_EMPTY_CONSTANT,
                  COLLECTIONS_EMPTYLIST_TO_LISTOF, COLLECTIONS_EMPTYMAP_TO_MAPOF,
                  COLLECTIONS_EMPTYSET_TO_SETOF, COLLECTIONS_SINGLETONLIST_TO_LISTOF,
                  COLLECTIONS_SINGLETONMAP_TO_MAPOF, COLLECTIONS_SINGLETON_TO_SETOF,
                  COLLECTIONS_SORT_TO_LIST_SORT, DEPRECATED_THREAD_API, DIAMOND_OPERATOR,
                  DOUBLE_CTOR_TO_VALUEOF, FILES_READALLBYTES_TO_READSTRING,
                  FILES_WRITE_TO_WRITESTRING, FLOAT_CTOR_TO_VALUEOF, INDEXOF_TO_CONTAINS,
                  LONG_CTOR_TO_VALUEOF, NEW_DATE_TO_INSTANT, PATHS_GET_TO_PATH_OF,
                  READER_WRITER_CHARSET_CTOR, RUN_FINALIZERS_ON_EXIT,
                  RUNTIME_EXEC_TO_PROCESSBUILDER, SHORT_CTOR_TO_VALUEOF,
                  SIMPLEDATEFORMAT_TO_DATETIMEFORMATTER, SIZE_ZERO_TO_ISEMPTY,
                  STRING_EQUALS_LITERAL_FIRST, STRING_GETBYTES_CHARSET, STRING_TRIM_TO_STRIP,
                  SYSTEM_SETSECURITYMANAGER_REMOVED, THREAD_YIELD_TO_ONSPINWAIT,
                  TOUPPERLOWER_LOCALE_ROOT, UNMODIFIABLE_TO_COPYOF, URL_CTOR_TO_URI,
                  URLENCODER_CHARSET, JAVAX_SERVLET_TO_JAKARTA, JAVAX_INJECT_TO_JAKARTA,
                  JAVAX_ANNOTATION_TO_JAKARTA, OPTIONAL_ISPRESENT_GET, OBJECTS_EQUALS_NULL_SAFE,
                  STRING_ISEMPTY, GUAVA_IMMUTABLELIST_TO_LISTOF, GUAVA_IMMUTABLESET_TO_SETOF,
                  GUAVA_IMMUTABLEMAP_TO_MAPOF, INPUTSTREAM_READALLBYTES, STRING_FORMATTED,
                  SEQUENCED_GET_FIRST, SEQUENCED_GET_LAST, HTTPURLCONNECTION_TO_HTTPCLIENT,
                  JUNIT4_ASSERT_TO_JUPITER, MAP_GET_OR_DEFAULT, COMPUTE_IF_ABSENT_DETECT
                Python (full): LibCST AST + py_compile hard gate (missing python3 → FAIL)
                COBOL (full preserving / translate detect-only): cobc hard-gated COBOL→COBOL;
                  translate stubs stay detect-only (not Blu Age semantic rehost)
                JavaScript/TypeScript (full): Acorn AST + node --check hard gate (missing node → FAIL)
                C# (full): Roslyn AST + dotnet build hard gate (missing SDK/.csproj → FAIL)
                """;
    }
}
