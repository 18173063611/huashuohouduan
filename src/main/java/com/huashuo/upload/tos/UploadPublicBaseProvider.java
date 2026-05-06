package com.huashuo.upload.tos;

import com.huashuo.upload.config.UploadProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 豆包图生图等场景需要的公网访问基址：优先 {@code huashuo.upload.public-base-url}；
 * 否则使用 {@code volcengine.tos.public-base-url}（与是否开启 TOS 同步上传无关，仅用于把 {@code /uploads/...} 拼成公网 URL）。
 * 注意：若未开启 {@code volcengine.tos.enabled}，文件不会自动写入 Bucket，豆包仍可能因对象不存在而失败。
 */
@Component
public class UploadPublicBaseProvider {

    private final UploadProperties uploadProperties;
    private final VolcengineTosProperties tosProperties;

    public UploadPublicBaseProvider(UploadProperties uploadProperties, VolcengineTosProperties tosProperties) {
        this.uploadProperties = uploadProperties;
        this.tosProperties = tosProperties;
    }

    public String effectivePublicBaseUrl() {
        String primary = uploadProperties.effectivePublicBaseUrl();
        if (StringUtils.hasText(primary)) {
            return primary;
        }
        if (StringUtils.hasText(tosProperties.publicBaseUrl())) {
            return trimTrailingSlashes(tosProperties.publicBaseUrl().trim());
        }
        return "";
    }

    private static String trimTrailingSlashes(String value) {
        String v = value;
        while (v.endsWith("/")) {
            v = v.substring(0, v.length() - 1);
        }
        return v;
    }
}
