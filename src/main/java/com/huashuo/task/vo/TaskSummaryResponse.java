package com.huashuo.task.vo;

import java.util.List;

/**
 * 任务中心汇总：处理中 / 成功 / 失败数量 + 近期任务列表。
 */
public record TaskSummaryResponse(
        long processingCount,
        long successCount,
        long failedCount,
        List<TaskItem> records
) {
}
