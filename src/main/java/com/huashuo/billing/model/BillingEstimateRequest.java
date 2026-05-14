package com.huashuo.billing.model;

import java.math.BigDecimal;

/**
 * 统一的预估请求。所有字段都可选，最少需要 {@code taskType}：调用方有什么就传什么。
 *
 * <p>第一版的预估金额只看 {@code taskType}（由 {@code ai_billing_step_config} 汇总），
 * 其余字段透传给 {@link com.huashuo.billing.service.UsageEstimateService} 以便回填 provider /
 * modelCode / usageUnit / 估算 usage 等元数据；不影响最终扣费金额。这样可以保证
 * "前端展示金额 == createTask 预扣金额"，避免出现展示 5 实扣 20 的双源冲突。</p>
 */
public record BillingEstimateRequest(
        String taskType,
        String modelCode,
        String usageUnit,
        Integer inputTextLength,
        Integer imageCount,
        BigDecimal durationSeconds,
        BigDecimal providerCredits,
        Long ownerUserId
) {
}
