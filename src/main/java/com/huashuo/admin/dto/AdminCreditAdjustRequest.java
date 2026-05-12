package com.huashuo.admin.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record AdminCreditAdjustRequest(
        @NotBlank
        String changeType,

        @NotNull
        @Min(1)
        Long amount,

        @NotBlank
        @Size(max = 500)
        String remark
) {
}
