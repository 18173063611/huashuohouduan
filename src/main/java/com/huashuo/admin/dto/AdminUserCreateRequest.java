package com.huashuo.admin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AdminUserCreateRequest(
        @NotBlank
        @Size(max = 60)
        String username,

        @NotBlank
        @Size(min = 6, max = 60)
        String password,

        @NotBlank
        @Size(max = 80)
        String displayName,

        String role,

        String status,

        @Size(max = 30)
        String phone,

        @Size(max = 120)
        String email,

        @Size(max = 500)
        String remark
) {
}
