package com.huashuo.task.vo;

import java.time.LocalDateTime;
import java.math.BigDecimal;

/**
 * 任务 VO：供 Controller 与前端任务中心展示（字段与实现规划 §11 对齐）。
 */
public record TaskItem(
        Long taskId,
        Long projectId,
        Long ownerUserId,
        String taskType,
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
        String taskTitle,
        String status,
        Integer progress,
        Long resultAssetId,
        String errorCode,
        String errorMessage,
        Integer retryCount,
        Boolean resultViewed,
        String inputJson,
        String outputJson,
        String traceId,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        LocalDateTime startedAt,
        LocalDateTime finishedAt
) {
}
