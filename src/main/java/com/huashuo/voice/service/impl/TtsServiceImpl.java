package com.huashuo.voice.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
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
import com.huashuo.voice.service.TtsService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;

@Service
/**
 * 语音服务实现：校验脚本版本，生成 mock 音频资产，并用 TaskService 统一记录 TTS 任务状态。
 */
public class TtsServiceImpl implements TtsService {

    private final TaskService taskService;
    private final ScriptVersionService scriptVersionService;
    private final AssetService assetService;
    private final ObjectMapper objectMapper;

    public TtsServiceImpl(
            TaskService taskService,
            ScriptVersionService scriptVersionService,
            AssetService assetService,
            ObjectMapper objectMapper
    ) {
        this.taskService = taskService;
        this.scriptVersionService = scriptVersionService;
        this.assetService = assetService;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional
    public TtsGenerateResponse generate(TtsGenerateRequest request, String traceId) {
        scriptVersionService.requireForProject(request.projectId(), request.scriptVersionId());
        String inputJson = toJson(Map.of(
                "projectId", request.projectId(),
                "scriptVersionId", request.scriptVersionId(),
                "voiceCode", request.voiceCode()
        ));
        TaskItem task = taskService.createTask(
                request.projectId(),
                TaskTypeCode.TTS_GENERATE,
                inputJson,
                traceId
        );
        taskService.startTask(task.taskId());

        AssetItem audio = assetService.createMockAudioForTask(
                request.projectId(),
                task.taskId(),
                request.voiceCode()
        );

        Map<String, Object> output = new LinkedHashMap<>();
        output.put("assetId", audio.assetId());
        output.put("fileUrl", audio.fileUrl());
        output.put("voiceCode", request.voiceCode());
        taskService.completeTask(task.taskId(), toJson(output));

        TaskItem done = taskService.getTask(task.taskId());
        AssetItem loaded = assetService.getAsset(audio.assetId());
        return new TtsGenerateResponse(done.taskId(), done.status(), loaded);
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new BusinessException(50000, "Failed to serialize JSON");
        }
    }
}
