package com.huashuo.script.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record RewriteScriptRequest(
        @NotNull Long projectId,
        @NotBlank String sourceText,
        @NotBlank String style,
        @NotNull @Positive Integer targetLength
) {
}
