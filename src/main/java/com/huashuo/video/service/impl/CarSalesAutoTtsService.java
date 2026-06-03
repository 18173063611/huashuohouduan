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
import com.huashuo.voice.dto.VoicePresetItem;
import com.huashuo.voice.entity.VoiceProfileEntity;
import com.huashuo.voice.service.VoicePresetService;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.InputStream;
import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class CarSalesAutoTtsService {

    private static final int POLL_MAX_ATTEMPTS = 120;
    private static final long POLL_INTERVAL_MS = 1_500L;
    private static final double MIN_TTS_SPEED = 0.5;
    private static final double MAX_TTS_SPEED = 2.0;
    private static final double TARGET_FIT_TOLERANCE = 1.18;
    private static final Pattern LATIN_WORD_PATTERN =
            Pattern.compile("[A-Za-z0-9]+(?:[-'_][A-Za-z0-9]+)*");

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
        return synthesize(task, request, text, null);
    }

    public AutoTtsResult synthesize(TaskItem task, CarSalesVideoDTO request, String text,
                                    Double targetDurationSeconds) {
        if (task == null) {
            throw new BusinessException(40000, "AUTO_TTS_TASK_REQUIRED: automatic TTS requires task context");
        }
        if (!StringUtils.hasText(text)) {
            throw new BusinessException(40000, "AUTO_TTS_TEXT_REQUIRED: automatic TTS requires voiceover text");
        }
        if (!ttsProperties.configured()) {
            throw new BusinessException(50100, "AUTO_TTS_NOT_CONFIGURED: Volcengine TTS is not configured");
        }

        VoiceProfileEntity voice = resolveVoice(task.ownerUserId(),
                request == null ? null : request.getAutoTtsVoiceId(),
                request == null ? null : request.getNativeVoiceStyle());
        Long projectId = request != null && request.getProjectId() != null ? request.getProjectId() : task.projectId();
        String finalText = text.trim();
        double estimatedDurationSeconds = estimateSpeechDurationSeconds(finalText);
        ensureTextCanFitTarget(estimatedDurationSeconds, targetDurationSeconds);
        double speed = resolveTargetAwareSpeed(finalText,
                resolveRequestedSpeed(request),
                targetDurationSeconds);
        if (request != null) {
            request.setAutoTtsSpeed(speed);
        }
        double volume = clampDouble(request == null || request.getAutoTtsVolume() == null
                ? 1.0 : request.getAutoTtsVolume(), 0.5, 2.0);
        int pitch = clampInt(request == null || request.getAutoTtsPitch() == null
                ? defaultPitchForStyle(request) : request.getAutoTtsPitch(), -12, 12);
        int speechRate = (int) Math.round((speed - 1.0) * 100);
        int loudnessRate = (int) Math.round((volume - 1.0) * 100);

        try {
            String volcTaskId = doubaoTtsClient.submit(projectId, finalText, voice.getProviderVoiceId(),
                    speechRate, loudnessRate, pitch);
            String remoteAudioUrl = pollAudioUrl(volcTaskId);
            if (!StringUtils.hasText(remoteAudioUrl)) {
                throw new BusinessException(50100, "AUTO_TTS_EMPTY_AUDIO_URL: TTS finished without audio URL");
            }

            String fileName = "car-sales-auto-tts-" + task.taskId() + ".mp3";
            HttpResponse<InputStream> audioResp =
                    doubaoTtsClient.openAudioDownloadWithRetries(remoteAudioUrl, task.taskId());
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
                    buildMetadata(task, request, voice, volcTaskId, remoteAudioUrl, finalText,
                            targetDurationSeconds, estimatedDurationSeconds, speed)
            );
            return new AutoTtsResult(audio.assetId(), audio.fileUrl(), voice.getVoiceId(),
                    voice.getVoiceName(), voice.getProviderVoiceId(), volcTaskId, remoteAudioUrl);
        } catch (BusinessException ex) {
            throw ex;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new BusinessException(50100, "AUTO_TTS_INTERRUPTED: automatic TTS was interrupted");
        } catch (Exception ex) {
            throw new BusinessException(50100, "AUTO_TTS_FAILED: automatic TTS failed: " + ex.getMessage());
        }
    }

    public boolean isConfigured() {
        return ttsProperties.configured();
    }

    private VoiceProfileEntity resolveVoice(Long ownerUserId, Long requestedVoiceId, String nativeVoiceStyle) {
        if (requestedVoiceId != null && requestedVoiceId > 0) {
            return voicePresetService.requireEnabledForUser(requestedVoiceId, ownerUserId);
        }
        VoiceProfileEntity styleMatched = resolveStyleMatchedVoice(ownerUserId, nativeVoiceStyle);
        if (styleMatched != null) {
            return styleMatched;
        }
        return voicePresetService.resolveDefaultForUser(ownerUserId);
    }

    private VoiceProfileEntity resolveStyleMatchedVoice(Long ownerUserId, String nativeVoiceStyle) {
        String targetGender = targetGender(nativeVoiceStyle);
        if (!StringUtils.hasText(targetGender)) {
            return null;
        }
        List<VoicePresetItem> candidates = ownerUserId == null
                ? voicePresetService.listCatalogPresets()
                : voicePresetService.listUserLibrary(ownerUserId);
        return candidates.stream()
                .filter(item -> matchesTargetGender(item, targetGender))
                .findFirst()
                .map(item -> voicePresetService.requireEnabledForUser(item.voiceId(), ownerUserId))
                .orElse(null);
    }

    private String targetGender(String nativeVoiceStyle) {
        String style = normalize(nativeVoiceStyle);
        if (!StringUtils.hasText(style)) {
            return null;
        }
        if (style.startsWith("male_")) {
            return "male";
        }
        if (style.startsWith("female_")) {
            return "female";
        }
        return null;
    }

    private boolean matchesTargetGender(VoicePresetItem item, String targetGender) {
        if (item == null || !StringUtils.hasText(targetGender)) {
            return false;
        }
        String text = normalize(String.join(" ",
                nullToEmpty(item.gender()),
                nullToEmpty(item.voiceName()),
                nullToEmpty(item.providerVoiceId()),
                nullToEmpty(item.scene())));
        if ("male".equals(targetGender)) {
            return text.contains("男")
                    || text.contains("zh_male")
                    || text.contains("_male_")
                    || text.startsWith("male_");
        }
        if ("female".equals(targetGender)) {
            return text.contains("女")
                    || text.contains("zh_female")
                    || text.contains("_female_")
                    || text.startsWith("female_");
        }
        return false;
    }

    private Double resolveRequestedSpeed(CarSalesVideoDTO request) {
        if (request == null) {
            return null;
        }
        if (request.getAutoTtsSpeed() != null) {
            return request.getAutoTtsSpeed();
        }
        String style = normalize(request.getNativeVoiceStyle());
        String rhythm = normalize(request.getNativeSpeechStyle());
        if (style.contains("energetic_promo") || rhythm.contains("fast") || rhythm.contains("concise")) {
            return 1.12;
        }
        if (rhythm.contains("emotional")) {
            return 1.04;
        }
        if (rhythm.contains("slow") || rhythm.contains("soft")) {
            return 0.92;
        }
        if (rhythm.contains("review")) {
            return 0.98;
        }
        return null;
    }

    private int defaultPitchForStyle(CarSalesVideoDTO request) {
        String style = normalize(request == null ? null : request.getNativeVoiceStyle());
        if (style.startsWith("male_")) {
            return style.contains("energetic_promo") ? -1 : -2;
        }
        if (style.startsWith("female_")) {
            return style.contains("energetic_promo") ? 2 : 1;
        }
        return 0;
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
        throw new BusinessException(50100, "AUTO_TTS_TIMEOUT: automatic TTS timed out");
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
                                 String volcTaskId, String remoteAudioUrl, String finalText,
                                 Double targetDurationSeconds, double estimatedDurationSeconds,
                                 double resolvedSpeed)
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
        meta.put("targetDurationSeconds", roundSeconds(targetDurationSeconds));
        meta.put("estimatedBaseDurationSeconds", roundSeconds(estimatedDurationSeconds));
        meta.put("resolvedAutoTtsSpeed", roundSeconds(resolvedSpeed));
        if (request != null) {
            meta.put("brandModel", request.getBrandModel());
            meta.put("renderMode", request.getRenderMode());
            meta.put("nativeVoiceStyle", request.getNativeVoiceStyle());
            meta.put("nativeSpeechStyle", request.getNativeSpeechStyle());
            meta.put("autoTtsVoiceId", request.getAutoTtsVoiceId());
            meta.put("autoTtsSpeed", request.getAutoTtsSpeed());
            meta.put("autoTtsVolume", request.getAutoTtsVolume());
            meta.put("autoTtsPitch", request.getAutoTtsPitch());
        }
        return objectMapper.writeValueAsString(meta);
    }

    static double resolveTargetAwareSpeed(String text, Double requestedSpeed, Double targetDurationSeconds) {
        double requested = clampDouble(requestedSpeed == null ? 1.0 : requestedSpeed, MIN_TTS_SPEED, MAX_TTS_SPEED);
        if (!isPositiveFinite(targetDurationSeconds)) {
            return requested;
        }
        double estimatedDuration = estimateSpeechDurationSeconds(text);
        if (!Double.isFinite(estimatedDuration) || estimatedDuration <= 0) {
            return requested;
        }
        return clampDouble(estimatedDuration / targetDurationSeconds, MIN_TTS_SPEED, MAX_TTS_SPEED);
    }

    static double estimateSpeechDurationSeconds(String text) {
        if (!StringUtils.hasText(text)) {
            return 0.0;
        }
        int cjkChars = 0;
        int visibleChars = 0;
        int pauseMarks = 0;
        int lineBreaks = 0;
        for (int offset = 0; offset < text.length(); ) {
            int cp = text.codePointAt(offset);
            offset += Character.charCount(cp);
            if (cp == '\n' || cp == '\r') {
                lineBreaks++;
            }
            if (Character.isWhitespace(cp)) {
                continue;
            }
            visibleChars++;
            Character.UnicodeScript script = Character.UnicodeScript.of(cp);
            if (script == Character.UnicodeScript.HAN
                    || script == Character.UnicodeScript.HIRAGANA
                    || script == Character.UnicodeScript.KATAKANA
                    || script == Character.UnicodeScript.HANGUL) {
                cjkChars++;
            }
            if (isPausePunctuation(cp)) {
                pauseMarks++;
            }
        }

        int latinWords = 0;
        Matcher matcher = LATIN_WORD_PATTERN.matcher(text);
        while (matcher.find()) {
            latinWords++;
        }

        int nonCjkVisible = Math.max(0, visibleChars - cjkChars);
        int otherUnits = Math.max(0, nonCjkVisible - latinWords * 5);
        double cjkSeconds = cjkChars / 4.2;
        double latinSeconds = latinWords / 2.35;
        double otherSeconds = otherUnits / 6.0;
        double pauseSeconds = Math.min(8.0, pauseMarks * 0.12 + lineBreaks * 0.25);
        double estimate = cjkSeconds + latinSeconds + otherSeconds + pauseSeconds;
        if (estimate <= 0 && visibleChars > 0) {
            return Math.max(1.0, visibleChars / 4.5);
        }
        return estimate;
    }

    private static void ensureTextCanFitTarget(double estimatedDurationSeconds, Double targetDurationSeconds) {
        if (!isPositiveFinite(targetDurationSeconds) || estimatedDurationSeconds <= 0) {
            return;
        }
        double maxFittableDuration = targetDurationSeconds * MAX_TTS_SPEED * TARGET_FIT_TOLERANCE;
        if (estimatedDurationSeconds <= maxFittableDuration) {
            return;
        }
        throw new BusinessException(40000, String.format(Locale.ROOT,
                "AUTO_TTS_TEXT_TOO_LONG_FOR_DURATION: voiceover text is about %.1fs at normal speed, target video is %.1fs; shorten the copy or increase storyboard duration.",
                estimatedDurationSeconds, targetDurationSeconds));
    }

    private static boolean isPausePunctuation(int cp) {
        return cp == ',' || cp == '.' || cp == '!' || cp == '?'
                || cp == ';' || cp == ':' || cp == 0xFF0C || cp == 0x3002
                || cp == 0xFF01 || cp == 0xFF1F || cp == 0xFF1B || cp == 0xFF1A
                || cp == 0x3001 || cp == 0x2026;
    }

    private static boolean isPositiveFinite(Double value) {
        return value != null && Double.isFinite(value) && value > 0;
    }

    private static Double roundSeconds(Double value) {
        if (value == null || !Double.isFinite(value)) {
            return null;
        }
        return Math.round(value * 100.0) / 100.0;
    }

    private static Double roundSeconds(double value) {
        if (!Double.isFinite(value)) {
            return null;
        }
        return Math.round(value * 100.0) / 100.0;
    }

    private static double clampDouble(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private static int clampInt(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT).replace('-', '_');
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
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
