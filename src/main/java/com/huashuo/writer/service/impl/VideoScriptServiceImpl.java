package com.huashuo.writer.service.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huashuo.common.exception.BusinessException;
import com.huashuo.upload.tos.TosUploadService;
import com.huashuo.upload.tos.VolcengineTosProperties;
import com.huashuo.writer.VO.ScriptVO;
import com.huashuo.writer.pojo.DouyinVideoParseRequest;
import com.huashuo.writer.pojo.DouyinVideoParseResponse;
import com.huashuo.writer.service.VideoScriptService;
import com.huashuo.writer.service.WriterService;
import com.volcengine.tos.TOSV2;
import com.volcengine.tos.TosException;
import com.volcengine.tos.model.object.HeadObjectV2Input;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
@Slf4j
public class VideoScriptServiceImpl implements VideoScriptService {
    @Autowired
    private WriterService writerService;

    private static final String SCRIPT_VIDEO_OBJECT_PREFIX = "writer/script-video/";
    private static final String VIDEO_CONTENT_TYPE = "video/mp4";
    private static final String DOWNLOAD_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";

    private static final String SCRIPT_ANALYZE_PROMPT = """
            你是专业的短视频分镜解析助手。请对输入视频按镜头切换或场景变化进行分镜切分，并对每一个分镜片段输出以下字段：
            - order: 分镜序号，从 1 开始的整数；
            - time: 该分镜的起止时间或时长，格式形如 "00:00:03-00:00:08"；
            - page: 画面内容，描述该分镜的人物、场景、动作、画面元素等视觉信息；
            - backgroundMusic: 背景音乐风格、节奏或具体音乐，无则填 "无"；
            - content: 口播文案，即该分镜中的旁白、人物原话或字幕原文，无则填 "无"；
            - highlight: 突出点，该分镜的核心亮点或表达意图。

            严格按下述 JSON 数组格式输出，不要输出任何额外解释、不要使用 markdown 代码块包裹、不要在 JSON 前后添加任何文字：
            [
              {"order": 1, "time": "...", "page": "...", "backgroundMusic": "...", "content": "...", "highlight": "..."}
            ]
            """;

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final String arkBaseUrl;
    private final String arkApiKey;
    private final String arkVideoModel;
    private final float videoFps;
    private final TosUploadService tosUploadService;
    private final VolcengineTosProperties tosProperties;
    private final ObjectProvider<TOSV2> tosClient;
    private final long videoTransferMaxBytes;
    private final int videoDownloadConnectTimeoutMillis;
    private final int videoDownloadReadTimeoutMillis;

