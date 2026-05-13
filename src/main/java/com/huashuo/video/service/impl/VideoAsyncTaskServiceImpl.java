package com.huashuo.video.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huashuo.common.exception.BusinessException;
import com.huashuo.task.aop.AiTaskSubmit;
import com.huashuo.task.enums.TaskTypeCode;
import com.huashuo.task.service.TaskService;
import com.huashuo.task.vo.TaskItem;
import com.huashuo.video.DTO.ImageDTO;
import com.huashuo.video.DTO.ImageFirstLastFrameDTO;
import com.huashuo.video.DTO.ImageReferenceDTO;
import com.huashuo.video.DTO.TextDTO;
import com.huashuo.video.service.VideoAsyncTaskService;
import org.springframework.stereotype.Service;

@Service
public class VideoAsyncTaskServiceImpl implements VideoAsyncTaskService {

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
                traceId, ownerUserId, null, null, idempotencyKey);
    }

    @Override
    @AiTaskSubmit
    public TaskItem createFirstFrameVideoTask(ImageDTO request, String traceId, Long ownerUserId,
                                              Long projectId, String idempotencyKey) {
        return taskService.createTask(projectId, TaskTypeCode.SEEDANCE_FIRST_FRAME_VIDEO, toJson(request),
                traceId, ownerUserId, null, null, idempotencyKey);
    }

    @Override
    @AiTaskSubmit
    public TaskItem createFirstLastFrameVideoTask(ImageFirstLastFrameDTO request, String traceId, Long ownerUserId,
                                                  Long projectId, String idempotencyKey) {
        return taskService.createTask(projectId, TaskTypeCode.SEEDANCE_FIRST_LAST_FRAME_VIDEO, toJson(request),
                traceId, ownerUserId, null, null, idempotencyKey);
    }

    @Override
    @AiTaskSubmit
    public TaskItem createReferenceVideoTask(ImageReferenceDTO request, String traceId, Long ownerUserId,
                                             Long projectId, String idempotencyKey) {
        return taskService.createTask(projectId, TaskTypeCode.SEEDANCE_REFERENCE_VIDEO, toJson(request),
                traceId, ownerUserId, null, null, idempotencyKey);
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new BusinessException(50000, "Failed to serialize task input");
        }
    }
}
