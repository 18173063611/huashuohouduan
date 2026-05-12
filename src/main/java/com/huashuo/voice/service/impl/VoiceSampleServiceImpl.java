package com.huashuo.voice.service.impl;

import com.huashuo.common.exception.BusinessException;
import com.huashuo.storage.StorageService;
import com.huashuo.storage.UploadResult;
import com.huashuo.voice.client.DoubaoTtsClient;
import com.huashuo.voice.config.VolcengineTtsProperties;
import com.huashuo.voice.entity.VoiceProfileEntity;
import com.huashuo.voice.mapper.VoiceProfileMapper;
import com.huashuo.voice.service.VoiceSampleService;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.InputStream;
import java.net.http.HttpResponse;

@Service
public class VoiceSampleServiceImpl implements VoiceSampleService {

    private static final String DEFAULT_SAMPLE_TEXT = "大家好，这是音色试听。";

    private final VoiceProfileMapper voiceProfileMapper;
    private final DoubaoTtsClient doubaoTtsClient;
    private final VolcengineTtsProperties ttsProperties;
    private final StorageService storageService;

    public VoiceSampleServiceImpl(
            VoiceProfileMapper voiceProfileMapper,
            DoubaoTtsClient doubaoTtsClient,
            VolcengineTtsProperties ttsProperties,
            StorageService storageService
    ) {
        this.voiceProfileMapper = voiceProfileMapper;
        this.doubaoTtsClient = doubaoTtsClient;
        this.ttsProperties = ttsProperties;
        this.storageService = storageService;
    }

    @Override
    public String getOrCreateSampleUrl(Long voiceId, String text) {
        VoiceProfileEntity voice = voiceProfileMapper.selectById(voiceId);
        if (voice == null || voice.getEnabled() == null || voice.getEnabled() != 1) {
            throw new BusinessException(40400, "音色不存在或未启用");
        }
        if (StringUtils.hasText(voice.getSampleUrl())) {
            return voice.getSampleUrl().trim();
        }
        if (!ttsProperties.configured()) {
            throw new BusinessException(50100, "Volcengine TTS is not configured");
        }

        String resolvedText = StringUtils.hasText(text) ? text.trim() : DEFAULT_SAMPLE_TEXT;
        // 试听保持短文本，避免接口侧长度限制与不必要的费用
        if (resolvedText.length() > 180) {
            resolvedText = resolvedText.substring(0, 180);
        }

        try {
            String volcTaskId = doubaoTtsClient.submit(
                    null,
                    resolvedText,
                    voice.getProviderVoiceId(),
                    0,
                    0,
                    0
            );

            String audioUrl = pollAudioUrl(volcTaskId);
            if (!StringUtils.hasText(audioUrl)) {
                throw new BusinessException(50100, "试听合成完成但未返回音频地址");
            }

            HttpResponse<InputStream> audioResp = doubaoTtsClient.openAudioDownload(audioUrl);
            String contentType = audioResp.headers().firstValue(HttpHeaders.CONTENT_TYPE).orElse("audio/mpeg");
            long contentLen = audioResp.headers().firstValue(HttpHeaders.CONTENT_LENGTH)
                    .map(Long::parseLong).orElse(-1L);

            String fileName = "voice-sample-" + voiceId + ".mp3";
            UploadResult stored;
            try (InputStream in = audioResp.body()) {
                stored = storageService.upload(in, contentLen, fileName, contentType, "voice-sample");
            }

            String url = stored.url();
            voice.setSampleUrl(url);
            voiceProfileMapper.updateById(voice);
            return url;
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new BusinessException(50100, "生成试听失败：" + (ex.getMessage() == null ? "unknown error" : ex.getMessage()));
        }
    }

    private String pollAudioUrl(String volcTaskId) throws Exception {
        int maxAttempts = 60;
        for (int i = 0; i < maxAttempts; i++) {
            Thread.sleep(900);
            DoubaoTtsClient.QueryResult q = doubaoTtsClient.query(volcTaskId);
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

