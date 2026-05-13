package com.huashuo.voice.job;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huashuo.asset.service.AssetService;
import com.huashuo.asset.vo.AssetItem;
import com.huashuo.billing.model.UsageActualResult;
import com.huashuo.billing.model.UsageUnit;
import com.huashuo.billing.service.CreditBillingService;
import com.huashuo.common.exception.BusinessException;
import com.huashuo.storage.StorageService;
import com.huashuo.storage.UploadResult;
import com.huashuo.task.service.TaskService;
import com.huashuo.voice.client.DoubaoTtsClient;
import com.huashuo.voice.config.VolcengineTtsProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.math.BigDecimal;
import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 异步执行 TTS：RUNNING → 火山引擎 submit/query → 音频流直传 TOS → 创建资产 → SUCCESS。
 */
@Component
public class TtsTaskExecutor {

    private static final Logger log = LoggerFactory.getLogger(TtsTaskExecutor.class);

    private final TaskService taskService;
    private final AssetService assetService;
    private final DoubaoTtsClient doubaoTtsClient;
    private final VolcengineTtsProperties ttsProperties;
    private final StorageService storageService;
    private final ObjectMapper objectMapper;
    private final CreditBillingService creditBillingService;

    public TtsTaskExecutor(
            TaskService taskService,
            AssetService assetService,
            DoubaoTtsClient doubaoTtsClient,
            VolcengineTtsProperties ttsProperties,
            StorageService storageService,
            ObjectMapper objectMapper,
            CreditBillingService creditBillingService
    ) {
        this.taskService = taskService;
        this.assetService = assetService;
        this.doubaoTtsClient = doubaoTtsClient;
        this.ttsProperties = ttsProperties;
        this.storageService = storageService;
        this.objectMapper = objectMapper;
        this.creditBillingService = creditBillingService;
    }

    public void run(Long taskId) {
        try {
            taskService.startTask(taskId);
        } catch (Exception e) {
            log.warn("TTS task {} cannot start: {}", taskId, e.getMessage());
            return;
        }

        final boolean[] refundIfFail = {true};
        try {
            var task = taskService.getTask(taskId);
            JsonNode input = objectMapper.readTree(task.inputJson() == null ? "{}" : task.inputJson());
            Long projectId = input.hasNonNull("projectId") ? input.path("projectId").asLong() : null;
            String text = input.path("text").asText("");
            String speaker = input.path("providerVoiceId").asText("");
            double speed = input.path("speed").isMissingNode() ? 1.0 : input.path("speed").asDouble(1.0);
            double volume = input.path("volume").isMissingNode() ? 1.0 : input.path("volume").asDouble(1.0);
            int pitch = input.path("pitch").isMissingNode() ? 0 : input.path("pitch").asInt(0);

            int speechRate = (int) Math.round((speed - 1.0) * 100);
            int loudnessRate = (int) Math.round((volume - 1.0) * 100);

            if (!ttsProperties.configured()) {
                throw new BusinessException(50100, "Volcengine TTS credentials missing; set volcengine.tts.app-id and access-key");
            }

            String volcTaskId = doubaoTtsClient.submit(projectId, text, speaker, speechRate, loudnessRate, pitch);
            refundIfFail[0] = false;

            String audioUrl = pollAudioUrl(volcTaskId);
            if (audioUrl == null || audioUrl.isBlank()) {
                throw new BusinessException(50100, "TTS finished without audio URL");
            }

            String fileName = "tts-" + taskId + ".mp3";
            HttpResponse<InputStream> audioResp = doubaoTtsClient.openAudioDownload(audioUrl);
            String contentType = audioResp.headers().firstValue(HttpHeaders.CONTENT_TYPE).orElse("audio/mpeg");
            long contentLen = audioResp.headers().firstValue(HttpHeaders.CONTENT_LENGTH)
                    .map(Long::parseLong).orElse(-1L);

            UploadResult stored;
            try (InputStream in = audioResp.body()) {
                stored = storageService.upload(in, contentLen, fileName, contentType, "tts");
            }

            AssetItem audio = assetService.createTtsAudioAsset(
                    task.ownerUserId(),
                    projectId,
                    taskId,
                    fileName,
                    stored.objectKey(),
                    stored.url(),
                    stored.contentType(),
                    stored.size(),
                    buildMeta(input, volcTaskId, audioUrl)
            );

            Map<String, Object> output = new LinkedHashMap<>();
            output.put("resultAssetId", audio.assetId());
            output.put("previewUrl", audio.fileUrl());
            output.put("volcTaskId", volcTaskId);
            output.put("remoteAudioUrl", audioUrl);
            creditBillingService.settle(taskId, new UsageActualResult(
                    "VOLCENGINE",
                    task.modelCode(),
                    UsageUnit.CHAR,
                    null,
                    null,
                    null,
                    text.length(),
                    null,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    null,
                    objectMapper.writeValueAsString(Map.of("characterCount", text.length(), "volcTaskId", volcTaskId))
            ));
            taskService.completeTask(taskId, objectMapper.writeValueAsString(output));
        } catch (BusinessException ex) {
            log.warn("TTS task {} failed: {}", taskId, ex.getMessage());
            throw ex;
        } catch (RuntimeException ex) {
            log.error("TTS task {} error", taskId, ex);
            throw ex;
        } catch (Exception ex) {
            log.error("TTS task {} error", taskId, ex);
            throw new com.huashuo.common.exception.RetryableException(
                    ex.getMessage() == null ? "TTS unknown error" : ex.getMessage(), ex);
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
