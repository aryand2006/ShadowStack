package com.shadowstack.analysis;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/**
 * Generates deterministic semantic fingerprints for Java methods.
 *
 * <p>A semantic fingerprint is a SHA-256 hash of a method's <em>normalized</em>
 * structural representation. It captures the semantically meaningful aspects of
 * a method — signature, parameter types, return type, called methods, and field
 * accesses — while being deliberately insensitive to whitespace, comments, local
 * variable names, and formatting changes.</p>
 *
 * <h3>Fingerprint composition</h3>
 * The normalized representation is built by concatenating (with delimiters):
 * <ol>
 *   <li>Fully-qualified method name</li>
 *   <li>Return type (erased)</li>
 *   <li>Parameter types (erased, sorted lexicographically for stability)</li>
 *   <li>Called methods (sorted)</li>
 *   <li>Field accesses (sorted)</li>
 * </ol>
 *
 * <p>The resulting string is hashed with SHA-256 and encoded as lowercase hex.
 * Two methods with identical fingerprints are considered semantically equivalent
 * for the purpose of refactoring verification.</p>
 *
 * <p>This class is stateless and thread-safe.</p>
 *
 * @see BaselineCapture
 */
public final class SemanticFingerprint {

    private static final Logger LOG = LoggerFactory.getLogger(SemanticFingerprint.class);

    private static final String DELIMITER = "|";
    private static final String LIST_SEPARATOR = ",";
    private static final String ALGORITHM = "SHA-256";

    private SemanticFingerprint() {
        throw new AssertionError("Utility class — do not instantiate");
    }

    // ─── Public API ──────────────────────────────────────────────────

    /**
     * Computes a deterministic semantic fingerprint for a method.
     *
     * @param descriptor the method descriptor containing all semantic components
     * @return a lowercase hex SHA-256 hash string (64 characters)
     * @throws NullPointerException if {@code descriptor} is null
     */
    public static String compute(MethodDescriptor descriptor) {
        Objects.requireNonNull(descriptor, "descriptor must not be null");

        String normalized = normalize(descriptor);
        String fingerprint = sha256(normalized);

        LOG.debug("Fingerprint for {}: {} (normalized length={})",
                descriptor.qualifiedName(), fingerprint, normalized.length());

        return fingerprint;
    }

    /**
     * Computes a fingerprint from raw components without creating a descriptor.
     *
     * @param qualifiedName  fully-qualified method name
     * @param returnType     return type
     * @param parameterTypes parameter types
     * @param calledMethods  methods invoked by this method
     * @param fieldAccesses  fields accessed by this method
     * @return a lowercase hex SHA-256 hash string
     */
    public static String compute(
            String qualifiedName,
            String returnType,
            List<String> parameterTypes,
            List<String> calledMethods,
            List<String> fieldAccesses) {

        return compute(new MethodDescriptor(
                qualifiedName, returnType, parameterTypes, calledMethods, fieldAccesses));
    }

    /**
     * Returns the normalized representation that would be hashed.
     * Useful for debugging fingerprint mismatches.
     *
     * @param descriptor the method descriptor
     * @return the normalized string before hashing
     */
    public static String normalizedForm(MethodDescriptor descriptor) {
        Objects.requireNonNull(descriptor, "descriptor must not be null");
        return normalize(descriptor);
    }

    // ─── Internal ────────────────────────────────────────────────────

    /**
     * Builds the canonical normalized representation of a method.
     *
     * <p>All list components are sorted to ensure determinism regardless of
     * the order in which the AST visitor encounters them.</p>
     */
    private static String normalize(MethodDescriptor descriptor) {
        StringBuilder sb = new StringBuilder(512);

        // 1) Qualified method name
        sb.append(descriptor.qualifiedName().trim());
        sb.append(DELIMITER);

        // 2) Return type (erased, trimmed)
        sb.append(normalizeType(descriptor.returnType()));
        sb.append(DELIMITER);

        // 3) Parameter types — sorted for stability
        List<String> params = new ArrayList<>(descriptor.parameterTypes());
        Collections.sort(params);
        sb.append(String.join(LIST_SEPARATOR, params.stream().map(SemanticFingerprint::normalizeType).toList()));
        sb.append(DELIMITER);

        // 4) Called methods — sorted
        List<String> calls = new ArrayList<>(descriptor.calledMethods());
        Collections.sort(calls);
        sb.append(String.join(LIST_SEPARATOR, calls.stream().map(String::trim).toList()));
        sb.append(DELIMITER);

        // 5) Field accesses — sorted
        List<String> fields = new ArrayList<>(descriptor.fieldAccesses());
        Collections.sort(fields);
        sb.append(String.join(LIST_SEPARATOR, fields.stream().map(String::trim).toList()));

        return sb.toString();
    }

    /**
     * Normalizes a type name by trimming whitespace and erasing generic parameters.
     * {@code java.util.List<String>} becomes {@code java.util.List}.
     */
    private static String normalizeType(String type) {
        if (type == null) {
            return "void";
        }
        String trimmed = type.trim();
        int angleBracket = trimmed.indexOf('<');
        if (angleBracket >= 0) {
            trimmed = trimmed.substring(0, angleBracket);
        }
        return trimmed;
    }

    /**
     * Computes SHA-256 of the given input and returns lowercase hex.
     */
    private static String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance(ALGORITHM);
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandated by the JCA specification — should never happen.
            throw new IllegalStateException("SHA-256 algorithm not available", e);
        }
    }

    // ═════════════════════════════════════════════════════════════════
    //  MethodDescriptor DTO
    // ═════════════════════════════════════════════════════════════════

    /**
     * Describes the semantically relevant components of a method used for fingerprinting.
     *
     * @param qualifiedName  fully-qualified name, e.g. {@code com.example.Foo#bar}
     * @param returnType     return type (may include generics, which are erased during normalization)
     * @param parameterTypes parameter types in declaration order
     * @param calledMethods  fully-qualified names of methods invoked within the body
     * @param fieldAccesses  fully-qualified names of fields read or written within the body
     */
    public record MethodDescriptor(
            String qualifiedName,
            String returnType,
            List<String> parameterTypes,
            List<String> calledMethods,
            List<String> fieldAccesses
    ) {
        public MethodDescriptor {
            Objects.requireNonNull(qualifiedName, "qualifiedName");
            returnType = returnType == null ? "void" : returnType;
            parameterTypes = parameterTypes == null ? List.of() : List.copyOf(parameterTypes);
            calledMethods = calledMethods == null ? List.of() : List.copyOf(calledMethods);
            fieldAccesses = fieldAccesses == null ? List.of() : List.copyOf(fieldAccesses);
        }
    }
}
