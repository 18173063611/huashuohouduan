package com.huashuo.admin.vo;

import java.math.BigDecimal;

/**
 * 后台 AI 用量与积分成本统计报表一行：按 {@code dimension} 分组后的合计。
 * <p>{@code finalCreditCost} 遵循"优先取 ACTUAL.actual_credit_cost > 0；否则回退 ESTIMATE.estimated_credit_cost"
 * 的规则；{@code unpaidCreditCost} 来自 {@code credit_debt_log} 的 {@code SETTLEMENT_EXTRA} 未结金额，
 * {@code paidCreditCost = finalCreditCost - unpaidCreditCost}，便于一行看清"该口径实际收到多少 / 还欠多少"。</p>
 */
public record AdminUsageSummaryRow(
        String groupKey,
        String groupLabel,
        long callCount,
        long estimatedCreditCost,
        long actualCreditCost,
        long finalCreditCost,
        long paidCreditCost,
        long unpaidCreditCost,
        long debtTaskCount,
        long promptTokens,
        long completionTokens,
        long totalTokens,
        long characterCount,
        long imageCount,
        BigDecimal durationSeconds,
        BigDecimal providerCredits
) {
}
