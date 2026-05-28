package com.huashuo.user.security;

import com.huashuo.common.exception.BusinessException;
import org.springframework.util.StringUtils;

public enum AuthClientType {
    USER_WEB,
    ADMIN_WEB;

    public static AuthClientType from(String value) {
        if (!StringUtils.hasText(value)) {
            return USER_WEB;
        }
        String normalized = value.trim().toUpperCase();
        for (AuthClientType type : values()) {
            if (type.name().equals(normalized)) {
                return type;
            }
        }
        throw new BusinessException(40000, "Unsupported clientType");
    }
}
