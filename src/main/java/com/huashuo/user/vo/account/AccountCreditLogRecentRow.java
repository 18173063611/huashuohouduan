package com.huashuo.user.vo.account;

import java.time.LocalDateTime;

/**
 * 账户中心「最近积分消费」单行：对应 {@code user_credit_log} 面向用户的可读视图。
 */
public record AccountCreditLogRecentRow(
        long creditLogId,
        LocalDateTime createdAt,
        String taskName,
        String taskType,
        /** 机器可读：PRECHARGE / SETTLE_EXTRA / REFUND / SETTLE_REFUND / DEBT / OTHER */
        String operationType,
        /** 中文：预扣、补扣、退款 等 */
        String operationLabel,
        long changeAmount,
        long afterBalance,
        String status
) {
}
