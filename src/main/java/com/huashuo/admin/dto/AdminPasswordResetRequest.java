package com.huashuo.admin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AdminPasswordResetRequest(
        @NotBlank
        @Size(min = 6, max = 60)
        String password
) {
}
