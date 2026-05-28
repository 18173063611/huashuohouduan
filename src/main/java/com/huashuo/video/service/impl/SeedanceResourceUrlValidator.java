package com.huashuo.video.service.impl;

import com.huashuo.common.exception.BusinessException;
import com.huashuo.storage.resolve.StoredUrlResolver;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.net.URI;

@Component
public class SeedanceResourceUrlValidator {

    private final StoredUrlResolver storedUrlResolver;

    public SeedanceResourceUrlValidator(StoredUrlResolver storedUrlResolver) {
        this.storedUrlResolver = storedUrlResolver;
    }

    public String resolveImageUrl(String rawUrl) {
        return resolveModelAccessibleUrl(rawUrl, "图片素材");
    }

    public String resolveAudioUrl(String rawUrl) {
        return resolveModelAccessibleUrl(rawUrl, "音频素材");
    }

    private String resolveModelAccessibleUrl(String rawUrl, String label) {
        if (!StringUtils.hasText(rawUrl)) {
            throw new BusinessException(40000, label + "地址不能为空");
        }
        String original = rawUrl.trim();
        String resolved = storedUrlResolver.resolveToPublicUrl(original);
        if (!StringUtils.hasText(resolved)) {
            throw new BusinessException(40000, label + "地址不能为空");
        }
        String value = resolved.trim();
        URI uri;
        try {
            uri = URI.create(value);
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(40000, label + "地址格式不正确，无法交给视频模型下载：" + abbreviateUrl(original));
        }
        String scheme = uri.getScheme();
        String host = uri.getHost();
        if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
            throw new BusinessException(40000, label + "必须是可公网访问的 http/https 地址：" + abbreviateUrl(original));
        }
        if (!StringUtils.hasText(host) || isPrivateOrLocalHost(host)) {
            throw new BusinessException(40000,
                    label + "不是可公网访问地址，视频模型无法下载。请先上传到资产中心/TOS 后再生成：" + abbreviateUrl(original));
        }
        return value;
    }

    private boolean isPrivateOrLocalHost(String host) {
        if (!StringUtils.hasText(host)) {
            return true;
        }
        String h = host.trim().toLowerCase();
        if ("localhost".equals(h) || h.endsWith(".localhost") || h.endsWith(".local")
                || "::1".equals(h) || "0:0:0:0:0:0:0:1".equals(h)) {
            return true;
        }
        if (h.startsWith("127.") || h.startsWith("10.") || h.startsWith("0.") || h.startsWith("169.254.")) {
            return true;
        }
        String[] parts = h.split("\\.");
        if (parts.length == 4) {
            try {
                int first = Integer.parseInt(parts[0]);
                int second = Integer.parseInt(parts[1]);
                if (first == 192 && second == 168) {
                    return true;
                }
                return first == 172 && second >= 16 && second <= 31;
            } catch (NumberFormatException ignored) {
                return false;
            }
        }
        return false;
    }

    private String abbreviateUrl(String url) {
        if (!StringUtils.hasText(url)) {
            return "";
        }
        String value = url.trim();
        return value.length() <= 180 ? value : value.substring(0, 177) + "...";
    }
}
