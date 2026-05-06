package com.huashuo.avatar.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 火山方舟图片生成配置，用于 Doubao Seedream 数字人形象生成。
 */
@ConfigurationProperties(prefix = "volcengine.image")
public record VolcengineImageProperties(
        String baseUrl,
        String apiKey,
        String model,
        String defaultSize,
        Boolean watermark
) {
    public String effectiveBaseUrl() {
        return baseUrl == null || baseUrl.isBlank()
                ? "https://ark.cn-beijing.volces.com/api/v3"
                : trimTrailingSlash(baseUrl);
    }

    public String effectiveModel() {
        return model == null || model.isBlank() ? "doubao-seedream-5-0-260128" : model;
    }

    public String effectiveDefaultSize() {
        return defaultSize == null || defaultSize.isBlank() ? "2K" : defaultSize;
    }

    public boolean effectiveWatermark() {
        return watermark == null || watermark;
    }

    public boolean configured() {
        return apiKey != null && !apiKey.isBlank();
    }

    private String trimTrailingSlash(String value) {
        String result = value.trim();
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }
}
