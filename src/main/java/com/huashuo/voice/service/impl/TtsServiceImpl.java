package com.huashuo.voice.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huashuo.asset.service.AssetService;
import com.huashuo.asset.vo.AssetItem;
import com.huashuo.common.exception.BusinessException;
import com.huashuo.script.service.ScriptVersionService;
import com.huashuo.task.enums.TaskTypeCode;
import com.huashuo.task.service.TaskService;
import com.huashuo.task.vo.TaskItem;
import com.huashuo.voice.dto.TtsGenerateRequest;
import com.huashuo.voice.dto.TtsGenerateResponse;
import com.huashuo.voice.dto.TtsTaskDetailResponse;
import com.huashuo.voice.entity.VoiceProfileEntity;
import com.huashuo.voice.job.TtsTaskExecutor;
import com.huashuo.voice.service.TtsService;
import com.huashuo.voice.service.VoicePresetService;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class TtsServiceImpl implements TtsService {

    private final TaskService taskService;
    private final ScriptVersionService scriptVersionService;
    private final VoicePresetService voicePresetService;
    private final AssetService assetService;
    private final TtsTaskExecutor ttsTaskExecutor;
    private final ObjectMapper objectMapper;

    public TtsServiceImpl(
            TaskService taskService,
            ScriptVersionService scriptVersionService,
            VoicePresetService voicePresetService,
            AssetService assetService,
            TtsTaskExecutor ttsTaskExecutor,
            ObjectMapper objectMapper
    ) {
        this.taskService = taskService;
        this.scriptVersionService = scriptVersionService;
        this.voicePresetService = voicePresetService;
        this.assetService = assetService;
        this.ttsTaskExecutor = ttsTaskExecutor;
        this.objectMapper = objectMapper;
    }

    @Override
    public TtsGenerateResponse generate(TtsGenerateRequest request, String traceId) {
        String resolvedText = resolveText(request);
        if (!StringUtils.hasText(resolvedText)) {
            throw new BusinessException(40000, "合成文案不能为空");
        }
        VoiceProfileEntity voice = voicePresetService.requireEnabled(request.voiceId());
        String provider = request.provider() == null || request.provider().isBlank()
                ? "DOUBAO"
                : request.provider();
        double speed = request.speed() == null ? 1.0 : request.speed();
        int pitch = request.pitch() == null ? 0 : request.pitch();
        double volume = request.volume() == null ? 1.0 : request.volume();

        Map<String, Object> input = new LinkedHashMap<>();
        input.put("projectId", request.projectId());
        input.put("scriptId", request.scriptId());
        input.put("text", resolvedText.trim());
        input.put("voiceId", voice.getVoiceId());
        input.put("voiceName", voice.getVoiceName());
        input.put("providerVoiceId", voice.getProviderVoiceId());
        input.put("provider", provider);
        input.put("speed", speed);
        input.put("pitch", pitch);
        input.put("volume", volume);

        String inputJson = toJson(input);
        TaskItem task = taskService.createTask(
                request.projectId(),
                TaskTypeCode.TTS_GENERATE,
                inputJson,
                traceId
        );
        ttsTaskExecutor.run(task.taskId());
        return new TtsGenerateResponse(task.taskId(), request.projectId(), TaskTypeCode.TTS_GENERATE, task.status());
    }

    @Override
    public TtsTaskDetailResponse getTtsTask(Long taskId) {
        TaskItem task = taskService.getTask(taskId);
        if (!TaskTypeCode.TTS_GENERATE.equals(task.taskType())) {
            throw new BusinessException(40400, "Not a TTS task");
        }
        Integer progress = switch (task.status()) {
            case "QUEUED" -> 10;
            case "RUNNING" -> 45;
            case "SUCCESS" -> 100;
            case "FAILED", "RETRYABLE" -> 0;
            default -> null;
        };
        AssetItem audio = null;
        if ("SUCCESS".equals(task.status()) && task.outputJson() != null && !task.outputJson().isBlank()) {
            try {
                JsonNode out = objectMapper.readTree(task.outputJson());
                long assetId = out.path("resultAssetId").asLong(0);
                if (assetId > 0) {
                    audio = assetService.getAsset(assetId);
                }
            } catch (JsonProcessingException ignored) {
                // ignore
            }
        }
        return new TtsTaskDetailResponse(
                task.taskId(),
                task.projectId(),
                task.taskType(),
                task.status(),
                progress,
                task.errorMessage(),
                audio
        );
    }

    private String resolveText(TtsGenerateRequest request) {
        if (StringUtils.hasText(request.text())) {
            return request.text().trim();
        }
        if (request.scriptId() != null) {
            if (request.projectId() == null) {
                throw new BusinessException(40000, "使用脚本生成语音时需要脚本所属 projectId；直接输入 text 时不需要。");
            }
            var sv = scriptVersionService.requireForProject(request.projectId(), request.scriptId());
            return sv.content();
        }
        return "";
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new BusinessException(50000, "Failed to serialize JSON");
        }
    }
}
