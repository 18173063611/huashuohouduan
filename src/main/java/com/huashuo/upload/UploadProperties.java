package com.huashuo.upload;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "huashuo.upload")
public record UploadProperties(
        String localRoot,
        String previewPrefix
) {
}
