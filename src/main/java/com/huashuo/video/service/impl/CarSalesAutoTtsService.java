package com.huashuo.video.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huashuo.asset.service.AssetService;
import com.huashuo.asset.vo.AssetItem;
import com.huashuo.common.exception.BusinessException;
import com.huashuo.storage.StorageService;
import com.huashuo.storage.UploadResult;
import com.huashuo.task.vo.TaskItem;
import com.huashuo.video.DTO.CarSalesVideoDTO;
import com.huashuo.voice.client.DoubaoTtsClient;
import com.huashuo.voice.config.VolcengineTtsProperties;
import com.huashuo.voice.entity.VoiceProfileEntity;
import com.huashuo.voice.service.VoicePresetService;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.InputStream;
import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class CarSalesAutoTtsService {

    private static final int POLL_MAX_ATTEMPTS = 120;
    private static final long POLL_INTERVAL_MS = 1_500L;

    private final DoubaoTtsClient doubaoTtsClient;
    private final VolcengineTtsProperties ttsProperties;
    private final StorageService storageService;
    private final AssetService assetService;
    private final VoicePresetService voicePresetService;
    private final ObjectMapper objectMapper;

    public CarSalesAutoTtsService(
            DoubaoTtsClient doubaoTtsClient,
            VolcengineTtsProperties ttsProperties,
            StorageService storageService,
            AssetService assetService,
            VoicePresetService voicePresetService,
            ObjectMapper objectMapper
    ) {
        this.doubaoTtsClient = doubaoTtsClient;
        this.ttsProperties = ttsProperties;
        this.storageService = storageService;
        this.assetService = assetService;
        this.voicePresetService = voicePresetService;
        this.objectMapper = objectMapper;
    }

    public AutoTtsResult synthesize(TaskItem task, CarSalesVideoDTO request, String text) {
        if (task == null) {
            throw new BusinessException(40000, "自动 TTS 缺少任务上下文");
        }
        if (!StringUtils.hasText(text)) {
            throw new BusinessException(40000, "AUTO_TTS_TEXT_REQUIRED: 自动 TTS 需要最终口播文案");
        }
        if (!ttsProperties.configured()) {
            throw new BusinessException(50100, "AUTO_TTS_NOT_CONFIGURED: 自动 TTS 未配置火山引擎凭证");
        }

        VoiceProfileEntity voice = resolveVoice(task.ownerUserId(), request == null ? null : request.getAutoTtsVoiceId());
        double speed = clampDouble(request == null || request.getAutoTtsSpeed() == null ? 1.0 : request.getAutoTtsSpeed(), 0.5, 2.0);
        double volume = clampDouble(request == null || request.getAutoTtsVolume() == null ? 1.0 : request.getAutoTtsVolume(), 0.5, 2.0);
        int pitch = clampInt(request == null || request.getAutoTtsPitch() == null ? 0 : request.getAutoTtsPitch(), -12, 12);
        int speechRate = (int) Math.round((speed - 1.0) * 100);
        int loudnessRate = (int) Math.round((volume - 1.0) * 100);
        Long projectId = request != null && request.getProjectId() != null ? request.getProjectId() : task.projectId();
        String finalText = text.trim();

        try {
            String volcTaskId = doubaoTtsClient.submit(projectId, finalText, voice.getProviderVoiceId(),
                    speechRate, loudnessRate, pitch);
            String remoteAudioUrl = pollAudioUrl(volcTaskId);
            if (!StringUtils.hasText(remoteAudioUrl)) {
                throw new BusinessException(50100, "AUTO_TTS_EMPTY_AUDIO_URL: TTS 完成但未返回音频地址");
            }

            String fileName = "car-sales-auto-tts-" + task.taskId() + ".mp3";
            HttpResponse<InputStream> audioResp = doubaoTtsClient.openAudioDownloadWithRetries(remoteAudioUrl, task.taskId());
            String contentType = audioResp.headers().firstValue(HttpHeaders.CONTENT_TYPE).orElse("audio/mpeg");
            long contentLength = parseContentLength(audioResp);
            UploadResult stored;
            try (InputStream in = audioResp.body()) {
                stored = storageService.upload(in, contentLength, fileName, contentType, "tts");
            }

            AssetItem audio = assetService.createTtsAudioAsset(
                    task.ownerUserId(),
                    projectId,
                    task.taskId(),
                    stored.filename(),
                    stored.objectKey(),
                    stored.url(),
                    stored.contentType(),
                    stored.size(),
                    "TTS_GENERATE",
                    buildMetadata(task, request, voice, volcTaskId, remoteAudioUrl, finalText)
            );
            return new AutoTtsResult(audio.assetId(), audio.fileUrl(), voice.getVoiceId(),
                    voice.getVoiceName(), voice.getProviderVoiceId(), volcTaskId, remoteAudioUrl);
        } catch (BusinessException ex) {
            throw ex;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new BusinessException(50100, "AUTO_TTS_INTERRUPTED: 自动 TTS 被中断");
        } catch (Exception ex) {
            throw new BusinessException(50100, "AUTO_TTS_FAILED: 自动 TTS 生成失败：" + ex.getMessage());
        }
    }

    public boolean isConfigured() {
        return ttsProperties.configured();
    }

    private VoiceProfileEntity resolveVoice(Long ownerUserId, Long requestedVoiceId) {
        if (requestedVoiceId != null && requestedVoiceId > 0) {
            return voicePresetService.requireEnabledForUser(requestedVoiceId, ownerUserId);
        }
        return voicePresetService.resolveDefaultForUser(ownerUserId);
    }

    private String pollAudioUrl(String volcTaskId) throws Exception {
        for (int i = 0; i < POLL_MAX_ATTEMPTS; i++) {
            Thread.sleep(POLL_INTERVAL_MS);
            DoubaoTtsClient.QueryResult q = doubaoTtsClient.query(volcTaskId);
            if (q.taskStatus() == 2) {
                return q.audioUrl();
            }
            if (q.taskStatus() == 3) {
                throw new BusinessException(50100, "AUTO_TTS_PROVIDER_FAILED: " + q.message());
            }
        }
        throw new BusinessException(50100, "AUTO_TTS_TIMEOUT: 自动 TTS 等待音频超时");
    }

    private long parseContentLength(HttpResponse<InputStream> response) {
        return response.headers().firstValue(HttpHeaders.CONTENT_LENGTH)
                .map(value -> {
                    try {
                        return Long.parseLong(value);
                    } catch (NumberFormatException ignored) {
                        return -1L;
                    }
                })
                .orElse(-1L);
    }

    private String buildMetadata(TaskItem task, CarSalesVideoDTO request, VoiceProfileEntity voice,
                                 String volcTaskId, String remoteAudioUrl, String finalText)
            throws JsonProcessingException {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("source", "AUTO_TTS_CAR_SALES");
        meta.put("assetRole", "voiceover");
        meta.put("voicePolicy", "auto_tts");
        meta.put("parentTaskId", task.taskId());
        meta.put("voiceId", voice.getVoiceId());
        meta.put("voiceName", voice.getVoiceName());
        meta.put("providerVoiceId", voice.getProviderVoiceId());
        meta.put("volcTaskId", volcTaskId);
        meta.put("remoteAudioUrl", remoteAudioUrl);
        meta.put("text", finalText);
        if (request != null) {
            meta.put("brandModel", request.getBrandModel());
            meta.put("renderMode", request.getRenderMode());
            meta.put("autoTtsVoiceId", request.getAutoTtsVoiceId());
            meta.put("autoTtsSpeed", request.getAutoTtsSpeed());
            meta.put("autoTtsVolume", request.getAutoTtsVolume());
            meta.put("autoTtsPitch", request.getAutoTtsPitch());
        }
        return objectMapper.writeValueAsString(meta);
    }

    private double clampDouble(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private int clampInt(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    public record AutoTtsResult(
            Long assetId,
            String audioUrl,
            Long voiceId,
            String voiceName,
            String providerVoiceId,
            String volcTaskId,
            String remoteAudioUrl
    ) {
    }
}
