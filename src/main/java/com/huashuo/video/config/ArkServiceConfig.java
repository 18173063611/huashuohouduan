package com.huashuo.video.config;

import com.volcengine.ark.runtime.service.ArkService;
import okhttp3.ConnectionPool;
import okhttp3.Dispatcher;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

/**
 * 火山方舟 Ark Runtime SDK 客户端 Bean 配置。
 * 视频生成任务（Seedance 系列）通过该 ArkService 单例发起异步请求，应用关闭时由 Spring 调用 shutdownExecutor 释放线程池。
 */
@Configuration
public class ArkServiceConfig {

    @Bean(destroyMethod = "shutdownExecutor")
    public ArkService seedanceArkService(
            @Value("${volcengine.seedance.api-key:${VOLCENGINE_SEEDANCE_API_KEY:}}") String apiKey
    ) {
        // 连接池与 Dispatcher 参考火山官方示例，避免每次创建任务都新建线程池。
        ConnectionPool connectionPool = new ConnectionPool(5, 1, TimeUnit.SECONDS);
        Dispatcher dispatcher = new Dispatcher();
        return ArkService.builder()
                .dispatcher(dispatcher)
                .connectionPool(connectionPool)
                .apiKey(apiKey)
                .build();
    }
}
