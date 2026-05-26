package com.huashuo.video.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huashuo.common.exception.BusinessException;
import com.huashuo.task.aop.AiTaskSubmit;
import com.huashuo.task.enums.TaskTypeCode;
import com.huashuo.task.mq.TaskExecutionGuard;
import com.huashuo.task.service.TaskService;
import com.huashuo.task.vo.TaskItem;
import com.huashuo.video.DTO.CarSalesVideoDTO;
import com.huashuo.video.DTO.ImageDTO;
import com.huashuo.video.DTO.ImageFirstLastFrameDTO;
import com.huashuo.video.DTO.ImageReferenceDTO;
import com.huashuo.video.DTO.TextDTO;
import com.huashuo.video.job.SeedanceVideoTaskExecutor;
import com.huashuo.video.service.VideoAsyncTaskService;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Service
public class VideoAsyncTaskServiceImpl implements VideoAsyncTaskService {

    private static final Logger log = LoggerFactory.getLogger(VideoAsyncTaskServiceImpl.class);

    private final TaskService taskService;
    private final ObjectMapper objectMapper;
    private final SeedanceVideoTaskExecutor seedanceVideoTaskExecutor;
    private final TaskExecutionGuard taskExecutionGuard;
    private final ExecutorService carSalesExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "car-sales-video-local-runner");
        thread.setDaemon(true);
        return thread;
    });

    public VideoAsyncTaskServiceImpl(TaskService taskService,
                                     ObjectMapper objectMapper,
                                     SeedanceVideoTaskExecutor seedanceVideoTaskExecutor,
                                     TaskExecutionGuard taskExecutionGuard) {
        this.taskService = taskService;
        this.objectMapper = objectMapper;
        this.seedanceVideoTaskExecutor = seedanceVideoTaskExecutor;
        this.taskExecutionGuard = taskExecutionGuard;
    }

    @Override
    @AiTaskSubmit
    public TaskItem createTextVideoTask(TextDTO request, String traceId, Long ownerUserId,
                                        Long projectId, String idempotencyKey) {
        return taskService.createTask(projectId, TaskTypeCode.SEEDANCE_TEXT_VIDEO, toJson(request),
                traceId, ownerUserId, null, 200L, idempotencyKey);
    }

    @Override
    @AiTaskSubmit
    public TaskItem createFirstFrameVideoTask(ImageDTO request, String traceId, Long ownerUserId,
                                              Long projectId, String idempotencyKey) {
        return taskService.createTask(projectId, TaskTypeCode.SEEDANCE_FIRST_FRAME_VIDEO, toJson(request),
                traceId, ownerUserId, null, 200L, idempotencyKey);
    }

    @Override
    @AiTaskSubmit
    public TaskItem createFirstLastFrameVideoTask(ImageFirstLastFrameDTO request, String traceId, Long ownerUserId,
                                                  Long projectId, String idempotencyKey) {
        return taskService.createTask(projectId, TaskTypeCode.SEEDANCE_FIRST_LAST_FRAME_VIDEO, toJson(request),
                traceId, ownerUserId, null, 200L, idempotencyKey);
    }

    @Override
    @AiTaskSubmit
    public TaskItem createReferenceVideoTask(ImageReferenceDTO request, String traceId, Long ownerUserId,
                                             Long projectId, String idempotencyKey) {
        return taskService.createTask(projectId, TaskTypeCode.SEEDANCE_REFERENCE_VIDEO, toJson(request),
                traceId, ownerUserId, null, 220L, idempotencyKey);
    }

    @Override
    public TaskItem createCarSalesVideoTask(CarSalesVideoDTO request, String traceId, Long ownerUserId,
                                            Long projectId, String idempotencyKey) {
        int segmentCount = normalizeSegmentCount(request == null ? null : request.getSegmentCount());
        long creditCost = Math.max(1, segmentCount) * 220L;
        TaskItem task = taskService.createTask(projectId, TaskTypeCode.SEEDANCE_CAR_SALES_VIDEO, toJson(request),
                traceId, ownerUserId, null, creditCost, idempotencyKey);
        submitCarSalesAfterCommit(task.taskId());
        return task;
    }

    private void submitCarSalesAfterCommit(Long taskId) {
        if (taskId == null) {
            return;
        }
        Runnable submit = () -> carSalesExecutor.submit(() -> runCarSalesLocally(taskId));
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    submit.run();
                }
            });
            return;
        }
        submit.run();
    }

    private void runCarSalesLocally(Long taskId) {
        try {
            taskExecutionGuard.run(TaskTypeCode.SEEDANCE_CAR_SALES_VIDEO, taskId,
                    () -> seedanceVideoTaskExecutor.run(taskId));
        } catch (BusinessException ex) {
            failCarSalesTask(taskId, ex.getMessage(), ex);
        } catch (RuntimeException ex) {
            failCarSalesTask(taskId, ex.getMessage(), ex);
        }
    }

    private void failCarSalesTask(Long taskId, String message, RuntimeException cause) {
        String safeMessage = message == null || message.isBlank()
                ? "汽车销售成片任务执行失败"
                : message;
        log.warn("Car sales video task {} failed in local runner: {}", taskId, safeMessage, cause);
        try {
            taskService.failTask(taskId, safeMessage, false, true);
        } catch (Exception failEx) {
            log.warn("Failed to mark car sales video task {} as FAILED: {}", taskId, failEx.getMessage());
        }
    }

    @PreDestroy
    public void shutdownCarSalesExecutor() {
        carSalesExecutor.shutdownNow();
    }

    private int normalizeSegmentCount(Integer value) {
        if (value == null) {
            return 4;
        }
        return Math.max(1, Math.min(6, value));
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new BusinessException(50000, "Failed to serialize task input");
        }
    }
}
