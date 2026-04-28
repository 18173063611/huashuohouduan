package com.huashuo.upload;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "huashuo.fwx-upload")
public record FwxUploadProperties(
        String localRoot,
        String previewPrefix
) {
}
