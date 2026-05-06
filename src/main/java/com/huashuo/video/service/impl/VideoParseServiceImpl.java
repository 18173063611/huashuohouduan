package com.huashuo.video.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huashuo.common.exception.BusinessException;
import com.huashuo.task.enums.TaskStatusCode;
import com.huashuo.task.enums.TaskTypeCode;
import com.huashuo.task.service.TaskService;
import com.huashuo.task.vo.TaskItem;
import com.huashuo.video.dto.VideoParseQueryResponse;
import com.huashuo.video.dto.VideoParseRequest;
import com.huashuo.video.dto.VideoParseResultDto;
import com.huashuo.video.dto.VideoParseSceneDto;
import com.huashuo.video.dto.VideoParseSubmitResponse;
import com.huashuo.video.service.VideoParseService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

@Service
/**
 * 视频解析服务实现：当前只生成 mock 解析结果，并通过 TaskService 统一写入任务输入和输出。
 */
public class VideoParseServiceImpl implements VideoParseService {

    private final TaskService taskService;
    private final ObjectMapper objectMapper;

    public VideoParseServiceImpl(TaskService taskService, ObjectMapper objectMapper) {
        this.taskService = taskService;
        this.objectMapper = objectMapper;
    }

    @Override
    public VideoParseSubmitResponse submit(VideoParseRequest request, String traceId) {
        String inputJson = request.projectId() == null
                ? toJson(Map.of("videoUrl", request.videoUrl()))
                : toJson(Map.of("projectId", request.projectId(), "videoUrl", request.videoUrl()));
        TaskItem task = taskService.createTask(
                request.projectId(),
                TaskTypeCode.VIDEO_PARSE,
                inputJson,
                traceId
        );
        VideoParseResultDto mock = buildMockResult(request.videoUrl());
        return new VideoParseSubmitResponse(task.taskId(), task.status(), mock);
    }

    @Override
    @Transactional
    public VideoParseQueryResponse getParseResult(Long taskId) {
        TaskItem task = taskService.getTask(taskId);
        if (!TaskTypeCode.VIDEO_PARSE.equals(task.taskType())) {
            throw new BusinessException(40000, "Task is not a video parse job");
        }
        TaskItem current = task;
        if (TaskStatusCode.QUEUED.equals(task.status()) || TaskStatusCode.RETRYABLE.equals(task.status())) {
            taskService.startTask(taskId);
            String videoUrl = readVideoUrl(task.inputJson());
            VideoParseResultDto mock = buildMockResult(videoUrl);
            taskService.completeTask(taskId, toJson(mock));
            current = taskService.getTask(taskId);
        }
        VideoParseResultDto result = readResult(current.outputJson());
        return new VideoParseQueryResponse(current.taskId(), current.status(), result);
    }

    private VideoParseResultDto readResult(String outputJson) {
        try {
            return objectMapper.readValue(outputJson, VideoParseResultDto.class);
        } catch (JsonProcessingException e) {
            throw new BusinessException(50000, "Invalid stored parse output");
        }
    }

    private String readVideoUrl(String inputJson) {
        try {
            JsonNode n = objectMapper.readTree(inputJson);
            return n.path("videoUrl").asText("");
        } catch (JsonProcessingException e) {
            return "";
        }
    }

    private VideoParseResultDto buildMockResult(String videoUrl) {
        String url = videoUrl == null ? "" : videoUrl;
        List<VideoParseSceneDto> scenes = List.of(
                new VideoParseSceneDto(0.0, 4.5, "开场"),
                new VideoParseSceneDto(4.5, 12.0, "主体讲解"),
                new VideoParseSceneDto(12.0, 18.0, "结尾号召")
        );
        return new VideoParseResultDto(
                url,
                18.0,
                "占位解析：检测到口播类视频结构（mock），后续可替换为真实抽帧/ASR 管线。",
                scenes
        );
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new BusinessException(50000, "Failed to serialize JSON");
        }
    }
}
