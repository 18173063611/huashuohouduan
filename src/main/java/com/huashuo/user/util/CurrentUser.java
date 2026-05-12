package com.huashuo.user.util;

import com.huashuo.user.config.LoginAuthInterceptor;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.OptionalLong;

public final class CurrentUser {

    private CurrentUser() {
    }

    public static OptionalLong optionalUserId() {
        ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attributes == null) {
            return OptionalLong.empty();
        }
        Object userId = attributes.getRequest().getAttribute(LoginAuthInterceptor.CURRENT_USER_ID_ATTRIBUTE);
        return userId instanceof Number number ? OptionalLong.of(number.longValue()) : OptionalLong.empty();
    }

    public static Long nullableUserId() {
        OptionalLong userId = optionalUserId();
        return userId.isPresent() ? userId.getAsLong() : null;
    }
}
