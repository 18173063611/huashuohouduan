package com.huashuo.user.vo.account;

import java.math.BigDecimal;

public record TaskCreditUsageSnapshot(
        Integer promptTokens,
        Integer completionTokens,
        Integer totalTokens,
        Integer characterCount,
        Integer imageCount,
        BigDecimal durationSeconds,
        BigDecimal providerCredits,
        String usagePhase
) {
}
