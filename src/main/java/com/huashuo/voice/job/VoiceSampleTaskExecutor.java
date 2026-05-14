package com.huashuo.voice.job;

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
import com.huashuo.task.mq.TaskFailureRefundHint;
import com.huashuo.task.service.TaskService;
import com.huashuo.voice.client.DoubaoTtsClient;
import com.huashuo.voice.config.VolcengineTtsProperties;
import com.huashuo.voice.entity.VoiceProfileEntity;
import com.huashuo.voice.mapper.VoiceProfileMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.InputStream;
import java.math.BigDecimal;
import java.net.http.HttpResponse;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class VoiceSampleTaskExecutor {

    private static final Logger log = LoggerFactory.getLogger(VoiceSampleTaskExecutor.class);
    private static final String DEFAULT_SAMPLE_TEXT = "大家好，这是音色试听。";

    private final TaskService taskService;
    private final VoiceProfileMapper voiceProfileMapper;
    private final DoubaoTtsClient doubaoTtsClient;
    private final VolcengineTtsProperties ttsProperties;
    private final StorageService storageService;
    private final AssetService assetService;
    private final ObjectMapper objectMapper;
    private final CreditBillingService creditBillingService;

    public VoiceSampleTaskExecutor(
            TaskService taskService,
            VoiceProfileMapper voiceProfileMapper,
            DoubaoTtsClient doubaoTtsClient,
            VolcengineTtsProperties ttsProperties,
            StorageService storageService,
            AssetService assetService,
            ObjectMapper objectMapper,
            CreditBillingService creditBillingService
    ) {
        this.taskService = taskService;
        this.voiceProfileMapper = voiceProfileMapper;
        this.doubaoTtsClient = doubaoTtsClient;
        this.ttsProperties = ttsProperties;
        this.storageService = storageService;
        this.assetService = assetService;
        this.objectMapper = objectMapper;
        this.creditBillingService = creditBillingService;
    }

    public void run(Long taskId) {
        try {
            taskService.startTask(taskId);
        } catch (Exception e) {
            log.warn("VOICE_SAMPLE task {} cannot start: {}", taskId, e.getMessage());
            return;
        }

        final boolean[] refundIfFail = {true};
        try {
            var task = taskService.getTask(taskId);
            JsonNode input = objectMapper.readTree(task.inputJson() == null ? "{}" : task.inputJson());
            long voiceId = input.path("voiceId").asLong(0);
            String text = input.path("text").asText("");

            if (voiceId <= 0) {
                throw new BusinessException(40000, "voiceId is required");
            }
            VoiceProfileEntity voice = voiceProfileMapper.selectById(voiceId);
            if (voice == null || voice.getEnabled() == null || voice.getEnabled() != 1) {
                throw new BusinessException(40400, "音色不存在或未启用");
            }

            // 已有缓存：直接完成（不再调模型）
            if (StringUtils.hasText(voice.getSampleUrl())) {
                taskService.completeTask(taskId, objectMapper.writeValueAsString(Map.of(
                        "sampleUrl", voice.getSampleUrl().trim(),
                        "cached", true
                )));
                return;
            }

            if (!ttsProperties.configured()) {
                throw new BusinessException(50100, "Volcengine TTS credentials missing; set volcengine.tts.app-id and access-key");
            }

            String resolvedText = StringUtils.hasText(text) ? text.trim() : DEFAULT_SAMPLE_TEXT;
            if (resolvedText.length() > 180) {
                resolvedText = resolvedText.substring(0, 180);
            }

            String volcTaskId = doubaoTtsClient.submit(null, resolvedText, voice.getProviderVoiceId(), 0, 0, 0);
            refundIfFail[0] = false;
            TaskFailureRefundHint.set(false);
            String audioUrl = pollAudioUrl(volcTaskId);
            if (!StringUtils.hasText(audioUrl)) {
                throw new BusinessException(50100, "试听合成完成但未返回音频地址");
            }

            String fileName = "voice-sample-" + voiceId + "-" + LocalDateTime.now().toString().replace(":", "") + ".mp3";
            HttpResponse<InputStream> audioResp;
            try {
                audioResp = doubaoTtsClient.openAudioDownloadWithRetries(audioUrl, taskId);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                TaskFailureRefundHint.set(refundIfFail[0]);
                throw new com.huashuo.common.exception.RetryableException("音色试听音频下载被中断", ex);
            }
            String contentType = audioResp.headers().firstValue(HttpHeaders.CONTENT_TYPE).orElse("audio/mpeg");
            long contentLen = audioResp.headers().firstValue(HttpHeaders.CONTENT_LENGTH)
                    .map(Long::parseLong).orElse(-1L);

            UploadResult stored;
            try (InputStream in = audioResp.body()) {
                stored = storageService.upload(in, contentLen, fileName, contentType, "voice-sample");
            }

            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("voiceId", voice.getVoiceId());
            meta.put("voiceName", voice.getVoiceName());
            meta.put("providerVoiceId", voice.getProviderVoiceId());
            meta.put("sample", true);
            meta.put("source", "VOLCENGINE_ASYNC_TTS");

            AssetItem audio = assetService.createTtsAudioAsset(
                    task.ownerUserId(),
                    null,
                    taskId,
                    fileName,
                    stored.objectKey(),
                    stored.url(),
                    stored.contentType(),
                    stored.size(),
                    objectMapper.writeValueAsString(meta)
            );

            // 回写缓存 URL
            voice.setSampleUrl(stored.url());
            voiceProfileMapper.updateById(voice);

            Map<String, Object> output = new LinkedHashMap<>();
            output.put("sampleUrl", stored.url());
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
                    resolvedText.length(),
                    null,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    null,
                    objectMapper.writeValueAsString(Map.of("characterCount", resolvedText.length(), "volcTaskId", volcTaskId))
            ));
            taskService.completeTask(taskId, objectMapper.writeValueAsString(output));
        } catch (BusinessException ex) {
            log.warn("VOICE_SAMPLE task {} failed: {}", taskId, ex.getMessage());
            TaskFailureRefundHint.set(refundIfFail[0]);
            throw ex;
        } catch (RuntimeException ex) {
            log.error("VOICE_SAMPLE task {} error", taskId, ex);
            TaskFailureRefundHint.set(refundIfFail[0]);
            throw ex;
        } catch (Exception ex) {
            log.error("VOICE_SAMPLE task {} error", taskId, ex);
            TaskFailureRefundHint.set(refundIfFail[0]);
            throw new com.huashuo.common.exception.RetryableException(
                    ex.getMessage() == null ? "Voice sample unknown error" : ex.getMessage(), ex);
        }
    }

    private String pollAudioUrl(String volcTaskId) throws Exception {
        int maxAttempts = 80;
        for (int i = 0; i < maxAttempts; i++) {
            Thread.sleep(900);
            DoubaoTtsClient.QueryResult q;
            try {
                q = doubaoTtsClient.query(volcTaskId);
            } catch (java.net.http.HttpTimeoutException timeout) {
                // query 偶发超时：直接进入下一轮轮询，不立刻判任务失败（避免抖动导致频繁 RETRYABLE）
                log.warn("VOICE_SAMPLE task poll timeout, volcTaskId={}", volcTaskId);
                continue;
            }
            if (q.taskStatus() == 2) {
                return q.audioUrl();
            }
            if (q.taskStatus() == 3) {
                throw new BusinessException(50100, "试听合成失败：" + q.message());
            }
        }
        throw new BusinessException(50100, "试听合成超时");
    }
}
