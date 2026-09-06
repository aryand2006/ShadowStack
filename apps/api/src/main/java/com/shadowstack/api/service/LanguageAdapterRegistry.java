package com.shadowstack.api.service;

import com.shadowstack.adapters.LanguageAdapter;
import com.shadowstack.adapters.cobol.CobolAdapter;
import com.shadowstack.adapters.csharp.CsharpAdapter;
import com.shadowstack.adapters.java.JavaAdapter;
import com.shadowstack.adapters.javascript.JavascriptAdapter;
import com.shadowstack.adapters.python.PythonAdapter;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Resolves {@link LanguageAdapter} implementations for project source languages.
 * Java live convert still prefers {@code RefactorEngine}; adapters cover the
 * remaining languages and can also analyze Java when needed.
 */
@Component
public class LanguageAdapterRegistry {

    private final Map<String, LanguageAdapter> adapters = new LinkedHashMap<>();

    public LanguageAdapterRegistry() {
        register(new JavaAdapter());
        register(new PythonAdapter());
        register(new CobolAdapter());
        register(new JavascriptAdapter());
        register(new CsharpAdapter());
    }

    private void register(LanguageAdapter adapter) {
        adapters.put(normalize(adapter.languageId()), adapter);
    }

    public Optional<LanguageAdapter> find(String languageId) {
        if (languageId == null || languageId.isBlank()) {
            return Optional.empty();
        }
        String key = normalize(languageId);
        LanguageAdapter direct = adapters.get(key);
        if (direct != null) {
            return Optional.of(direct);
        }
        return switch (key) {
            case "py", "python3" -> Optional.ofNullable(adapters.get("python"));
            case "js", "ts", "typescript", "nodejs", "node" -> Optional.ofNullable(adapters.get("javascript"));
            case "c#", "cs", "dotnet", ".net" -> Optional.ofNullable(adapters.get("csharp"));
            case "cbl", "cob" -> Optional.ofNullable(adapters.get("cobol"));
            default -> Optional.empty();
        };
    }

    public LanguageAdapter require(String languageId) {
        return find(languageId).orElseThrow(() ->
                new IllegalArgumentException("No language adapter registered for '" + languageId + "'"));
    }

    public boolean isJavaEngineLanguage(String languageId) {
        String key = normalize(languageId);
        return key.isEmpty() || key.equals("java");
    }

    public Set<String> supportedLanguages() {
        return Set.copyOf(adapters.keySet());
    }

    private static String normalize(String languageId) {
        return languageId == null ? "" : languageId.trim().toLowerCase(Locale.ROOT);
    }
}
