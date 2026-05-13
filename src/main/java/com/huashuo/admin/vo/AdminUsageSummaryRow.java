package com.huashuo.admin.vo;

import java.math.BigDecimal;

/**
 * 后台 AI 用量与积分成本统计报表一行：按 {@code dimension} 分组后的合计。
 * <p>{@code finalCreditCost} 遵循"优先取 ACTUAL.actual_credit_cost > 0；否则回退 ESTIMATE.estimated_credit_cost"
 * 的规则，便于运营对"实际成本是多少"一目了然。其余真实用量字段（tokens/characterCount/...）来自 ACTUAL 行求和。</p>
 */
public record AdminUsageSummaryRow(
        String groupKey,
        String groupLabel,
        long callCount,
        long estimatedCreditCost,
        long actualCreditCost,
        long finalCreditCost,
        long promptTokens,
        long completionTokens,
        long totalTokens,
        long characterCount,
        long imageCount,
        BigDecimal durationSeconds,
        BigDecimal providerCredits
) {
}
