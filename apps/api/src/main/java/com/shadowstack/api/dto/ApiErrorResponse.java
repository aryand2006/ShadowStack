package com.shadowstack.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;

/**
 * Standard error response envelope for all API errors.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiErrorResponse(
        int status,
        String error,
        String message,
        String path,
        Instant timestamp,
        List<FieldError> fieldErrors,
        String traceId
) {

    public record FieldError(
            String field,
            String message,
            Object rejectedValue
    ) {}

    public static ApiErrorResponse of(int status, String error, String message, String path) {
        return new ApiErrorResponse(status, error, message, path, Instant.now(), null, null);
    }

    public static ApiErrorResponse withFieldErrors(int status, String error, String message,
                                                    String path, List<FieldError> fieldErrors) {
        return new ApiErrorResponse(status, error, message, path, Instant.now(), fieldErrors, null);
    }

    public static ApiErrorResponse withTrace(int status, String error, String message,
                                              String path, String traceId) {
        return new ApiErrorResponse(status, error, message, path, Instant.now(), null, traceId);
    }
}
