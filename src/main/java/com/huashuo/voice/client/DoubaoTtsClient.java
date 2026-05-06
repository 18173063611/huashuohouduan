package com.huashuo.voice.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huashuo.common.exception.BusinessException;
import com.huashuo.voice.config.VolcengineTtsProperties;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 火山引擎异步长文本语音合成（submit + query + 下载）。
 */
@Component
public class DoubaoTtsClient {

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20))
            .build();

    private final VolcengineTtsProperties properties;
    private final ObjectMapper objectMapper;

    /**
     * 同一个 speaker 往往只属于某一个 resourceId；做缓存可减少 submit 重试，避免并发配额被打满。
     */
    private final ConcurrentHashMap<String, String> speakerResourceCache = new ConcurrentHashMap<>();

    /**
     * query 请求需要与 submit 相同的 resourceId；提交成功后把 taskId -> resourceId 记录下来。
     */
    private final ConcurrentHashMap<String, String> taskResourceCache = new ConcurrentHashMap<>();

    public DoubaoTtsClient(VolcengineTtsProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    /**
     * @param speechRate 语速 [-50,100]
     * @param loudnessRate 音量 [-50,100]
     * @param pitch 音调 [-12,12]
     */
    public String submit(Long projectId, String text, String speaker,
                         int speechRate, int loudnessRate, int pitch) throws IOException, InterruptedException {
        if (!properties.configured()) {
            throw new BusinessException(50100, "Volcengine TTS is not configured");
        }
        String additions = objectMapper.writeValueAsString(Map.of("post_process", Map.of("pitch", clamp(pitch, -12, 12))));
        Map<String, Object> audioParams = new LinkedHashMap<>();
        audioParams.put("format", "mp3");
        audioParams.put("sample_rate", 24000);
        audioParams.put("speech_rate", clamp(speechRate, -50, 100));
        audioParams.put("loudness_rate", clamp(loudnessRate, -50, 100));

        Map<String, Object> reqParams = new LinkedHashMap<>();
        reqParams.put("text", text);
        reqParams.put("speaker", speaker);
        reqParams.put("audio_params", audioParams);
        reqParams.put("additions", additions);

        Map<String, Object> body = new LinkedHashMap<>();
        String uid = projectId == null ? "huashuo-global" : "huashuo-project-" + projectId;
        body.put("user", Map.of("uid", uid));
        body.put("namespace", "BidirectionalTTS");
        body.put("unique_id", UUID.randomUUID().toString());
        body.put("req_params", reqParams);

        String json = objectMapper.writeValueAsString(body);

        // 不同 speaker 对应不同模型资源：官方约定 seed-tts-2.0=豆包 2.0 音色，seed-tts-1.0 / volc.service_type.10029=1.0 音色。
        // 默认配置常为 10029；若先配 1.0 资源却选 2.0 音色会报 mismatch，必须继续试 seed-tts-2.0。
        String cached = speakerResourceCache.get(speaker);
        String[] resourceCandidates = cached != null && !cached.isBlank()
                ? new String[]{cached}
                : defaultResourceCandidates();

        BusinessException last = null;
        for (String resourceId : resourceCandidates) {
            try {
                SubmitResult r = submitOnce(json, resourceId);
                if (speaker != null && !speaker.isBlank()) {
                    speakerResourceCache.put(speaker, resourceId);
                }
                if (r.taskId() != null && !r.taskId().isBlank()) {
                    taskResourceCache.put(r.taskId(), resourceId);
                }
                return r.taskId();
            } catch (BusinessException ex) {
                last = ex;
                String msg = ex.getMessage() == null ? "" : ex.getMessage();
                // 仅对「资源 ID 与音色不匹配」类错误重试；文案可能大小写或中英文不一致
                if (!isResourceSpeakerMismatchMessage(msg)) {
                    throw ex;
                }
                // 说明缓存的 resourceId 不对，清空后允许后续再次探测
                if (cached != null) {
                    speakerResourceCache.remove(speaker);
                }
            }
        }
        // 如果仅尝试了缓存的 resourceId 且失败，则再补一次探测，避免永久卡死
        if (cached != null && !cached.isBlank()) {
            for (String resourceId : defaultResourceCandidates()) {
                try {
                    SubmitResult r = submitOnce(json, resourceId);
                    if (speaker != null && !speaker.isBlank()) {
                        speakerResourceCache.put(speaker, resourceId);
                    }
                    if (r.taskId() != null && !r.taskId().isBlank()) {
                        taskResourceCache.put(r.taskId(), resourceId);
                    }
                    return r.taskId();
                } catch (BusinessException ex) {
                    last = ex;
                    String msg = ex.getMessage() == null ? "" : ex.getMessage();
                    if (!isResourceSpeakerMismatchMessage(msg)) {
                        throw ex;
                    }
                }
            }
        }
        if (last != null) {
            throw last;
        }
        throw new BusinessException(50100, "Volcengine TTS submit failed");
    }

    private SubmitResult submitOnce(String json, String resourceId) throws IOException, InterruptedException {
        String requestId = UUID.randomUUID().toString();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(properties.effectiveSubmitUrl()))
                .timeout(Duration.ofSeconds(60))
                .header("Content-Type", "application/json; charset=utf-8")
                .header("X-Api-App-Id", properties.appId())
                .header("X-Api-Access-Key", properties.accessKey())
                .header("X-Api-Resource-Id", resourceId)
                .header("X-Api-Request-Id", requestId)
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        JsonNode root = objectMapper.readTree(response.body());
        int code = root.path("code").asInt(-1);
        if (code != 20000000) {
            String msg = root.path("message").asText("TTS submit failed");
            throw new BusinessException(50100, "Volcengine TTS submit: " + msg);
        }
        return new SubmitResult(root.path("data").path("task_id").asText(null));
    }

    /**
     * 探测顺序：先 2.0 再 1.0/legacy，最后用户配置的 resource-id（常为 volc.service_type.10029），
     * 避免 2.0 音色在首次请求就打到 1.0 资源上（虽可重试，但依赖错误文案匹配，易因文案差异失败）。
     */
    private String[] defaultResourceCandidates() {
        String configured = properties.effectiveResourceId();
        return dedupeOrdered("seed-tts-2.0", "seed-tts-1.0", configured);
    }

    private static String[] dedupeOrdered(String... values) {
        LinkedHashSet<String> set = new LinkedHashSet<>();
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                set.add(v);
            }
        }
        return set.toArray(new String[0]);
    }

    /**
     * 火山返回的 message 可能是英文大小写变体或中文描述；与「resource ID is mismatched」不完全一致时，
     * 原先 {@code contains} 过窄会导致不重试后续 resourceId，表现为「一直 mismatch」。
     */
    static boolean isResourceSpeakerMismatchMessage(String message) {
        if (message == null || message.isBlank()) {
            return false;
        }
        String m = message.toLowerCase(Locale.ROOT);
        if (m.contains("resource id is mismatched") || m.contains("mismatched with speaker")) {
            return true;
        }
        if (m.contains("resource") && m.contains("mismatch") && m.contains("speaker")) {
            return true;
        }
        // 常见中文：资源 / 音色 / 不匹配
        return message.contains("资源") && message.contains("音色")
                && (message.contains("不匹配") || message.contains("不一致"));
    }

    /**
     * @return task_status: 1 running, 2 success, 3 failure；成功时返回音频 URL
     */
    public QueryResult query(String volcTaskId) throws IOException, InterruptedException {
        if (!properties.configured()) {
            throw new BusinessException(50100, "Volcengine TTS is not configured");
        }
        String json = objectMapper.writeValueAsString(Map.of("task_id", volcTaskId));
        String requestId = UUID.randomUUID().toString();
        String resourceId = taskResourceCache.getOrDefault(volcTaskId, properties.effectiveResourceId());
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(properties.effectiveQueryUrl()))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json; charset=utf-8")
                .header("X-Api-App-Id", properties.appId())
                .header("X-Api-Access-Key", properties.accessKey())
                .header("X-Api-Resource-Id", resourceId)
                .header("X-Api-Request-Id", requestId)
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        JsonNode root = objectMapper.readTree(response.body());
        int code = root.path("code").asInt(-1);
        if (code != 20000000) {
            String msg = root.path("message").asText("TTS query failed");
            throw new BusinessException(50100, "Volcengine TTS query: " + msg);
        }
        JsonNode data = root.path("data");
        int taskStatus = data.path("task_status").asInt(-1);
        String audioUrl = data.path("audio_url").asText(null);
        return new QueryResult(taskStatus, audioUrl, root.path("message").asText(""));
    }

    public void downloadAudio(String audioUrl, Path targetFile) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(audioUrl))
                .timeout(Duration.ofMinutes(3))
                .GET()
                .build();
        HttpResponse<Path> response = HTTP.send(request, HttpResponse.BodyHandlers.ofFile(targetFile));
        if (response.statusCode() / 100 != 2) {
            throw new IOException("Download audio failed, HTTP " + response.statusCode());
        }
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    private record SubmitResult(String taskId) {
    }

    public record QueryResult(int taskStatus, String audioUrl, String message) {
    }
}
