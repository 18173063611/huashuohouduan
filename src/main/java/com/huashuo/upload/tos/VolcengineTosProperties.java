package com.huashuo.upload.tos;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 火山引擎 TOS：用于把上传文件同步到对象存储，供豆包等外网服务通过公网 URL 访问。
 */
@ConfigurationProperties(prefix = "volcengine.tos")
public record VolcengineTosProperties(
        boolean enabled,
        String region,
        String endpoint,
        String bucket,
        String publicBaseUrl,
        String accessKeyId,
        String secretAccessKey
) {
}
