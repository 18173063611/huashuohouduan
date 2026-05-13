package com.huashuo.billing.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 单任务的用量汇总：把同一 {@code task_id} 的 ESTIMATE / ACTUAL 两条 {@code ai_usage_log} 折叠为 1 行。
 *
 * <p>{@code estimatedCreditCost} 取 ESTIMATE 行；{@code actualCreditCost} 取 ACTUAL 行（usage_phase=ACTUAL 且
 * {@code actual_credit_cost > 0} 才计入）。维度字段（taskType / provider / modelCode / usageUnit）来自任一 phase；
 * 真实用量字段（tokens / characterCount / imageCount / durationSeconds / providerCredits）只在 ACTUAL 行求和，
 * 避免 ESTIMATE 占位行被重复计入。</p>
 */
public record UsageSummaryPerTaskRow(
        Long taskId,
        LocalDate reportDate,
        String taskType,
        String provider,
        String modelCode,
        String usageUnit,
        Long estimatedCreditCost,
        Long actualCreditCost,
        Long promptTokens,
        Long completionTokens,
        Long totalTokens,
        Long characterCount,
        Long imageCount,
        BigDecimal durationSeconds,
        BigDecimal providerCredits
) {
}
