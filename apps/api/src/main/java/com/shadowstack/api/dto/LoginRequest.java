package com.shadowstack.api.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Username/password login for issuing a JWT (demo + Basic fallback).
 */
public record LoginRequest(
        @NotBlank String username,
        @NotBlank String password
) {}
