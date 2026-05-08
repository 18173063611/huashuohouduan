package com.huashuo.user.config;

import com.huashuo.user.service.UserAuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.jetbrains.annotations.NotNull;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class LoginAuthInterceptor implements HandlerInterceptor {

    public static final String CURRENT_USER_ID_ATTRIBUTE = "currentUserId";

    private final UserAuthService userAuthService;

    public LoginAuthInterceptor(UserAuthService userAuthService) {
        this.userAuthService = userAuthService;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, @NotNull HttpServletResponse response, @NotNull Object handler) {
        if (HttpMethod.OPTIONS.matches(request.getMethod())) {
            return true;
        }

        long userId = userAuthService.requireUserId(
                request.getHeader("Authorization"),
                request.getHeader("X-Auth-Token")
        );
        request.setAttribute(CURRENT_USER_ID_ATTRIBUTE, userId);
        return true;
    }
}
