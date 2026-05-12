package com.huashuo.admin.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record AdminModelSaveRequest(
        @NotBlank
        @Size(max = 80)
        String modelCode,

        @NotBlank
        @Size(max = 120)
        String modelName,

        @NotBlank
        @Size(max = 30)
        String modelType,

        @NotBlank
        @Size(max = 50)
        String provider,

        @Size(max = 120)
        String providerModel,

        @NotNull
        @Min(0)
        Long creditCost,

        Boolean enabled,

        Boolean defaultModel,

        String capabilityJson,

        String defaultParamsJson,

        Integer rateLimitPerMinute,

        Integer concurrencyLimit
) {
}
