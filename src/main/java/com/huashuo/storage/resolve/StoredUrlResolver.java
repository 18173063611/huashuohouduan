package com.huashuo.storage.resolve;

import com.huashuo.upload.config.UploadProperties;
import com.huashuo.upload.tos.VolcengineTosProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 将库内多种形态的访问路径解析为可给前端使用的 URL（历史 /uploads、完整 https、纯 objectKey）。
 */
@Component
public class StoredUrlResolver {

    private static final String LEGACY_PREFIX = "/uploads/";

    private final UploadProperties uploadProperties;
    private final VolcengineTosProperties tosProperties;

    public StoredUrlResolver(UploadProperties uploadProperties, VolcengineTosProperties tosProperties) {
        this.uploadProperties = uploadProperties;
        this.tosProperties = tosProperties;
    }

    /**
     * 解析为浏览器或第三方可直接访问的 URL；无法解析时返回原字符串。
     */
    public String resolveToPublicUrl(String stored) {
        if (!StringUtils.hasText(stored)) {
            return stored;
        }
        String t = stored.trim();
        if (t.startsWith("https://") || t.startsWith("http://")) {
            return t;
        }
        if (t.startsWith("tos:")) {
            return joinPublicBase(t.substring(4));
        }
        if (t.startsWith(LEGACY_PREFIX)) {
            if (uploadProperties.effectiveServeLocalPreview()) {
                return t;
            }
            return joinPublicBase(t.substring(1));
        }
        if (t.startsWith("/api/")) {
            return t;
        }
        if (!t.contains("://") && !t.startsWith("/") && looksLikeObjectKey(t)) {
            return joinPublicBase(t);
        }
        String primary = uploadProperties.effectivePublicBaseUrl();
        if (StringUtils.hasText(primary) && t.startsWith("/")) {
            return trimSlash(primary) + t;
        }
        return stored;
    }

    private boolean looksLikeObjectKey(String t) {
        return t.matches("[a-z]+[a-z0-9_-]*/\\d{4}/\\d{2}/\\d{2}/[a-zA-Z0-9_.-]+");
    }

    private String joinPublicBase(String keyOrPath) {
        String base = uploadProperties.effectivePublicBaseUrl();
        if (!StringUtils.hasText(base) && StringUtils.hasText(tosProperties.publicBaseUrl())) {
            base = trimSlash(tosProperties.publicBaseUrl().trim());
        }
        if (!StringUtils.hasText(base)) {
            return storedFallback(keyOrPath);
        }
        String k = keyOrPath.startsWith("/") ? keyOrPath.substring(1) : keyOrPath;
        return trimSlash(base) + "/" + k;
    }

    private String storedFallback(String keyOrPath) {
        return keyOrPath.startsWith("/") ? keyOrPath : "/" + keyOrPath;
    }

    private static String trimSlash(String base) {
        String v = base;
        while (v.endsWith("/")) {
            v = v.substring(0, v.length() - 1);
        }
        return v;
    }
}
