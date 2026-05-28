package com.huashuo.video.config;

import com.volcengine.ark.runtime.service.ArkService;
import okhttp3.ConnectionPool;
import okhttp3.Dispatcher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.util.StringUtils;

import java.time.Duration;
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

        String baseUrl = firstText(
                environment.getProperty("volcengine.seedance.base-url"),
                environment.getProperty("volcengine.ark.base-url"),
                environment.getProperty("VOLCENGINE_SEEDANCE_BASE_URL"),
                environment.getProperty("VOLCENGINE_ARK_BASE_URL")
        );
        Duration timeout = Duration.ofSeconds(longProperty(environment,
                "volcengine.seedance.client-timeout-seconds", 60L));
        Duration callTimeout = Duration.ofSeconds(longProperty(environment,
                "volcengine.seedance.client-call-timeout-seconds", 120L));
        Duration connectTimeout = Duration.ofSeconds(longProperty(environment,
                "volcengine.seedance.client-connect-timeout-seconds", 15L));
        int retryTimes = intProperty(environment, "volcengine.seedance.client-retry-times", 1);

        ConnectionPool connectionPool = new ConnectionPool(10, 5, TimeUnit.MINUTES);
        Dispatcher dispatcher = new Dispatcher();
        dispatcher.setMaxRequests(32);
        dispatcher.setMaxRequestsPerHost(16);

        ArkService.Builder builder = ArkService.builder()
                .dispatcher(dispatcher)
                .connectionPool(connectionPool)
                .apiKey(apiKey)
                .timeout(timeout)
                .callTimeout(callTimeout)
                .connectTimeout(connectTimeout)
                .retryTimes(retryTimes);
        if (StringUtils.hasText(baseUrl)) {
            builder.baseUrl(baseUrl);
        }
        return builder.build();
    }

    private String firstText(String... values) {
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value.trim();
            }
        }
        return "";
    }

    private long longProperty(Environment environment, String key, long defaultValue) {
        try {
            String value = environment.getProperty(key);
            if (StringUtils.hasText(value)) {
                return Math.max(1L, Long.parseLong(value.trim()));
            }
        } catch (Exception ignored) {
            // Fall back to the conservative default below.
        }
        return defaultValue;
    }

    private int intProperty(Environment environment, String key, int defaultValue) {
        try {
            String value = environment.getProperty(key);
            if (StringUtils.hasText(value)) {
                return Math.max(0, Integer.parseInt(value.trim()));
            }
        } catch (Exception ignored) {
            // Fall back to the conservative default below.
        }
        return defaultValue;
    }
}
