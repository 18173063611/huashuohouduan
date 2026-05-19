package com.huashuo.task.job;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.huashuo.task.entity.TaskEntity;
import com.huashuo.task.enums.TaskStatusCode;
import com.huashuo.task.mapper.TaskMapper;
import com.huashuo.task.mq.AiTaskPublisher;
import com.huashuo.task.vo.TaskItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

@Component
@Order(Ordered.LOWEST_PRECEDENCE)
@ConditionalOnProperty(prefix = "huashuo.ai-task.backlog-dispatch", name = "enabled", havingValue = "true")
public class QueuedTaskStartupDispatcher implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(QueuedTaskStartupDispatcher.class);

    private final TaskMapper taskMapper;
    private final AiTaskPublisher aiTaskPublisher;
    private final int maxItems;
    private final int lookbackHours;

    public QueuedTaskStartupDispatcher(
            TaskMapper taskMapper,
            AiTaskPublisher aiTaskPublisher,
            @Value("${huashuo.ai-task.backlog-dispatch.max-items:50}") int maxItems,
            @Value("${huashuo.ai-task.backlog-dispatch.lookback-hours:24}") int lookbackHours
    ) {
        this.taskMapper = taskMapper;
        this.aiTaskPublisher = aiTaskPublisher;
        this.maxItems = Math.max(1, maxItems);
        this.lookbackHours = Math.max(1, lookbackHours);
    }

    @Override
    public void run(ApplicationArguments args) {
        LocalDateTime since = LocalDateTime.now().minusHours(lookbackHours);
        LambdaQueryWrapper<TaskEntity> wrapper = new LambdaQueryWrapper<TaskEntity>()
                .in(TaskEntity::getStatus, TaskStatusCode.QUEUED, TaskStatusCode.RETRYABLE)
                .ge(TaskEntity::getCreatedAt, since)
                .orderByAsc(TaskEntity::getCreatedAt)
                .last("limit " + maxItems);
        List<TaskEntity> tasks = taskMapper.selectList(wrapper);
        if (tasks.isEmpty()) {
            return;
        }
        int published = 0;
        for (TaskEntity task : tasks) {
            try {
                aiTaskPublisher.publish(toDispatchItem(task));
                published++;
            } catch (RuntimeException ex) {
                log.warn("Republish queued/retryable task failed. taskId={}, taskType={}, reason={}",
                        task.getTaskId(), task.getTaskType(), ex.getMessage());
            }
        }
        log.info("Republished {} queued/retryable task(s) created within the last {} hour(s).",
                published, lookbackHours);
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
