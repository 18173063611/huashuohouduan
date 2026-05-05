package com.huashuo.writer.vo;

import java.time.LocalDateTime;

/**
 * 文案改写页面脚本视图：对应 /api/v1/writer/scripts/** 返回结构。
 */
public record WriterScriptItem(
        Long scriptId,
        Long projectId,
        Long parseId,
        Integer versionNo,
        String currentStep,
        String nextStep,
        String sourceScript,
        String finalScript,
        String rewriteStyle,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
