package com.huashuo.common.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

@Configuration
@EnableAsync
public class AsyncConfig {

    @Bean("writerTaskExecutor")
    public Executor writerTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(20);
        executor.setThreadNamePrefix("writer-task-");
        executor.initialize();
        return executor;
    }

    /**
     * 与 {@code com.huashuo.voice.job.TtsTaskExecutor}（组件 Bean）区分命名，避免二者都叫 ttsTaskExecutor 导致启动失败。
     */
    @Bean("voiceTtsAsyncExecutor")
    public Executor voiceTtsAsyncExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("tts-task-");
        executor.initialize();
        return executor;
    }
}
