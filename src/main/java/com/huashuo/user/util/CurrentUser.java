package com.huashuo.user.util;

import com.huashuo.user.config.LoginAuthInterceptor;
import com.huashuo.user.security.CustomUserDetails;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.OptionalLong;

public final class CurrentUser {

    private CurrentUser() {
    }

    public static OptionalLong optionalUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof CustomUserDetails user) {
            return OptionalLong.of(user.userId());
        }
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
