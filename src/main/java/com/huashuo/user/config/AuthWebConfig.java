package com.huashuo.user.config;

import com.huashuo.admin.config.AdminAuthInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class AuthWebConfig implements WebMvcConfigurer {

    private final LoginAuthInterceptor loginAuthInterceptor;
    private final AdminAuthInterceptor adminAuthInterceptor;

    public AuthWebConfig(LoginAuthInterceptor loginAuthInterceptor, AdminAuthInterceptor adminAuthInterceptor) {
        this.loginAuthInterceptor = loginAuthInterceptor;
        this.adminAuthInterceptor = adminAuthInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(loginAuthInterceptor)
                .addPathPatterns("/api/**")
                .excludePathPatterns(
                        "/api/v1/auth/login",
                        "/api/v1/auth/register"
                );
        registry.addInterceptor(adminAuthInterceptor)
                .addPathPatterns("/api/v1/admin/**");
    }
}
