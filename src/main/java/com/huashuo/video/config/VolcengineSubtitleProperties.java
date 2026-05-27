package com.huashuo.video.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "volcengine.subtitle")
public record VolcengineSubtitleProperties(
        boolean enabled,
        String appId,
        String accessToken,
        String submitUrl,
        String queryUrl,
        String language,
        int wordsPerLine,
        int maxLines,
        boolean useItn,
        boolean useCapitalize,
        boolean usePunc,
        String captionType,
        long pollIntervalSeconds,
        long pollTimeoutSeconds
) {
    public String effectiveSubmitUrl() {
        return hasText(submitUrl) ? submitUrl.trim() : "https://openspeech.bytedance.com/api/v1/vc/submit";
    }

    public String effectiveQueryUrl() {
        return hasText(queryUrl) ? queryUrl.trim() : "https://openspeech.bytedance.com/api/v1/vc/query";
    }

    public String effectiveLanguage() {
        return hasText(language) ? language.trim() : "zh-CN";
    }

    public int effectiveWordsPerLine() {
        return wordsPerLine > 0 ? wordsPerLine : 15;
    }

    public int effectiveMaxLines() {
        return maxLines > 0 ? maxLines : 1;
    }

    public String effectiveCaptionType() {
        return hasText(captionType) ? captionType.trim() : "speech";
    }

    public long effectivePollIntervalSeconds() {
        return Math.max(1L, pollIntervalSeconds);
    }

    public long effectivePollTimeoutSeconds() {
        return pollTimeoutSeconds > 0 ? Math.max(30L, pollTimeoutSeconds) : 600L;
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
