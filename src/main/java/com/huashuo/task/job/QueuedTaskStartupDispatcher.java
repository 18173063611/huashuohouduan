package com.huashuo.task.job;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.huashuo.common.exception.BusinessException;
import com.huashuo.task.entity.TaskEntity;
import com.huashuo.task.enums.TaskStatusCode;
import com.huashuo.task.enums.TaskTypeCode;
import com.huashuo.task.mapper.TaskMapper;
import com.huashuo.task.mq.AiTaskExecutionDispatcher;
import com.huashuo.task.mq.AiTaskMessage;
import com.huashuo.task.mq.AiTaskPublisher;
import com.huashuo.task.mq.TaskExecutionGuard;
import com.huashuo.task.service.TaskService;
import com.huashuo.task.vo.TaskItem;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

@Component
@Order(Ordered.LOWEST_PRECEDENCE)
@ConditionalOnProperty(prefix = "huashuo.ai-task.backlog-dispatch", name = "enabled", havingValue = "true")
public class QueuedTaskStartupDispatcher implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(QueuedTaskStartupDispatcher.class);

    private final TaskMapper taskMapper;
    private final AiTaskPublisher aiTaskPublisher;
    private final AiTaskExecutionDispatcher aiTaskExecutionDispatcher;
    private final TaskExecutionGuard taskExecutionGuard;
    private final TaskService taskService;
    private final int maxItems;
    private final int lookbackHours;
    private final int staleRunningMinutes;
    private final int queuedRepublishMinutes;
    private final boolean queuedDirectFallbackEnabled;
    private final Set<Long> directDispatchInFlight = ConcurrentHashMap.newKeySet();
    private final ExecutorService directFallbackExecutor;

    public QueuedTaskStartupDispatcher(
            TaskMapper taskMapper,
            AiTaskPublisher aiTaskPublisher,
            AiTaskExecutionDispatcher aiTaskExecutionDispatcher,
            TaskExecutionGuard taskExecutionGuard,
            TaskService taskService,
            @Value("${huashuo.ai-task.backlog-dispatch.max-items:50}") int maxItems,
            @Value("${huashuo.ai-task.backlog-dispatch.lookback-hours:24}") int lookbackHours,
            @Value("${huashuo.ai-task.backlog-dispatch.stale-running-minutes:35}") int staleRunningMinutes,
            @Value("${huashuo.ai-task.backlog-dispatch.queued-republish-minutes:5}") int queuedRepublishMinutes,
            @Value("${huashuo.ai-task.backlog-dispatch.queued-direct-fallback-enabled:true}") boolean queuedDirectFallbackEnabled
    ) {
        this.taskMapper = taskMapper;
        this.aiTaskPublisher = aiTaskPublisher;
        this.aiTaskExecutionDispatcher = aiTaskExecutionDispatcher;
        this.taskExecutionGuard = taskExecutionGuard;
        this.taskService = taskService;
        this.maxItems = Math.max(1, maxItems);
        this.lookbackHours = Math.max(1, lookbackHours);
        this.staleRunningMinutes = Math.max(10, staleRunningMinutes);
        this.queuedRepublishMinutes = Math.max(1, queuedRepublishMinutes);
        this.queuedDirectFallbackEnabled = queuedDirectFallbackEnabled;
        AtomicInteger counter = new AtomicInteger(1);
        this.directFallbackExecutor = Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable, "queued-task-direct-fallback-" + counter.getAndIncrement());
            thread.setDaemon(true);
            return thread;
        });
    }

    @Override
    public void run(ApplicationArguments args) {
        recoverStaleRunningTasks();
        republishQueuedTasks(false);
    }

    @Scheduled(
            initialDelayString = "${huashuo.ai-task.backlog-dispatch.stale-scan-initial-delay-ms:120000}",
            fixedDelayString = "${huashuo.ai-task.backlog-dispatch.stale-scan-interval-ms:300000}"
    )
    public void recoverStaleTasksOnSchedule() {
        recoverStaleRunningTasks();
        republishQueuedTasks(true);
    }

    private void republishQueuedTasks(boolean onlyStale) {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime since = now.minusHours(lookbackHours);
        LambdaQueryWrapper<TaskEntity> wrapper = new LambdaQueryWrapper<TaskEntity>()
                .in(TaskEntity::getStatus, TaskStatusCode.QUEUED, TaskStatusCode.RETRYABLE)
                .ge(TaskEntity::getCreatedAt, since)
                .orderByAsc(TaskEntity::getCreatedAt)
                .last("limit " + maxItems);
        if (onlyStale) {
            wrapper.le(TaskEntity::getUpdatedAt, now.minusMinutes(queuedRepublishMinutes));
        }
        List<TaskEntity> tasks = taskMapper.selectList(wrapper);
        if (tasks.isEmpty()) {
            return;
        }
        int published = 0;
        for (TaskEntity task : tasks) {
            if (onlyStale && !markQueuedTaskRepublished(task, now)) {
                continue;
            }
            try {
                aiTaskPublisher.publish(toDispatchItem(task));
                published++;
            } catch (RuntimeException ex) {
                log.warn("Republish queued/retryable task failed. taskId={}, taskType={}, reason={}",
                        task.getTaskId(), task.getTaskType(), ex.getMessage());
            }
            if (onlyStale) {
                dispatchQueuedTaskDirectly(task);
            }
        }
        log.info("Republished {} {}queued/retryable task(s) created within the last {} hour(s).",
                published, onlyStale ? "stale " : "", lookbackHours);
    }

    private boolean markQueuedTaskRepublished(TaskEntity task, LocalDateTime now) {
        LocalDateTime staleBefore = now.minusMinutes(queuedRepublishMinutes);
        LambdaUpdateWrapper<TaskEntity> update = new LambdaUpdateWrapper<TaskEntity>()
                .eq(TaskEntity::getTaskId, task.getTaskId())
                .in(TaskEntity::getStatus, TaskStatusCode.QUEUED, TaskStatusCode.RETRYABLE)
                .le(TaskEntity::getUpdatedAt, staleBefore)
                .set(TaskEntity::getErrorCode, "TASK_REPUBLISHED")
                .set(TaskEntity::getErrorMessage, "任务长时间未被消费，已重新投递队列")
                .set(TaskEntity::getUpdatedAt, now);
        if (taskMapper.update(null, update) <= 0) {
            return false;
        }
        task.setErrorCode("TASK_REPUBLISHED");
        task.setErrorMessage("任务长时间未被消费，已重新投递队列");
        task.setUpdatedAt(now);
        return true;
    }

    private void dispatchQueuedTaskDirectly(TaskEntity task) {
        if (!queuedDirectFallbackEnabled || task == null || task.getTaskId() == null) {
            return;
        }
        Long taskId = task.getTaskId();
        if (!directDispatchInFlight.add(taskId)) {
            return;
        }
        AiTaskMessage message = new AiTaskMessage(taskId, task.getTaskType(), task.getOwnerUserId(), task.getTraceId());
        directFallbackExecutor.submit(() -> {
            try {
                log.warn("Direct fallback dispatch start for stale queued task. taskId={}, taskType={}",
                        taskId, task.getTaskType());
                taskExecutionGuard.run(message.taskType(), message.taskId(),
                        () -> aiTaskExecutionDispatcher.dispatch(message));
                log.info("Direct fallback dispatch completed for stale queued task. taskId={}, taskType={}",
                        taskId, task.getTaskType());
            } catch (BusinessException ex) {
                log.warn("Direct fallback business failure taskId={} taskType={} reason={}",
                        taskId, task.getTaskType(), ex.getMessage());
                failDirectFallbackTask(message, ex.getMessage(), false, true);
            } catch (RuntimeException ex) {
                log.warn("Direct fallback retryable failure taskId={} taskType={} reason={}",
                        taskId, task.getTaskType(), ex.getMessage());
                if (TaskTypeCode.DOUYIN_PARSE_TRANSCRIPT.equals(message.taskType())) {
                    failDirectFallbackTask(message, ex.getMessage(), false, true);
                } else {
                    failDirectFallbackTask(message, ex.getMessage(), true, false);
                }
            } finally {
                directDispatchInFlight.remove(taskId);
            }
        });
    }

    private void failDirectFallbackTask(AiTaskMessage message, String errorMessage,
                                        boolean retryable, boolean refundCredits) {
        try {
            taskService.failTask(message.taskId(),
                    errorMessage == null || errorMessage.isBlank() ? "AI 任务执行失败" : errorMessage,
                    retryable, refundCredits);
        } catch (Exception ex) {
            log.warn("Direct fallback failed to mark task {} as failed/retryable: {}",
                    message.taskId(), ex.getMessage());
        }
    }

    @PreDestroy
    public void shutdown() {
        directFallbackExecutor.shutdownNow();
    }

    private void recoverStaleRunningTasks() {
        LocalDateTime staleBefore = LocalDateTime.now().minusMinutes(staleRunningMinutes);
        LambdaQueryWrapper<TaskEntity> wrapper = new LambdaQueryWrapper<TaskEntity>()
                .eq(TaskEntity::getStatus, TaskStatusCode.RUNNING)
                .le(TaskEntity::getUpdatedAt, staleBefore)
                .orderByAsc(TaskEntity::getUpdatedAt)
                .last("limit " + maxItems);
        List<TaskEntity> tasks = taskMapper.selectList(wrapper);
        if (tasks.isEmpty()) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        int recovered = 0;
        for (TaskEntity task : tasks) {
            LambdaUpdateWrapper<TaskEntity> update = new LambdaUpdateWrapper<TaskEntity>()
                    .eq(TaskEntity::getTaskId, task.getTaskId())
                    .eq(TaskEntity::getStatus, TaskStatusCode.RUNNING)
                    .set(TaskEntity::getStatus, TaskStatusCode.RETRYABLE)
                    .set(TaskEntity::getErrorCode, "TASK_RETRYABLE")
                    .set(TaskEntity::getErrorMessage, "任务长时间无进度，已重新入队")
                    .set(TaskEntity::getUpdatedAt, now);
            if (taskMapper.update(null, update) <= 0) {
                continue;
            }
            task.setStatus(TaskStatusCode.RETRYABLE);
            task.setErrorCode("TASK_RETRYABLE");
            task.setErrorMessage("任务长时间无进度，已重新入队");
            task.setUpdatedAt(now);
            try {
                aiTaskPublisher.publish(toDispatchItem(task));
                recovered++;
            } catch (RuntimeException ex) {
                log.warn("Republish stale running task failed. taskId={}, taskType={}, reason={}",
                        task.getTaskId(), task.getTaskType(), ex.getMessage());
            }
        }
        log.warn("Recovered {} stale running task(s) older than {} minute(s).",
                recovered, staleRunningMinutes);
    }

    private TaskItem toDispatchItem(TaskEntity entity) {
        return new TaskItem(
                entity.getTaskId(),
                entity.getProjectId(),
                entity.getOwnerUserId(),
                entity.getTaskType(),
                entity.getModelCode(),
                entity.getProvider(),
                entity.getUsageUnit(),
                entity.getEstimatedUsage(),
                entity.getActualUsage(),
                entity.getEstimatedCreditCost(),
                entity.getActualCreditCost(),
                entity.getSettlementStatus(),
                entity.getCreditCost(),
                entity.getCreditLogId(),
                null,
                entity.getStatus(),
                entity.getProgress(),
                entity.getResultAssetId(),
                entity.getErrorCode(),
                entity.getErrorMessage(),
                entity.getRetryCount(),
                entity.getResultViewed() != null && entity.getResultViewed() == 1,
                entity.getInputJson(),
                entity.getOutputJson(),
                entity.getTraceId(),
                entity.getCreatedAt(),
                entity.getUpdatedAt(),
                entity.getStartedAt(),
                entity.getFinishedAt()
        );
    }
}
