package com.huashuo.admin.vo;

import java.time.LocalDateTime;

public record AdminOperationLogItem(
        Long operationId,
        Long adminUserId,
        String operationType,
        String targetType,
        Long targetId,
        String beforeJson,
        String afterJson,
        String ip,
        String traceId,
        LocalDateTime createdAt
) {
}
