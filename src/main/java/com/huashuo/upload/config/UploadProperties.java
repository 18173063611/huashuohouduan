package com.huashuo.upload.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "huashuo.upload")
public record UploadProperties(
        String localRoot,
        String previewPrefix,
        String publicBaseUrl,
        Boolean serveLocalPreview
) {
    public String effectivePreviewPrefix() {
        return previewPrefix == null || previewPrefix.isBlank() ? "/uploads" : previewPrefix;
    }

    public String effectivePublicBaseUrl() {
        if (publicBaseUrl == null || publicBaseUrl.isBlank()) {
            return "";
        }
        String value = publicBaseUrl.trim();
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }

    /** 是否在本地映射 /uploads/** （兼容历史）；默认 false，新部署统一走 TOS 公网 URL。 */
    public boolean effectiveServeLocalPreview() {
        return Boolean.TRUE.equals(serveLocalPreview);
    }
}
