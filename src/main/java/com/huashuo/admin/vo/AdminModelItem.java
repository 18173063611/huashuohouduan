package com.huashuo.admin.vo;

import java.time.LocalDateTime;

public record AdminModelItem(
        Long modelId,
        String modelCode,
        String modelName,
        String modelType,
        String provider,
        String providerModel,
        Long creditCost,
        Boolean enabled,
        Boolean defaultModel,
        String capabilityJson,
        String defaultParamsJson,
        Integer rateLimitPerMinute,
        Integer concurrencyLimit,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
