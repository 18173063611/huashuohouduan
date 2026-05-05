package com.huashuo.voice.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 火山引擎「异步长文本语音合成」HTTP 接口配置（openspeech.bytedance.com）。
 * 文档：https://www.volcengine.com/docs/6561/1829010 （与项目根目录 api.md 中语音合成链接一致）
 */
@ConfigurationProperties(prefix = "volcengine.tts")
public record VolcengineTtsProperties(
        String appId,
        String accessKey,
        String resourceId,
        String submitUrl,
        String queryUrl
) {
    public String effectiveResourceId() {
        return resourceId == null || resourceId.isBlank() ? "seed-tts-2.0" : resourceId;
    }

    public String effectiveSubmitUrl() {
        return submitUrl == null || submitUrl.isBlank()
                ? "https://openspeech.bytedance.com/api/v3/tts/submit"
                : submitUrl;
    }

    public String effectiveQueryUrl() {
        return queryUrl == null || queryUrl.isBlank()
                ? "https://openspeech.bytedance.com/api/v3/tts/query"
                : queryUrl;
    }

    public boolean configured() {
        return appId != null && !appId.isBlank()
                && accessKey != null && !accessKey.isBlank();
    }
}
