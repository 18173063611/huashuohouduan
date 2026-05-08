package com.huashuo.video.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "vidu.digital-human")
public record ViduDigitalHumanProperties(
        String apiKey,
        String baseUrl,
        String model,
        String resolution,
        long pollIntervalSeconds,
        long pollTimeoutSeconds
) {
    public String effectiveBaseUrl() {
        return baseUrl == null || baseUrl.isBlank() ? "https://api.vidu.cn" : trimTrailingSlash(baseUrl);
    }

    public String effectiveModel() {
        return model == null || model.isBlank() ? "viduq2-turbo" : model;
    }

    public String effectiveResolution() {
        return resolution == null || resolution.isBlank() ? "720p" : resolution;
    }

    public long effectivePollIntervalSeconds() {
        return Math.max(2L, pollIntervalSeconds);
    }

    public long effectivePollTimeoutSeconds() {
        return Math.max(60L, pollTimeoutSeconds);
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
