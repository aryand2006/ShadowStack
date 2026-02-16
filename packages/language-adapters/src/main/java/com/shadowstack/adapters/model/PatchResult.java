package com.shadowstack.adapters.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.*;

/**
 * Result of applying a refactoring transformation to source code.
 *
 * <p>A {@code PatchResult} captures the complete output of a single refactoring
 * application: the unified diff, AST hashes before and after, the set of affected
 * files, and arbitrary metadata for downstream verification.</p>
 *
 * <p>Instances are immutable and constructed via {@link Builder}.</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public final class PatchResult {

    private final UUID patchId;
    private final String candidateId;
    private final String unifiedDiff;
    private final String beforeAstHash;
    private final String afterAstHash;
    private final List<String> affectedFiles;
    private final Map<String, Object> metadata;
    private final Instant timestamp;
    private final boolean success;
    private final String errorMessage;

    private PatchResult(Builder builder) {
        this.patchId = builder.patchId != null ? builder.patchId : UUID.randomUUID();
        this.candidateId = builder.candidateId;
        this.unifiedDiff = Objects.requireNonNull(builder.unifiedDiff, "unifiedDiff must not be null");
        this.beforeAstHash = Objects.requireNonNull(builder.beforeAstHash, "beforeAstHash must not be null");
        this.afterAstHash = Objects.requireNonNull(builder.afterAstHash, "afterAstHash must not be null");
        this.affectedFiles = builder.affectedFiles != null
                ? List.copyOf(builder.affectedFiles) : List.of();
        this.metadata = builder.metadata != null
                ? Map.copyOf(builder.metadata) : Map.of();
        this.timestamp = builder.timestamp != null ? builder.timestamp : Instant.now();
        this.success = builder.success;
        this.errorMessage = builder.errorMessage;
    }

    // ── Accessors ────────────────────────────────────────────────────────

    /** @return unique patch identifier */
    @JsonProperty public UUID patchId() { return patchId; }

    /** @return identifier of the candidate that was applied, or null */
    @JsonProperty public String candidateId() { return candidateId; }

    /** @return unified diff representing the code transformation */
    @JsonProperty public String unifiedDiff() { return unifiedDiff; }

    /** @return SHA-256 hash of the AST structure before the refactoring */
    @JsonProperty public String beforeAstHash() { return beforeAstHash; }

    /** @return SHA-256 hash of the AST structure after the refactoring */
    @JsonProperty public String afterAstHash() { return afterAstHash; }

    /** @return unmodifiable list of files modified by this patch */
    @JsonProperty public List<String> affectedFiles() { return affectedFiles; }

    /** @return unmodifiable metadata map (e.g., tool version, rule details) */
    @JsonProperty public Map<String, Object> metadata() { return metadata; }

    /** @return timestamp when the patch was created */
    @JsonProperty public Instant timestamp() { return timestamp; }

    /** @return whether the patch was applied successfully */
    @JsonProperty public boolean success() { return success; }

    /** @return error message if the patch failed, or null on success */
    @JsonProperty public String errorMessage() { return errorMessage; }

    // ── Convenience methods ──────────────────────────────────────────────

    /**
     * Returns {@code true} if the AST hash changed, indicating a structural
     * modification was made.
     *
     * @return whether the AST was structurally modified
     */
    public boolean hasStructuralChange() {
        return !beforeAstHash.equals(afterAstHash);
    }

    /**
     * Returns the number of files affected by this patch.
     *
     * @return affected file count
     */
    public int affectedFileCount() {
        return affectedFiles.size();
    }

    /**
     * Creates a failed patch result with the given error message.
     *
     * @param candidateId the candidate that failed to apply
     * @param error       description of the failure
     * @return a new {@code PatchResult} representing a failure
     */
    public static PatchResult failure(String candidateId, String error) {
        return builder()
                .candidateId(candidateId)
                .unifiedDiff("")
                .beforeAstHash("N/A")
                .afterAstHash("N/A")
                .success(false)
                .errorMessage(Objects.requireNonNull(error, "error must not be null"))
                .build();
    }

    public static Builder builder() {
        return new Builder();
    }

    // ── Builder ──────────────────────────────────────────────────────────

    /**
     * Mutable builder for {@link PatchResult}.
     */
    public static final class Builder {
        private UUID patchId;
        private String candidateId;
        private String unifiedDiff;
        private String beforeAstHash;
        private String afterAstHash;
        private List<String> affectedFiles;
        private Map<String, Object> metadata;
        private Instant timestamp;
        private boolean success = true;
        private String errorMessage;

        private Builder() {}

        public Builder patchId(UUID patchId) { this.patchId = patchId; return this; }
        public Builder candidateId(String candidateId) { this.candidateId = candidateId; return this; }
        public Builder unifiedDiff(String unifiedDiff) { this.unifiedDiff = unifiedDiff; return this; }
        public Builder beforeAstHash(String beforeAstHash) { this.beforeAstHash = beforeAstHash; return this; }
        public Builder afterAstHash(String afterAstHash) { this.afterAstHash = afterAstHash; return this; }
        public Builder affectedFiles(List<String> affectedFiles) { this.affectedFiles = affectedFiles; return this; }
        public Builder addAffectedFile(String file) {
            if (this.affectedFiles == null) this.affectedFiles = new ArrayList<>();
            this.affectedFiles.add(Objects.requireNonNull(file));
            return this;
        }
        public Builder metadata(Map<String, Object> metadata) { this.metadata = metadata; return this; }
        public Builder putMetadata(String key, Object value) {
            if (this.metadata == null) this.metadata = new LinkedHashMap<>();
            this.metadata.put(key, value);
            return this;
        }
        public Builder timestamp(Instant timestamp) { this.timestamp = timestamp; return this; }
        public Builder success(boolean success) { this.success = success; return this; }
        public Builder errorMessage(String errorMessage) { this.errorMessage = errorMessage; return this; }

        /**
         * Builds an immutable {@link PatchResult}.
         *
         * @return new patch result instance
         */
        public PatchResult build() {
            return new PatchResult(this);
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof PatchResult that)) return false;
        return Objects.equals(patchId, that.patchId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(patchId);
    }

    @Override
    public String toString() {
        return "PatchResult{" +
                "patchId=" + patchId +
                ", success=" + success +
                ", affectedFiles=" + affectedFiles.size() +
                ", hasStructuralChange=" + hasStructuralChange() +
                '}';
    }
}
