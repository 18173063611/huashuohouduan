package com.huashuo.voice.job;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huashuo.asset.service.AssetService;
import com.huashuo.asset.vo.AssetItem;
import com.huashuo.common.exception.BusinessException;
import com.huashuo.task.service.TaskService;
import com.huashuo.upload.config.UploadProperties;
import com.huashuo.voice.client.DoubaoTtsClient;
import com.huashuo.voice.config.VolcengineTtsProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 异步执行 TTS：RUNNING → 调用火山引擎 submit/query → 落盘音频 → 创建资产 → SUCCESS。
 */
@Component
public class TtsTaskExecutor {

    private static final Logger log = LoggerFactory.getLogger(TtsTaskExecutor.class);

    private final TaskService taskService;
    private final AssetService assetService;
    private final DoubaoTtsClient doubaoTtsClient;
    private final VolcengineTtsProperties ttsProperties;
    private final UploadProperties uploadProperties;
    private final ObjectMapper objectMapper;

    public TtsTaskExecutor(
            TaskService taskService,
            AssetService assetService,
            DoubaoTtsClient doubaoTtsClient,
            VolcengineTtsProperties ttsProperties,
            UploadProperties uploadProperties,
            ObjectMapper objectMapper
    ) {
        this.taskService = taskService;
        this.assetService = assetService;
        this.doubaoTtsClient = doubaoTtsClient;
        this.ttsProperties = ttsProperties;
        this.uploadProperties = uploadProperties;
        this.objectMapper = objectMapper;
    }

    @Async("voiceTtsAsyncExecutor")
    public void run(Long taskId) {
        try {
            taskService.startTask(taskId);
        } catch (Exception e) {
            log.warn("TTS task {} cannot start: {}", taskId, e.getMessage());
            return;
        }

        try {
            var task = taskService.getTask(taskId);
            JsonNode input = objectMapper.readTree(task.inputJson() == null ? "{}" : task.inputJson());
            long projectId = input.path("projectId").asLong();
            String text = input.path("text").asText("");
            String speaker = input.path("providerVoiceId").asText("");
            double speed = input.path("speed").isMissingNode() ? 1.0 : input.path("speed").asDouble(1.0);
            double volume = input.path("volume").isMissingNode() ? 1.0 : input.path("volume").asDouble(1.0);
            int pitch = input.path("pitch").isMissingNode() ? 0 : input.path("pitch").asInt(0);

            int speechRate = (int) Math.round((speed - 1.0) * 100);
            int loudnessRate = (int) Math.round((volume - 1.0) * 100);

            if (!ttsProperties.configured()) {
                taskService.failTask(taskId, "50100: Volcengine TTS credentials missing; set volcengine.tts.app-id and access-key", true);
                return;
            }

            String volcTaskId = doubaoTtsClient.submit(projectId, text, speaker, speechRate, loudnessRate, pitch);

            String audioUrl = pollAudioUrl(volcTaskId);
            if (audioUrl == null || audioUrl.isBlank()) {
                taskService.failTask(taskId, "TTS finished without audio URL", true);
                return;
            }

            String datePath = LocalDate.now().toString();
            Path dir = Path.of(uploadProperties.localRoot(), datePath);
            Files.createDirectories(dir);
            String fileName = "tts-" + taskId + ".mp3";
            Path target = dir.resolve(fileName);
            doubaoTtsClient.downloadAudio(audioUrl, target);

            long size = Files.size(target);
            String previewUrl = uploadProperties.previewPrefix() + "/" + datePath + "/" + fileName;

            AssetItem audio = assetService.createTtsAudioAsset(
                    projectId,
                    taskId,
                    fileName,
                    target.toAbsolutePath().toString(),
                    previewUrl,
                    "audio/mpeg",
                    size,
                    buildMeta(input, volcTaskId, audioUrl)
            );

            Map<String, Object> output = new LinkedHashMap<>();
            output.put("resultAssetId", audio.assetId());
            output.put("previewUrl", audio.fileUrl());
            output.put("volcTaskId", volcTaskId);
            output.put("remoteAudioUrl", audioUrl);
            taskService.completeTask(taskId, objectMapper.writeValueAsString(output));
        } catch (BusinessException ex) {
            log.warn("TTS task {} failed: {}", taskId, ex.getMessage());
            taskService.failTask(taskId, ex.getMessage(), true);
        } catch (Exception ex) {
            log.error("TTS task {} error", taskId, ex);
            taskService.failTask(taskId, ex.getMessage() == null ? "TTS unknown error" : ex.getMessage(), true);
        }
    }

    private String pollAudioUrl(String volcTaskId) throws Exception {
        int maxAttempts = 120;
        for (int i = 0; i < maxAttempts; i++) {
            Thread.sleep(1500);
            DoubaoTtsClient.QueryResult q = doubaoTtsClient.query(volcTaskId);
            if (q.taskStatus() == 2) {
                return q.audioUrl();
            }
            if (q.taskStatus() == 3) {
                throw new BusinessException(50100, "Volcengine TTS synthesis failed: " + q.message());
            }
        }
        throw new BusinessException(50100, "Volcengine TTS timeout waiting for audio");
    }

    private String buildMeta(JsonNode input, String volcTaskId, String remoteUrl) throws JsonProcessingException {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("voiceId", input.path("voiceId").asLong());
        meta.put("voiceName", input.path("voiceName").asText(""));
        meta.put("providerVoiceId", input.path("providerVoiceId").asText(""));
        meta.put("volcTaskId", volcTaskId);
        meta.put("remoteAudioUrl", remoteUrl);
        meta.put("source", "VOLCENGINE_ASYNC_TTS");
        return objectMapper.writeValueAsString(meta);
    }
}
