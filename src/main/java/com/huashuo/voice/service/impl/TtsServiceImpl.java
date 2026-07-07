package com.huashuo.voice.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huashuo.asset.service.AssetService;
import com.huashuo.asset.vo.AssetItem;
import com.huashuo.common.exception.BusinessException;
import com.huashuo.script.service.ScriptVersionService;
import com.huashuo.task.aop.AiTaskSubmit;
import com.huashuo.task.enums.TaskTypeCode;
import com.huashuo.task.service.TaskService;
import com.huashuo.task.vo.TaskItem;
import com.huashuo.user.service.UserFeaturePermissionService;
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
import java.util.OptionalLong;

@Service
public class TtsServiceImpl implements TtsService {

    private final TaskService taskService;
    private final ScriptVersionService scriptVersionService;
    private final VoicePresetService voicePresetService;
    private final AssetService assetService;
    private final TtsTaskExecutor ttsTaskExecutor;
    private final ObjectMapper objectMapper;
    private final UserFeaturePermissionService featurePermissionService;

    public TtsServiceImpl(
            TaskService taskService,
            ScriptVersionService scriptVersionService,
            VoicePresetService voicePresetService,
            AssetService assetService,
            TtsTaskExecutor ttsTaskExecutor,
            ObjectMapper objectMapper,
            UserFeaturePermissionService featurePermissionService
    ) {
        this.taskService = taskService;
        this.scriptVersionService = scriptVersionService;
        this.voicePresetService = voicePresetService;
        this.assetService = assetService;
        this.ttsTaskExecutor = ttsTaskExecutor;
        this.objectMapper = objectMapper;
        this.featurePermissionService = featurePermissionService;
    }

    @Override
    @AiTaskSubmit
    public TtsGenerateResponse generate(TtsGenerateRequest request, String traceId, Long ownerUserId,
                                        String idempotencyKey) {
        String resolvedText = resolveText(request, ownerUserId);
        if (!StringUtils.hasText(resolvedText)) {
            throw new BusinessException(40000, "合成文案不能为空");
        }
        VoiceProfileEntity voice = voicePresetService.requireEnabledForUser(request.voiceId(), ownerUserId);
        String provider = request.provider() == null || request.provider().isBlank()
                ? "DOUBAO"
                : request.provider();
        double speed = request.speed() == null ? 1.0 : request.speed();
        int pitch = request.pitch() == null ? 0 : request.pitch();
        double volume = request.volume() == null ? 1.0 : request.volume();
        String businessDomain = normalizeBusinessDomain(request.businessDomain());
        if ("pet".equals(businessDomain)) {
            if (ownerUserId == null) {
                throw new BusinessException(40100, "PET_CREATION_ACCESS_REQUIRED");
            }
            featurePermissionService.assertPetCreationAccess(ownerUserId);
        }

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
        if ("pet".equals(businessDomain)) {
            input.put("businessDomain", "pet");
            input.put("domain", "pet_creation");
        }

        String inputJson = toJson(input);
        TaskItem task = taskService.createTask(
                request.projectId(),
                TaskTypeCode.TTS_GENERATE,
                inputJson,
                traceId,
                ownerUserId,
                null,
                null,
                idempotencyKey
        );
        return new TtsGenerateResponse(task.taskId(), request.projectId(), TaskTypeCode.TTS_GENERATE, task.status());
    }

    @Override
    public TtsTaskDetailResponse getTtsTask(Long taskId) {
        TaskItem task = taskService.getTask(taskId);
        if (!TaskTypeCode.TTS_GENERATE.equals(task.taskType())) {
            throw new BusinessException(40400, "Not a TTS task");
        }
        Integer progress = task.progress();
        if (progress == null) {
            progress = switch (task.status()) {
                case "QUEUED" -> 10;
                case "RUNNING" -> 45;
                case "SUCCESS" -> 100;
                case "FAILED", "RETRYABLE", "CANCELED" -> 0;
                default -> null;
            };
        }
        AssetItem audio = null;
        if ("SUCCESS".equals(task.status())) {
            Long storedId = task.resultAssetId();
            if (storedId != null && storedId > 0) {
                try {
                    audio = assetService.getAsset(storedId);
                } catch (BusinessException ignored) {
                    // 资产可能已删除，退回解析 outputJson
                }
            }
            if (audio == null && task.outputJson() != null && !task.outputJson().isBlank()) {
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

    private String resolveText(TtsGenerateRequest request, Long ownerUserId) {
        if (StringUtils.hasText(request.text())) {
            return request.text().trim();
        }
        if (request.scriptId() != null) {
            OptionalLong viewer = ownerUserId == null ? OptionalLong.empty() : OptionalLong.of(ownerUserId);
            var sv = scriptVersionService.requireForProject(request.projectId(), request.scriptId(), viewer);
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

    private String normalizeBusinessDomain(String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        String normalized = value.trim().toLowerCase();
        return "pet".equals(normalized) || "pet_creation".equals(normalized) ? "pet" : "";
    }
}
