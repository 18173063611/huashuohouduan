package com.huashuo.video.config;

import com.volcengine.ark.runtime.service.ArkService;
import okhttp3.ConnectionPool;
import okhttp3.Dispatcher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.util.StringUtils;

import java.util.concurrent.TimeUnit;

/**
 * Volcengine Ark Runtime SDK client configuration for Seedance video generation.
 */
@Configuration
public class ArkServiceConfig {

    @Bean(destroyMethod = "shutdownExecutor")
    public ArkService seedanceArkService(Environment environment) {
        String apiKey = firstText(
                environment.getProperty("volcengine.seedance.api-key"),
                environment.getProperty("volcengine.ark.api-key"),
                environment.getProperty("VOLCENGINE_SEEDANCE_API_KEY"),
                environment.getProperty("VOLCENGINE_ARK_API_KEY")
        );
        if (!StringUtils.hasText(apiKey)) {
            throw new IllegalStateException(
                    "Volcengine Seedance api key is not configured; set VOLCENGINE_SEEDANCE_API_KEY or VOLCENGINE_ARK_API_KEY."
            );
        }

        ConnectionPool connectionPool = new ConnectionPool(5, 1, TimeUnit.SECONDS);
        Dispatcher dispatcher = new Dispatcher();
        return ArkService.builder()
                .dispatcher(dispatcher)
                .connectionPool(connectionPool)
                .apiKey(apiKey)
                .build();
    }

    private String firstText(String... values) {
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value.trim();
            }
        }
        return "";
    }
}
