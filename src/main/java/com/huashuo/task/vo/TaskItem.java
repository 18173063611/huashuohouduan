package com.huashuo.task.vo;

import java.time.LocalDateTime;

public record TaskItem(
        Long taskId,
        Long projectId,
        String taskType,
        String status,
        String inputJson,
        String outputJson,
        Integer retryCount,
        String errorMessage,
        String traceId,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
