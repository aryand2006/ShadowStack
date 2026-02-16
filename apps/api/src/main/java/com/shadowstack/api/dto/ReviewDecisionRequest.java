package com.shadowstack.api.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Request payload for submitting a review decision on a patch.
 */
public record ReviewDecisionRequest(

        @NotNull(message = "Decision (accepted) is required")
        Boolean accepted,

        @Size(max = 2000, message = "Reason must not exceed 2000 characters")
        String reason
) {
}
