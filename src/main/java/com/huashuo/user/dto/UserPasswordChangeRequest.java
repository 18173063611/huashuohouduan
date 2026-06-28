package com.huashuo.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record UserPasswordChangeRequest(
        @NotBlank
        String currentPassword,

        @NotBlank
        @Size(min = 6, max = 60)
        String newPassword
) {
}
