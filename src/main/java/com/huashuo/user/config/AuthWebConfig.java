package com.huashuo.user.config;

import com.huashuo.admin.config.AdminAuthInterceptor;
import java.util.Arrays;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class AuthWebConfig implements WebMvcConfigurer {

    private final LoginAuthInterceptor loginAuthInterceptor;
    private final AdminAuthInterceptor adminAuthInterceptor;
    private final String corsAllowedOrigins;

    public AuthWebConfig(
            LoginAuthInterceptor loginAuthInterceptor,
            AdminAuthInterceptor adminAuthInterceptor,
            @Value("${huashuo.cors.allowed-origins:http://localhost:5173,http://127.0.0.1:5173}") String corsAllowedOrigins) {
        this.loginAuthInterceptor = loginAuthInterceptor;
        this.adminAuthInterceptor = adminAuthInterceptor;
        this.corsAllowedOrigins = corsAllowedOrigins;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        String[] allowedOriginPatterns = Arrays.stream(corsAllowedOrigins.split(","))
                .map(String::trim)
                .filter(origin -> !origin.isEmpty())
                .toArray(String[]::new);

        registry.addMapping("/api/**")
                .allowedOriginPatterns(allowedOriginPatterns)
                .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .exposedHeaders("Content-Disposition")
                .maxAge(3600);
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(loginAuthInterceptor)
                .addPathPatterns("/api/**")
                .excludePathPatterns(
                        "/api/v1/auth/login",
                        "/api/v1/auth/register",
                        "/api/v1/credits/task-quote",
                        "/api/v1/billing/estimate"
                );
        registry.addInterceptor(adminAuthInterceptor)
                .addPathPatterns("/api/v1/admin/**");
    }
}
