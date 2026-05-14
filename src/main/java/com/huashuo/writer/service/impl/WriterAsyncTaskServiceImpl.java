package com.huashuo.writer.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huashuo.common.exception.BusinessException;
import com.huashuo.task.aop.AiTaskSubmit;
import com.huashuo.task.enums.TaskTypeCode;
import com.huashuo.task.service.TaskService;
import com.huashuo.task.vo.TaskItem;
import com.huashuo.writer.dto.RewriteDTO;
import com.huashuo.writer.dto.VideoScriptSubmitRequest;
import com.huashuo.writer.pojo.DouyinVideoParseRequest;
import com.huashuo.writer.pojo.DouyinVideoTranscriptRequest;
import com.huashuo.writer.service.WriterAsyncTaskService;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
public class WriterAsyncTaskServiceImpl implements WriterAsyncTaskService {

    private final TaskService taskService;
    private final ObjectMapper objectMapper;

    public WriterAsyncTaskServiceImpl(TaskService taskService, ObjectMapper objectMapper) {
        this.taskService = taskService;
        this.objectMapper = objectMapper;
    }

    @Override
    @AiTaskSubmit
    public TaskItem createVideoScriptAnalyzeTask(VideoScriptSubmitRequest request, long ownerUserId,
                                                  Long projectId, String traceId, String idempotencyKey) {
        requireScriptSubmit(request, ownerUserId);
        String url = request.url().trim();
        return taskService.createTask(projectId, TaskTypeCode.VIDEO_SCRIPT_ANALYZE,
                toJson(Map.of("url", safe(url))), traceId, ownerUserId, null, null, idempotencyKey);
    }

    @Override
    @AiTaskSubmit
    public TaskItem createVideoScriptUrlAnalyzeTask(VideoScriptSubmitRequest request, long ownerUserId,
                                                     Long projectId, String traceId, String idempotencyKey) {
        requireScriptSubmit(request, ownerUserId);
        String url = request.url().trim();
        return taskService.createTask(projectId, TaskTypeCode.VIDEO_SCRIPT_URL_ANALYZE,
                toJson(Map.of("url", safe(url))), traceId, ownerUserId, null, null, idempotencyKey);
    }

    private void requireScriptSubmit(VideoScriptSubmitRequest request, long ownerUserId) {
        if (request == null || request.url() == null || request.url().isBlank()) {
            throw new BusinessException(40000, "url 不能为空");
        }
        if (ownerUserId <= 0L) {
            throw new BusinessException(40100, "创建消耗积分任务失败：缺少登录用户信息");
        }
    }

    @Override
    public TaskItem createDouyinParseTranscriptTask(DouyinVideoParseRequest request, String traceId, Long ownerUserId) {
        Long projectId = request == null ? null : request.getProjectId();
        return taskService.createTask(projectId, TaskTypeCode.DOUYIN_PARSE_TRANSCRIPT, toJson(request),
                traceId, ownerUserId);
    }

    @Override
    @AiTaskSubmit
    public TaskItem createDouyinRewriteTask(RewriteDTO request, String traceId, Long ownerUserId) {
        return taskService.createTask(null, TaskTypeCode.DOUYIN_REWRITE, toJson(request), traceId, ownerUserId);
    }

    @Override
    @AiTaskSubmit
    public TaskItem createDouyinTranscriptTask(DouyinVideoTranscriptRequest request, String traceId, Long ownerUserId) {
        return taskService.createTask(null, TaskTypeCode.DOUYIN_TRANSCRIPT, toJson(request), traceId, ownerUserId);
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new BusinessException(50000, "Failed to serialize task input");
        }
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }
}
