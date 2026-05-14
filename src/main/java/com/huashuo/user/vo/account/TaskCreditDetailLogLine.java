package com.huashuo.user.vo.account;

import java.time.LocalDateTime;

public record TaskCreditDetailLogLine(
        LocalDateTime createdAt,
        String changeType,
        String operationType,
        String operationLabel,
        long changeAmount,
        long beforeBalance,
        long afterBalance,
        String idempotencyKey,
        String remark
) {
}
