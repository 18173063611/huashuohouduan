package com.huashuo.user.vo;

/**
 * 按任务类型查询预计扣费（与 {@link com.huashuo.task.config.TaskCreditProperties} 一致，最终以提交时后端为准）。
 */
public record TaskCreditQuoteResponse(
        String taskType,
        long creditCost,
        String modelCode
) {
}
