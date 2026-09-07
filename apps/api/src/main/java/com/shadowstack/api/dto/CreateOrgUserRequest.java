package com.shadowstack.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

public record CreateOrgUserRequest(
        @NotBlank @Size(max = 128) String username,
        @NotBlank @Size(min = 8, max = 128) String password,
        @NotEmpty List<@NotBlank String> roles
) {
}
