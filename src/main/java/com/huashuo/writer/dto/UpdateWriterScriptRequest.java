package com.huashuo.writer.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 更新文案请求：用户手动调整改写文案后保存最新内容。
 */
public record UpdateWriterScriptRequest(
        @NotNull Long projectId,
        @NotBlank String finalScript
) {
}
