package com.huashuo.admin.vo;

import java.time.LocalDateTime;
import java.math.BigDecimal;

public record AdminTaskItem(
        Long taskId,
        Long ownerUserId,
        String taskType,
        String status,
        Integer progress,
        String modelCode,
        String provider,
        String usageUnit,
        BigDecimal estimatedUsage,
        BigDecimal actualUsage,
        Long estimatedCreditCost,
        Long actualCreditCost,
        String settlementStatus,
        Long creditCost,
        Long creditLogId,
        String queueName,
        String messageId,
        String errorCode,
        String errorMessage,
        AdminTaskProviderOps providerOps,
        String traceId,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        LocalDateTime startedAt,
        LocalDateTime finishedAt
) {
}
