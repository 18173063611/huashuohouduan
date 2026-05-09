package com.huashuo.video.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huashuo.common.exception.BusinessException;
import com.huashuo.task.enums.TaskTypeCode;
import com.huashuo.task.service.TaskService;
import com.huashuo.task.vo.TaskItem;
import com.huashuo.video.DTO.DigitalHumanDTO;
import com.huashuo.video.config.ViduDigitalHumanProperties;
import com.huashuo.video.DTO.DigitalHumanGenerateResponse;
import com.huashuo.video.DTO.DigitalHumanTaskDetailResponse;
import com.huashuo.video.job.DigitalHumanTaskExecutor;
import com.huashuo.video.service.ViduDigitalHumanService;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class ViduDigitalHumanServiceImpl implements ViduDigitalHumanService {

    private final TaskService taskService;
    private final ViduDigitalHumanProperties properties;
    private final DigitalHumanTaskExecutor digitalHumanTaskExecutor;
    private final ObjectMapper objectMapper;

    public ViduDigitalHumanServiceImpl(
            TaskService taskService,
            ViduDigitalHumanProperties properties,
            DigitalHumanTaskExecutor digitalHumanTaskExecutor,
            ObjectMapper objectMapper
    ) {
        this.taskService = taskService;
        this.properties = properties;
        this.digitalHumanTaskExecutor = digitalHumanTaskExecutor;
        this.objectMapper = objectMapper;
    }

    @Override
    public DigitalHumanGenerateResponse generate(DigitalHumanDTO request, String traceId, Long ownerUserId) {
        if (request == null || !StringUtils.hasText(request.getImageUrl())) {
            throw new BusinessException(40000, "imageUrl is required");
        }
        if (!StringUtils.hasText(request.getAudioUrl()) && !StringUtils.hasText(request.getText())) {
            throw new BusinessException(40000, "audioUrl or text is required");
        }

        Map<String, Object> input = new LinkedHashMap<>();
        input.put("projectId", request.getProjectId());
        input.put("imageUrl", request.getImageUrl().trim());
        input.put("audioUrl", trimToNull(request.getAudioUrl()));
        input.put("text", trimToNull(request.getText()));
        input.put("voiceId", trimToNull(request.getVoiceId()));
        input.put("prompt", trimToNull(request.getPrompt()));
        input.put("resolution", StringUtils.hasText(request.getResolution())
                ? request.getResolution().trim()
                : properties.effectiveResolution());
        input.put("model", StringUtils.hasText(request.getModel())
                ? request.getModel().trim()
                : properties.effectiveModel());

        TaskItem task = taskService.createTask(
                request.getProjectId(),
                TaskTypeCode.DIGITAL_HUMAN_GENERATE,
                toJson(input),
                traceId,
                ownerUserId
        );
        digitalHumanTaskExecutor.run(task.taskId());
        return new DigitalHumanGenerateResponse(
                task.taskId(),
                task.projectId(),
                TaskTypeCode.DIGITAL_HUMAN_GENERATE,
                task.status()
        );
    }

    @Override
    public DigitalHumanTaskDetailResponse getGenerateTask(Long taskId) {
        TaskItem task = taskService.getTask(taskId);
        if (!TaskTypeCode.DIGITAL_HUMAN_GENERATE.equals(task.taskType())) {
            throw new BusinessException(40400, "Not a digital human task");
        }

        String model = null;
        String videoUrl = null;
        Long resultAssetId = task.resultAssetId();
        String coverUrl = null;
        Integer credits = null;

        if (StringUtils.hasText(task.outputJson())) {
            try {
                JsonNode out = objectMapper.readTree(task.outputJson());
                model = textOrNull(out.path("model"));
                videoUrl = textOrNull(out.path("videoUrl"));
                coverUrl = textOrNull(out.path("coverUrl"));
                if (resultAssetId == null && out.path("resultAssetId").canConvertToLong()) {
                    resultAssetId = out.path("resultAssetId").asLong();
                }
                if (out.path("credits").canConvertToInt()) {
                    credits = out.path("credits").asInt();
                }
            } catch (JsonProcessingException ignored) {
            }
        }

        return new DigitalHumanTaskDetailResponse(
                task.taskId(),
                task.projectId(),
                task.taskType(),
                task.status(),
                task.progress() != null ? task.progress() : progressOf(task.status()),
                task.errorMessage(),
                model,
                videoUrl,
                resultAssetId,
                coverUrl,
                credits
        );
    }

    private Integer progressOf(String status) {
        return switch (status) {
            case "QUEUED" -> 10;
            case "RUNNING" -> 45;
            case "SUCCESS" -> 100;
            case "FAILED", "RETRYABLE", "CANCELED" -> 0;
            default -> null;
        };
    }

    private String textOrNull(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        String value = node.asText(null);
        return trimToNull(value);
    }

    private String trimToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new BusinessException(50000, "Failed to serialize JSON");
        }
    }
}
