package com.huashuo.billing.model;

import java.math.BigDecimal;

/**
 * 统一的预估请求。所有字段都可选，最少需要 {@code taskType}：调用方有什么就传什么。
 *
 * <p>大多数任务按 {@code taskType} 对应的 {@code ai_billing_step_config} 汇总预估；
 * TTS / 试听 / 形象生成等用量明确的任务会使用 {@code inputTextLength}、{@code imageCount}
 * 等字段按模型单价动态预估；汽车销售成片会使用 {@code segmentCount} 按段数预估。
 * 这些字段必须与 {@code TaskService.createTask} 的预扣逻辑保持一致，避免出现展示 5 实扣 20
 * 或预扣后又大额退款的双源冲突。</p>
 */
public record BillingEstimateRequest(
        String taskType,
        String modelCode,
        String usageUnit,
        Integer inputTextLength,
        Integer imageCount,
        Integer segmentCount,
        BigDecimal durationSeconds,
        BigDecimal providerCredits,
        Long ownerUserId
) {
}
