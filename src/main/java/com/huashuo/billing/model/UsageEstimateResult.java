package com.huashuo.billing.model;

import java.math.BigDecimal;

public record UsageEstimateResult(
        String provider,
        String modelCode,
        String usageUnit,
        BigDecimal estimatedUsage,
        Integer estimatedPromptTokens,
        Integer estimatedCompletionTokens,
        long estimatedCreditCost
) {
}
