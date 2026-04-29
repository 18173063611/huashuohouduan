package com.huashuo.upload.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "huashuo.upload")
public record UploadProperties(
        String localRoot,
        String previewPrefix
) {
}
