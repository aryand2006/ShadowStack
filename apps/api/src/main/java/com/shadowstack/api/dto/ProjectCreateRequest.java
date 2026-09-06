package com.shadowstack.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Request payload for creating a new ShadowStack project.
 * Accepts a Git URL or a local filesystem path (for demos / air-gapped scans).
 */
public record ProjectCreateRequest(

        @NotBlank(message = "Project name is required")
        @Size(min = 1, max = 255, message = "Project name must be between 1 and 255 characters")
        String name,

        @Size(max = 1000, message = "Description must not exceed 1000 characters")
        String description,

        @NotBlank(message = "Repository URL or local path is required")
        @Pattern(
                regexp = "^(https?://|git@|ssh://|file://|\\.?/?|[A-Za-z]:\\\\).*$",
                message = "Must be a Git URL or local filesystem path"
        )
        String repositoryUrl,

        @Pattern(
                regexp = "^[a-zA-Z0-9._/-]+$",
                message = "Branch name contains invalid characters"
        )
        String branch,

        @NotBlank(message = "Source language is required")
        String sourceLanguage,

        String targetLanguageVersion
) {
    public ProjectCreateRequest {
        if (branch == null || branch.isBlank()) {
            branch = "main";
        }
    }
}
