package com.huashuo.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record UserProfileUpdateRequest(
        @NotBlank
        @Size(max = 80)
        String displayName,

        @Size(max = 30)
        String phone,

        @Size(max = 120)
        String email,

        @Size(max = 500)
        String remark
) {
}
