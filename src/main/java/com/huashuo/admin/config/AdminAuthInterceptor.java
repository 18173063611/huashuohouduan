package com.huashuo.admin.config;

import com.huashuo.admin.service.AdminAccessService;
import com.huashuo.common.exception.BusinessException;
import com.huashuo.user.config.LoginAuthInterceptor;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.jetbrains.annotations.NotNull;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class AdminAuthInterceptor implements HandlerInterceptor {

    private final AdminAccessService adminAccessService;

    public AdminAuthInterceptor(AdminAccessService adminAccessService) {
        this.adminAccessService = adminAccessService;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, @NotNull HttpServletResponse response,
                             @NotNull Object handler) {
        if (HttpMethod.OPTIONS.matches(request.getMethod())) {
            return true;
        }
        Object currentUserId = request.getAttribute(LoginAuthInterceptor.CURRENT_USER_ID_ATTRIBUTE);
        if (!(currentUserId instanceof Long userId)) {
            throw new BusinessException(40100, "未登录或登录已过期");
        }
        // 管理员接口必须二次校验权限；以数据库 role/status 为主，配置白名单仅保留应急入口。
        adminAccessService.requireAdmin(userId);
        return true;
    }
}
