package com.huashuo.task.mq;

import com.huashuo.common.exception.RetryableException;
import com.huashuo.task.enums.TaskTypeCode;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 业务级执行超时保底：按任务类型限定最长执行时间，超时则中断工作线程，抛 {@link RetryableException}
 * 由消费者按可重试失败处理。注意 RabbitMQ broker 自身的 consumer_timeout 默认 30 分钟，
 * 此处给每类任务设置远小于 30 分钟的业务上限，避免被 broker 强制断连。
 */
@Component
public class TaskExecutionGuard {

    private static final Logger log = LoggerFactory.getLogger(TaskExecutionGuard.class);

    private static final Duration DEFAULT_TIMEOUT = Duration.ofMinutes(10);

    private static final Map<String, Duration> TIMEOUTS = Map.ofEntries(
            Map.entry(TaskTypeCode.TTS_GENERATE, Duration.ofMinutes(5)),
            Map.entry(TaskTypeCode.AVATAR_GENERATE, Duration.ofMinutes(5)),
            Map.entry(TaskTypeCode.VIDEO_SCRIPT_ANALYZE, Duration.ofMinutes(5)),
            Map.entry(TaskTypeCode.VIDEO_SCRIPT_URL_ANALYZE, Duration.ofMinutes(5)),
            Map.entry(TaskTypeCode.DOUYIN_REWRITE, Duration.ofMinutes(5)),
            Map.entry(TaskTypeCode.DOUYIN_TRANSCRIPT, Duration.ofMinutes(5)),
            Map.entry(TaskTypeCode.DOUYIN_PARSE_TRANSCRIPT, Duration.ofMinutes(5)),
            Map.entry(TaskTypeCode.SEEDANCE_TEXT_VIDEO, Duration.ofMinutes(15)),
            Map.entry(TaskTypeCode.SEEDANCE_FIRST_FRAME_VIDEO, Duration.ofMinutes(15)),
            Map.entry(TaskTypeCode.SEEDANCE_FIRST_LAST_FRAME_VIDEO, Duration.ofMinutes(15)),
            Map.entry(TaskTypeCode.SEEDANCE_REFERENCE_VIDEO, Duration.ofMinutes(15)),
            Map.entry(TaskTypeCode.DIGITAL_HUMAN_GENERATE, Duration.ofMinutes(15)),
            Map.entry(TaskTypeCode.VOICE_SAMPLE, Duration.ofMinutes(5))
    );

    private final ExecutorService pool = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "ai-task-guard");
        t.setDaemon(true);
        return t;
    });

    public void run(String taskType, Long taskId, GuardedAction work) {
        Duration timeout = TIMEOUTS.getOrDefault(taskType, DEFAULT_TIMEOUT);
        Future<?> future = pool.submit((Callable<Void>) () -> {
            work.execute();
            return null;
        });
        try {
            future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException te) {
            future.cancel(true);
            log.warn("AI task {} ({}) exceeded business timeout {} min", taskId, taskType, timeout.toMinutes());
            throw new RetryableException("任务执行超过 " + timeout.toMinutes() + " 分钟业务超时");
        } catch (ExecutionException ee) {
            Throwable cause = ee.getCause();
            if (cause instanceof RuntimeException re) {
                throw re;
            }
            if (cause instanceof Error err) {
                throw err;
            }
            throw new RetryableException(cause == null ? "未知执行异常" : cause.getMessage(), cause);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            throw new RetryableException("消费者线程被中断: " + taskType, ie);
        }
    }

    @PreDestroy
    public void shutdown() {
        pool.shutdownNow();
    }

    @FunctionalInterface
    public interface GuardedAction {
        void execute() throws Exception;
    }
}