    public VideoScriptServiceImpl(
            ObjectMapper objectMapper,
            TosUploadService tosUploadService,
            VolcengineTosProperties tosProperties,
            ObjectProvider<TOSV2> tosClient,
            @Value("${volcengine.arks.base-url:${VOLCENGINE_ARKS_BASE_URL:https://ark.cn-beijing.volces.com/api/v3}}") String arkBaseUrl,
            @Value("${volcengine.arks.api-key:${VOLCENGINE_ARKS_API_KEY:}}") String arkApiKey,
            @Value("${volcengine.arks.video-model:${VOLCENGINE_ARKS_VIDEO_MODEL:doubao-seed-2-0-lite-260215}}") String arkVideoModel,
            @Value("${volcengine.arks.video-fps:1.0}") float videoFps,
            @Value("${volcengine.arks.video-transfer.max-bytes:52428800}") long videoTransferMaxBytes,
            @Value("${volcengine.arks.video-transfer.connect-timeout-seconds:15}") long videoDownloadConnectTimeoutSeconds,
            @Value("${volcengine.arks.video-transfer.read-timeout-seconds:60}") long videoDownloadReadTimeoutSeconds
    ) {
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(60))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        this.arkBaseUrl = trimTrailingSlash(arkBaseUrl);
        this.arkApiKey = arkApiKey;
        this.arkVideoModel = StringUtils.hasText(arkVideoModel) ? arkVideoModel.trim() : "doubao-seed-2-0-lite-260215";
        this.videoFps = videoFps;
        this.tosUploadService = tosUploadService;
        this.tosProperties = tosProperties;
        this.tosClient = tosClient;
        this.videoTransferMaxBytes = videoTransferMaxBytes > 0 ? videoTransferMaxBytes : 52_428_800L;
        this.videoDownloadConnectTimeoutMillis = secondsToMillis(videoDownloadConnectTimeoutSeconds, 15);
        this.videoDownloadReadTimeoutMillis = secondsToMillis(videoDownloadReadTimeoutSeconds, 60);
    }

    @Override
    public List<ScriptVO> scriptAnalyze(String url) {
        if (!StringUtils.hasText(url)) {
            throw new BusinessException(40000, "url is required");
        }
        if (!StringUtils.hasText(arkApiKey)) {
            throw new BusinessException(50001, "Volcengine Ark api key is not configured");
        }

        try {
            Map<String, Object> body = Map.of(
                    "model", arkVideoModel,
                    "messages", List.of(Map.of(
                            "role", "user",
                            "content", List.of(
                                    Map.of(
                                            "type", "video_url",
                                            "video_url", Map.of(
                                                    "url", url,
                                                    "fps", videoFps
                                            )
                                    ),
                                    Map.of(
                                            "type", "text",
                                            "text", SCRIPT_ANALYZE_PROMPT
                                    )
                            )
                    ))
            );

            log.info("开始传给大模型进行分镜解析，time={}", LocalDateTime.now());
            HttpRequest httpRequest = HttpRequest.newBuilder(URI.create(arkBaseUrl + "/chat/completions"))
                    .timeout(Duration.ofSeconds(180))
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + arkApiKey)
                    .header(HttpHeaders.CONTENT_TYPE, "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                    .build();

            HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new BusinessException(50220,
                        "Doubao video understanding failed: " + arkErrorMessage(response));
            }
            String content = extractMessageContent(response.body());
            if (!StringUtils.hasText(content)) {
                throw new BusinessException(50220, "Doubao video understanding returned empty content");
            }
            log.info("分镜解析完成，time={}", LocalDateTime.now());
            return parseScriptList(content);
        } catch (IOException exception) {
            throw new BusinessException(50220, "Doubao video understanding failed: " + exception.getMessage());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new BusinessException(50220, "Doubao video understanding was interrupted");
        }
    }

    @Override
    public List<ScriptVO> scriptAnalyzeByUrl(String url) {
        DouyinVideoParseRequest request = new DouyinVideoParseRequest();
        request.setUrl(url);
        DouyinVideoParseResponse parseResult= writerService.parseDouyinVideo(request);
        log.info("parseResult:{}",parseResult.getPlayUrl()); // playUrl抖音官方CDN视频资源直链
        String modelVideoUrl = publishDouyinPlayUrlForModel(parseResult, url);
        return scriptAnalyze(modelVideoUrl);
    }

    private String publishDouyinPlayUrlForModel(DouyinVideoParseResponse parseResult, String sourceUrl) {
        String playUrl = parseResult == null ? null : parseResult.getPlayUrl();
        if (!StringUtils.hasText(playUrl)) {
            throw new BusinessException(50202, "TikHub parse succeeded but playUrl is empty");
        }
        ensureTosAvailable();

        String objectKey = buildScriptVideoObjectKey(parseResult, sourceUrl);
        String publicUrl = buildTosPublicUrl(objectKey);
        if (tosObjectExists(objectKey)) {
            log.info("Script video TOS cache hit, objectKey={} url={}", objectKey, publicUrl);
            return publicUrl;
        }

        log.info("开始下载视频，time={} objectKey={}", LocalDateTime.now(), objectKey);
        HttpURLConnection connection = null;
        try {
            URL downloadUrl = URI.create(playUrl.trim()).toURL();
            connection = (HttpURLConnection) downloadUrl.openConnection();
            connection.setInstanceFollowRedirects(true);
            connection.setConnectTimeout(videoDownloadConnectTimeoutMillis);
            connection.setReadTimeout(videoDownloadReadTimeoutMillis);
            connection.setRequestProperty(HttpHeaders.USER_AGENT, DOWNLOAD_USER_AGENT);
            connection.setRequestProperty(HttpHeaders.ACCEPT, "video/mp4,video/*,*/*");

            int statusCode = connection.getResponseCode();
            if (statusCode < 200 || statusCode >= 300) {
                throw new BusinessException(50221, "Douyin playUrl download failed, HTTP " + statusCode);
            }
            long contentLength = connection.getContentLengthLong();
            if (contentLength > videoTransferMaxBytes) {
                throw new BusinessException(50221, "Douyin video is too large for model analysis: " + contentLength + " bytes");
            }
            String contentType = StringUtils.hasText(connection.getContentType())
                    ? connection.getContentType().trim()
                    : VIDEO_CONTENT_TYPE;
            log.info("视频下载响应已建立，time={} objectKey={} httpStatus={} contentLength={}",
                    LocalDateTime.now(), objectKey, statusCode, contentLength);
            if (contentLength >= 0) {
                uploadKnownLengthStream(objectKey, connection.getInputStream(), contentLength, contentType);
            } else {
                uploadUnknownLengthStream(objectKey, connection.getInputStream(), contentType);
            }
            log.info("视频下载完成，time={} objectKey={} contentLength={}", LocalDateTime.now(), objectKey, contentLength);
            log.info("Script video published to TOS, objectKey={} url={}", objectKey, publicUrl);
            return publicUrl;
        } catch (IOException exception) {
            throw new BusinessException(50221, "Douyin playUrl transfer to TOS failed: " + exception.getMessage());
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private void uploadKnownLengthStream(String objectKey, InputStream body, long contentLength, String contentType) {
        if (contentLength <= 0) {
            closeQuietly(body);
            throw new BusinessException(50221, "Douyin video download returned empty content");
        }
        try (InputStream in = body) {
            tosUploadService.putPublicObject(objectKey, in, contentLength, contentType);
        } catch (IOException exception) {
            throw new BusinessException(50221, "Douyin video stream close failed: " + exception.getMessage());
        }
    }

    private void uploadUnknownLengthStream(String objectKey, InputStream body, String contentType) {
        Path tempFile = null;
        try (InputStream in = body) {
            tempFile = Files.createTempFile("huashuo-script-video-", ".mp4");
            long copied = copyWithLimit(in, tempFile, videoTransferMaxBytes);
            if (copied <= 0) {
                throw new BusinessException(50221, "Douyin video download returned empty content");
            }
            try (InputStream fileIn = Files.newInputStream(tempFile)) {
                tosUploadService.putPublicObject(objectKey, fileIn, copied, contentType);
            }
        } catch (IOException exception) {
            throw new BusinessException(50221, "Douyin video temp transfer failed: " + exception.getMessage());
        } finally {
            if (tempFile != null) {
                try {
                    Files.deleteIfExists(tempFile);
                } catch (IOException exception) {
                    log.warn("Failed to delete script video temp file {}: {}", tempFile, exception.getMessage());
                }
            }
        }
    }

    private long copyWithLimit(InputStream inputStream, Path targetFile, long maxBytes) throws IOException {
        long copied = 0L;
        byte[] buffer = new byte[1024 * 1024];
        try (OutputStream out = Files.newOutputStream(targetFile)) {
            int read;
            while ((read = inputStream.read(buffer)) != -1) {
                copied += read;
                if (copied > maxBytes) {
                    throw new BusinessException(50221, "Douyin video is too large for model analysis: over " + maxBytes + " bytes");
                }
                out.write(buffer, 0, read);
            }
        }
        return copied;
    }

    private void ensureTosAvailable() {
        if (!tosProperties.enabled()) {
            throw new BusinessException(50001, "TOS is required for Douyin video script analysis; enable volcengine.tos.enabled");
        }
        if (tosClient.getIfAvailable() == null) {
            throw new BusinessException(50001, "TOS client is not initialized; configure VOLCENGINE_TOS_ACCESS_KEY_ID and secret");
        }
        if (!StringUtils.hasText(tosProperties.bucket())) {
            throw new BusinessException(50001, "volcengine.tos.bucket is not configured");
        }
        if (!StringUtils.hasText(tosProperties.publicBaseUrl())) {
            throw new BusinessException(50001, "volcengine.tos.public-base-url is not configured");
        }
    }

    private boolean tosObjectExists(String objectKey) {
        TOSV2 client = tosClient.getIfAvailable();
        if (client == null) {
            return false;
        }
        try {
            var output = client.headObject(new HeadObjectV2Input()
                    .setBucket(tosProperties.bucket())
                    .setKey(objectKey));
            return output != null && output.getContentLength() > 0;
        } catch (TosException exception) {
            if (exception.getStatusCode() == 404) {
                return false;
            }
            throw new BusinessException(50000, "TOS object cache check failed: " + exception.getMessage());
        }
    }

    private String buildScriptVideoObjectKey(DouyinVideoParseResponse parseResult, String sourceUrl) {
        String cacheSeed = firstNonBlank(
                parseResult == null ? null : parseResult.getVideoId(),
                sourceUrl,
                parseResult == null ? null : parseResult.getPlayUrl()
        );
        return SCRIPT_VIDEO_OBJECT_PREFIX + safeCacheKey(cacheSeed) + ".mp4";
    }

    private String safeCacheKey(String value) {
        String trimmed = StringUtils.hasText(value) ? value.trim() : "unknown";
        String normalized = trimmed.toLowerCase(Locale.ROOT);
        if (normalized.matches("[a-z0-9][a-z0-9._-]{0,120}")) {
            return normalized;
        }
        return sha256Hex(trimmed);
    }

    private String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new BusinessException(50000, "SHA-256 is not available");
        }
    }

    private String buildTosPublicUrl(String objectKey) {
        String base = tosProperties.publicBaseUrl().trim();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base + "/" + objectKey;
    }

    private int secondsToMillis(long seconds, int fallbackSeconds) {
        long safeSeconds = seconds > 0 ? seconds : fallbackSeconds;
        long millis = safeSeconds * 1000L;
        return millis > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) millis;
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value.trim();
            }
        }
        return null;
    }

    private void closeQuietly(InputStream inputStream) {
        if (inputStream == null) {
            return;
        }
        try {
            inputStream.close();
        } catch (IOException ignored) {
        }
    }

    private String extractMessageContent(String body) throws IOException {
        JsonNode root = objectMapper.readTree(body);
        JsonNode contentNode = root.at("/choices/0/message/content");
        if (contentNode.isMissingNode() || contentNode.isNull()) {
            return null;
        }
        return contentNode.asText();
    }

    private List<ScriptVO> parseScriptList(String content) {
        String json = stripJsonWrapper(content);
        try {
            return objectMapper.readValue(json, new TypeReference<List<ScriptVO>>() {});
        } catch (IOException exception) {
            log.warn("Failed to parse script JSON, raw content: {}", content);
            throw new BusinessException(50220,
                    "Doubao video understanding returned non-parsable content: " + abbreviate(content));
        }
    }

    private String stripJsonWrapper(String content) {
        String trimmed = content.trim();
        if (trimmed.startsWith("```")) {
            int firstNewline = trimmed.indexOf('\n');
            if (firstNewline > 0) {
                trimmed = trimmed.substring(firstNewline + 1);
            }
            if (trimmed.endsWith("```")) {
                trimmed = trimmed.substring(0, trimmed.length() - 3);
            }
            trimmed = trimmed.trim();
        }
        int firstBracket = trimmed.indexOf('[');
        int lastBracket = trimmed.lastIndexOf(']');
        if (firstBracket >= 0 && lastBracket > firstBracket) {
            return trimmed.substring(firstBracket, lastBracket + 1);
        }
        return trimmed;
    }

    private String arkErrorMessage(HttpResponse<String> response) {
        String body = response.body();
        if (!StringUtils.hasText(body)) {
            return "http=" + response.statusCode() + ", empty response body";
        }
        try {
            JsonNode root = objectMapper.readTree(body);
            String message = textByPaths(root, "/error/message", "/message");
            if (StringUtils.hasText(message)) {
                return "http=" + response.statusCode() + ", message=" + message;
            }
        } catch (IOException ignored) {
        }
        return "http=" + response.statusCode() + ", body=" + abbreviate(body);
    }

    private String textByPaths(JsonNode node, String... paths) {
        if (node == null) {
            return null;
        }
        for (String path : paths) {
            JsonNode value = node.at(path);
            if (value != null && !value.isMissingNode() && !value.isNull() && value.isTextual()) {
                String text = value.asText();
                if (StringUtils.hasText(text)) {
                    return text.trim();
                }
            }
        }
        return null;
    }

    private String abbreviate(String value) {
        if (value == null || value.length() <= 500) {
            return value;
        }
        return value.substring(0, 500);
    }

    private static String trimTrailingSlash(String value) {
        if (!StringUtils.hasText(value)) {
            return "https://ark.cn-beijing.volces.com/api/v3";
        }
        String trimmed = value.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }
}
