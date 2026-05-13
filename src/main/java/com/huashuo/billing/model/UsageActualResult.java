package com.huashuo.billing.model;

import java.math.BigDecimal;

public record UsageActualResult(
        String provider,
        String modelCode,
        String usageUnit,
        Integer promptTokens,
        Integer completionTokens,
        Integer totalTokens,
        Integer characterCount,
        Integer imageCount,
        BigDecimal durationSeconds,
        BigDecimal providerCredits,
        Long actualCreditCost,
        String rawUsageJson
) {
}
