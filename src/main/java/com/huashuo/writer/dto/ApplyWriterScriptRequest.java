package com.huashuo.writer.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 应用文案请求：保存用户确认后的最终文案，供后续流程继续使用。
 */
public record ApplyWriterScriptRequest(
        @NotNull Long projectId,
        Long parseId,
        @NotBlank String sourceScript,
        @NotBlank String finalScript,
        String rewriteStyle
) {
}
