package com.huashuo.video.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huashuo.common.exception.BusinessException;
import com.huashuo.task.aop.AiTaskSubmit;
import com.huashuo.task.enums.TaskTypeCode;
import com.huashuo.task.service.TaskService;
import com.huashuo.task.vo.TaskItem;
import com.huashuo.video.DTO.CarSalesVideoDTO;
import com.huashuo.video.DTO.ImageDTO;
import com.huashuo.video.DTO.ImageFirstLastFrameDTO;
import com.huashuo.video.DTO.ImageReferenceDTO;
import com.huashuo.video.DTO.TextDTO;
import com.huashuo.video.service.VideoAsyncTaskService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class VideoAsyncTaskServiceImpl implements VideoAsyncTaskService {

    private static final Logger log = LoggerFactory.getLogger(VideoAsyncTaskServiceImpl.class);

    private final TaskService taskService;
    private final ObjectMapper objectMapper;

    public VideoAsyncTaskServiceImpl(TaskService taskService, ObjectMapper objectMapper) {
        this.taskService = taskService;
        this.objectMapper = objectMapper;
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
    @AiTaskSubmit
    public TaskItem createCarSalesVideoTask(CarSalesVideoDTO request, String traceId, Long ownerUserId,
                                            Long projectId, String idempotencyKey) {
        int segmentCount = normalizeSegmentCount(request == null ? null : request.getSegmentCount());
        long creditCost = Math.max(1, segmentCount) * 220L;
        return taskService.createTask(projectId, TaskTypeCode.SEEDANCE_CAR_SALES_VIDEO, toJson(request),
                traceId, ownerUserId, null, creditCost, idempotencyKey);
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
            log.warn("Failed to serialize video task input", e);
            throw new BusinessException(50000, "Failed to serialize task input");
        }
    }
}
