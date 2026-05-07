package com.huashuo.user.util;

/**
 * 从请求头解析 Bearer / X-Auth-Token，供各 Controller 复用。
 */
public final class AuthHeaderParser {

    private AuthHeaderParser() {
    }

    public static String resolveBearer(String authorization, String xAuthToken) {
        if (xAuthToken != null && !xAuthToken.isBlank()) {
            return xAuthToken.trim();
        }
        if (authorization == null) {
            return null;
        }
        String v = authorization.trim();
        if (v.regionMatches(true, 0, "Bearer ", 0, 7)) {
            String raw = v.substring(7).trim();
            return raw.isEmpty() ? null : raw;
        }
        return v.isEmpty() ? null : v;
    }
}
