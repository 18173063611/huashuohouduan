package com.huashuo.task.vo;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 汽车销售一键成片内测批次复盘报告。
 */
public record CarSalesTestBatchReport(
        String testBatch,
        Long projectId,
        int totalCount,
        int sampleCount,
        int quickRenderTaskCount,
        int generationTaskCount,
        int successCount,
        int failedCount,
        int processingCount,
        int retryableCount,
        Map<String, Long> failureCategoryCounts,
        List<SampleItem> samples
) {
    public record SampleItem(
            Long taskId,
            Long projectId,
            Long ownerUserId,
            String taskType,
            String taskTitle,
            String status,
            Integer progress,
            Long resultAssetId,
            String sampleId,
            String outputPurpose,
            String errorCategory,
            String failureType,
            String failureReason,
            String traceId,
            String errorCode,
            String errorMessage,
            Boolean retryable,
            LocalDateTime createdAt,
            LocalDateTime updatedAt,
            LocalDateTime startedAt,
            LocalDateTime finishedAt
    ) {
    }
}
