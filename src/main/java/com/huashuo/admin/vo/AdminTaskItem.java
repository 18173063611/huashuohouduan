package com.huashuo.admin.vo;

import java.time.LocalDateTime;

public record AdminTaskItem(
        Long taskId,
        Long ownerUserId,
        String taskType,
        String status,
        Integer progress,
        String modelCode,
        Long creditCost,
        Long creditLogId,
        String queueName,
        String messageId,
        String errorCode,
        String errorMessage,
        String traceId,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        LocalDateTime startedAt,
        LocalDateTime finishedAt
) {
}
