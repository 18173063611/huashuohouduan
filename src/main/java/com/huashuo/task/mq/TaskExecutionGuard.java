package com.huashuo.task.mq;

import com.huashuo.common.exception.RetryableException;
import com.huashuo.task.config.AiTaskProperties;
import com.huashuo.task.enums.TaskTypeCode;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

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
            Map.entry(TaskTypeCode.SEEDANCE_CAR_SALES_VIDEO, Duration.ofMinutes(30)),
            Map.entry(TaskTypeCode.DIGITAL_HUMAN_GENERATE, Duration.ofMinutes(15)),
            Map.entry(TaskTypeCode.VOICE_SAMPLE, Duration.ofMinutes(5))
    );

    private final AiTaskProperties aiTaskProperties;
    private final Map<String, Semaphore> concurrencyGuards = new ConcurrentHashMap<>();
    private final ExecutorService pool = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "ai-task-guard");
        t.setDaemon(true);
        return t;
    });

    public TaskExecutionGuard(AiTaskProperties aiTaskProperties) {
        this.aiTaskProperties = aiTaskProperties;
    }

    public void run(String taskType, Long taskId, GuardedAction work) {
        Semaphore semaphore = guardFor(taskType);
        acquire(taskType, taskId, semaphore);
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
        } finally {
            semaphore.release();
        }
    }

    private Semaphore guardFor(String taskType) {
        String key = taskType == null ? "__DEFAULT__" : taskType.trim().toUpperCase();
        return concurrencyGuards.computeIfAbsent(key,
                ignored -> new Semaphore(aiTaskProperties.getExecution().maxConcurrentFor(taskType)));
    }

    private void acquire(String taskType, Long taskId, Semaphore semaphore) {
        try {
            semaphore.acquire();
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new RetryableException("AI task concurrency wait interrupted: " + taskType + ", taskId=" + taskId, ie);
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
