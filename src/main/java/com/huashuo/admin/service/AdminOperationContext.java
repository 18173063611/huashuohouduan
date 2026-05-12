package com.huashuo.admin.service;

public record AdminOperationContext(
        Long adminUserId,
        String ip,
        String traceId
) {
}
