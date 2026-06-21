package com.huashuo.common.ai;

public record ArkChatResult(
        String content,
        String model,
        Integer promptTokens,
        Integer completionTokens,
        Integer totalTokens,
        String rawUsageJson
) {
}
