package com.huashuo.user.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class AuthWebConfig implements WebMvcConfigurer {

    private final LoginAuthInterceptor loginAuthInterceptor;

    public AuthWebConfig(LoginAuthInterceptor loginAuthInterceptor) {
        this.loginAuthInterceptor = loginAuthInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(loginAuthInterceptor)
                .addPathPatterns("/api/**")
                .excludePathPatterns(
                        "/api/v1/auth/login",
                        "/api/v1/auth/register"
                );
    }
}
