package com.huashuo.user.vo.account;

import java.time.LocalDateTime;
import java.util.List;

public record TaskCreditDetailResponse(
        long taskId,
        String taskTitle,
        String taskType,
        String provider,
        String modelCode,
        String taskStatus,
        Long estimatedCreditCost,
        Long actualCreditCost,
        long paidCreditCost,
        long unpaidCreditCost,
        String settlementStatus,
        String settlementStatusLabel,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        LocalDateTime startedAt,
        LocalDateTime finishedAt,
        TaskCreditUsageSnapshot usage,
        List<TaskCreditStepRow> steps,
        List<String> creditExplanation,
        List<TaskCreditDetailLogLine> logs
) {
}
