package com.huashuo.writer.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huashuo.common.exception.BusinessException;
import com.huashuo.upload.config.UploadProperties;
import com.huashuo.upload.tos.TosUploadService;
import com.huashuo.upload.tos.UploadPublicBaseProvider;
import com.huashuo.writer.dto.RewriteDTO;
import com.huashuo.writer.pojo.DouyinAuthorInfo;
import com.huashuo.writer.pojo.DouyinVideoParseRequest;
import com.huashuo.writer.pojo.DouyinVideoParseResponse;
import com.huashuo.writer.pojo.DouyinVideoTranscriptRequest;
import com.huashuo.writer.pojo.WriterVO;
import com.huashuo.writer.service.WriterService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

@Service
@Slf4j
public class WriterServiceImpl implements WriterService {

    private static final String HYBRID_VIDEO_DATA_PATH = "/api/v1/hybrid/video_data";
    private static final String DOUYIN_SHARE_VIDEO_PATH = "/api/v1/douyin/web/fetch_one_video_by_share_url";
    private static final String VOLCENGINE_SUBMIT_URL = "https://openspeech.bytedance.com/api/v1/auc/submit";
    private static final String VOLCENGINE_QUERY_URL = "https://openspeech.bytedance.com/api/v1/auc/query";
    private static final int VOLCENGINE_SUCCESS_CODE = 1000;
    private static final String VOLCENGINE_AUDIO_FORMAT = "mp4";
    private static final String PREPROCESSED_AUDIO_FORMAT = "mp3";
    private static final String PREPROCESSED_AUDIO_CONTENT_TYPE = "audio/mpeg";
    private static final String COPY_REWRITE_PROMPT_BASE = "改写以下短视频口播文案：保留核心信息，去除口水话，不虚构内容，只输出改写后的纯文本。";

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final UploadProperties uploadProperties;
    private final TosUploadService tosUploadService;
    private final UploadPublicBaseProvider uploadPublicBaseProvider;
    private final String tikhubBaseUrl;
    private final String tikhubApiKey;
    private final String volcengineAppId;
    private final String volcengineToken;
    private final String volcengineCluster;
    private final String arkBaseUrl;
    private final String arkApiKey;
    private final String arkModel;
    private final boolean audioPreprocessEnabled;
    private final String ffmpegPath;
    private final double audioSpeed;
    private final long audioPreprocessTimeoutSeconds;

    public WriterServiceImpl(
            ObjectMapper objectMapper,
            UploadProperties uploadProperties,
            TosUploadService tosUploadService,
            UploadPublicBaseProvider uploadPublicBaseProvider,
            @Value("${tikhub.base-url:https://api.tikhub.io}") String tikhubBaseUrl,
            @Value("${tikhub.api-key:${TIKHUB_API_KEY:}}") String tikhubApiKey,
            @Value("${volcengine.asr.app-key:${VOLCENGINE_ASR_APP_KEY:}}") String volcengineAppId,
            @Value("${volcengine.asr.access-key:${VOLCENGINE_ASR_ACCESS_KEY:}}") String volcengineToken,
            @Value("${volcengine.asr.cluster:${VOLCENGINE_ASR_CLUSTER:volc_auc_common}}") String volcengineCluster,
            @Value("${volcengine.ark.base-url:${VOLCENGINE_ARK_BASE_URL:https://ark.cn-beijing.volces.com/api/v3}}") String arkBaseUrl,
            @Value("${volcengine.ark.api-key:${VOLCENGINE_ARK_API_KEY:}}") String arkApiKey,
            @Value("${volcengine.ark.model:${VOLCENGINE_ARK_MODEL:doubao-seed-2-0-mini-260215}}") String arkModel,
            @Value("${writer.audio-preprocess.enabled:true}") boolean audioPreprocessEnabled,
            @Value("${writer.audio-preprocess.ffmpeg-path:ffmpeg}") String ffmpegPath,
            @Value("${writer.audio-preprocess.speed:1.2}") double audioSpeed,
            @Value("${writer.audio-preprocess.timeout-seconds:900}") long audioPreprocessTimeoutSeconds
    ) {
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(60))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        this.uploadProperties = uploadProperties;
        this.tosUploadService = tosUploadService;
        this.uploadPublicBaseProvider = uploadPublicBaseProvider;
        this.tikhubBaseUrl = trimTrailingSlash(tikhubBaseUrl);
        this.tikhubApiKey = tikhubApiKey;
        this.volcengineAppId = volcengineAppId;
        this.volcengineToken = volcengineToken;
        this.volcengineCluster = StringUtils.hasText(volcengineCluster) ? volcengineCluster.trim() : "volc_auc_common";
        this.arkBaseUrl = trimTrailingSlash(arkBaseUrl);
        this.arkApiKey = arkApiKey;
        this.arkModel = StringUtils.hasText(arkModel) ? arkModel.trim() : "doubao-seed-2-0-mini-260215";
        this.audioPreprocessEnabled = audioPreprocessEnabled;
        this.ffmpegPath = StringUtils.hasText(ffmpegPath) ? ffmpegPath.trim() : "ffmpeg";
        this.audioSpeed = audioSpeed > 0 ? audioSpeed : 1.2D;
        this.audioPreprocessTimeoutSeconds = audioPreprocessTimeoutSeconds > 0 ? audioPreprocessTimeoutSeconds : 900L;
    }

    /**
     * 解析抖音视频成可下载的视频链接
     * @param request
     * @return
     */
    @Override
    public DouyinVideoParseResponse parseDouyinVideo(DouyinVideoParseRequest request) {
        String shareUrl = firstNonBlank(request == null ? null : request.getUrl());
        if (!StringUtils.hasText(shareUrl)) {
            throw new BusinessException(40000, "shareUrl or url is required");
        }
        if (!StringUtils.hasText(tikhubApiKey)) {
            throw new BusinessException(50001, "TikHub api key is not configured");
        }

        RuntimeException hybridError = null;
        try {
            JsonNode hybridResponse = callTikHub(HYBRID_VIDEO_DATA_PATH, "url", shareUrl);
            if (hasData(hybridResponse)) {
                return toVideoParseResponse(hybridResponse, HYBRID_VIDEO_DATA_PATH);
            }
        } catch (RuntimeException exception) {
            hybridError = exception;
        }

        try {
            JsonNode shareResponse = callTikHub(DOUYIN_SHARE_VIDEO_PATH, "share_url", shareUrl);
            if (hasData(shareResponse)) {
                return toVideoParseResponse(shareResponse, DOUYIN_SHARE_VIDEO_PATH);
            }
            throw new BusinessException(50202, "TikHub returned empty video data");
        } catch (RuntimeException exception) {
            String reason = hybridError == null ? exception.getMessage() : exception.getMessage() + "; hybrid error: " + hybridError.getMessage();
            throw new BusinessException(50201, "TikHub parse failed: " + reason);
        }
    }

    /**
     * 提取抖音视频的文字
     */
    @Override
    public WriterVO extractDouyinVideoTranscript(DouyinVideoTranscriptRequest request) {
        String playUrl = request == null ? null : trimToNull(request.getPlayUrl());
        if (!StringUtils.hasText(playUrl)) {
            throw new BusinessException(40000, "playUrl is required");
        }
        if (!StringUtils.hasText(volcengineAppId) || !StringUtils.hasText(volcengineToken)) {
            throw new BusinessException(50001, "Volcengine ASR app-key or access-key is not configured");
        }
        if (!StringUtils.hasText(arkApiKey)) {
            throw new BusinessException(50001, "Volcengine Ark api key is not configured");
        }

        log.info("开始下载视频并抽取音轨：" + LocalDateTime.now());
        AsrMedia asrMedia = prepareAudioForAsr(playUrl);
        log.info("音轨抽取完成，提交火山 ASR：" + LocalDateTime.now());
        String taskId = submitVolcengineAsrTask(asrMedia.url(), asrMedia.format());
        log.info("提交成功，taskId=" + taskId + " " + LocalDateTime.now());
        String originalText = queryVolcengineTranscript(taskId);
        log.info("轮询查看识别结果结束 " + LocalDateTime.now());

        return new WriterVO(originalText, null);
    }

    @Override
    public WriterVO rewriteDouyinVideo(RewriteDTO request) {
        log.info("开始改写文案：" + LocalDateTime.now());
        String translatedText = rewriteCopywriting(request);
        log.info("改写文案完成：" + LocalDateTime.now());
        return new WriterVO(null, translatedText);
    }

    private AsrMedia prepareAudioForAsr(String playUrl) {
        if (!audioPreprocessEnabled) {
            return new AsrMedia(playUrl, VOLCENGINE_AUDIO_FORMAT);
        }
        String publicBaseUrl = uploadPublicBaseProvider.effectivePublicBaseUrl();
        if (!StringUtils.hasText(publicBaseUrl)) {
            throw new BusinessException(50001, "Upload public base url is not configured; cannot publish preprocessed audio for ASR");
        }

        String datePath = LocalDate.now().toString();
        String baseName = "writer-asr-" + UUID.randomUUID();
        Path targetDir = Path.of(uploadProperties.localRoot(), "writer", "asr", datePath);
        Path sourceVideoFile = targetDir.resolve(baseName + ".mp4");
        Path targetAudioFile = targetDir.resolve(baseName + ".mp3");
        String previewUrl = uploadProperties.effectivePreviewPrefix() + "/writer/asr/" + datePath + "/" + baseName + ".mp3";

        try {
            Files.createDirectories(targetDir);
            downloadSourceVideo(playUrl, sourceVideoFile);
            runFfmpegAudioExtract(sourceVideoFile, targetAudioFile);
            long fileSize = Files.size(targetAudioFile);
            if (fileSize <= 0) {
                throw new BusinessException(50214, "Preprocessed audio is empty");
            }
            try (var in = Files.newInputStream(targetAudioFile)) {
                tosUploadService.putPublicObject(
                        TosUploadService.previewUrlToObjectKey(previewUrl),
                        in,
                        fileSize,
                        PREPROCESSED_AUDIO_CONTENT_TYPE
                );
            }
            String resultUrl = publicBaseUrl + previewUrl;
            log.info("ASR audio preprocess finished, size={} url={}", fileSize, resultUrl);
            return new AsrMedia(resultUrl, PREPROCESSED_AUDIO_FORMAT);
        } catch (IOException exception) {
            throw new BusinessException(50214, "ASR audio preprocess failed: " + exception.getMessage());
        } finally {
            deleteIfExists(sourceVideoFile);
        }
    }

    private void downloadSourceVideo(String sourceUrl, Path targetFile) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(sourceUrl))
                    .timeout(Duration.ofSeconds(audioPreprocessTimeoutSeconds))
                    .GET()
                    .build();
            HttpResponse<Path> response = httpClient.send(request, HttpResponse.BodyHandlers.ofFile(targetFile));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new BusinessException(50214, "Source video download failed, HTTP " + response.statusCode());
            }
            long fileSize = Files.size(targetFile);
            if (fileSize <= 0) {
                throw new BusinessException(50214, "Downloaded source video is empty");
            }
            log.info("ASR source video downloaded, size={}", fileSize);
        } catch (BusinessException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new BusinessException(50214, "Source video download failed: " + exception.getMessage());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new BusinessException(50214, "Source video download was interrupted");
        }
    }

    private void runFfmpegAudioExtract(Path sourceVideoFile, Path targetAudioFile) {
        List<String> command = List.of(
                ffmpegPath,
                "-y",
                "-hide_banner",
                "-loglevel", "warning",
                "-i", sourceVideoFile.toAbsolutePath().toString(),
                "-vn",
                "-af", "aresample=16000,atempo=" + audioSpeed,
                "-c:a", "libmp3lame",
                "-b:a", "32k",
                "-ac", "1",
                "-ar", "16000",
                targetAudioFile.toAbsolutePath().toString()
        );
        try {
            Process process = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .start();
            CompletableFuture<String> outputFuture = CompletableFuture.supplyAsync(() -> readProcessOutput(process));
            boolean finished = process.waitFor(audioPreprocessTimeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new BusinessException(50214, "ASR audio extract timed out after " + audioPreprocessTimeoutSeconds + " seconds");
            }
            String output = outputFuture.get(3, TimeUnit.SECONDS);
            if (process.exitValue() != 0) {
                throw new BusinessException(50214, "ASR audio extract failed: " + abbreviate(output, 1000));
            }
        } catch (BusinessException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new BusinessException(50214, "ASR audio extract failed: " + exception.getMessage());
        }
    }

    private record AsrMedia(String url, String format) {
    }

    private String readProcessOutput(Process process) {
        try (var reader = process.inputReader(StandardCharsets.UTF_8)) {
            StringBuilder output = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                if (output.length() < 2000) {
                    output.append(line).append('\n');
                }
            }
            return output.toString();
        } catch (IOException exception) {
            return exception.getMessage();
        }
    }

    private void deleteIfExists(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException exception) {
            log.warn("Failed to delete temp source video {}: {}", path, exception.getMessage());
        }
    }

    private String submitVolcengineAsrTask(String playUrl, String format) {
        String taskId = UUID.randomUUID().toString();
        try {
            Map<String, Object> body = Map.of(
                    "app", Map.of(
                            "appid", volcengineAppId,
                            "token", volcengineToken,
                            "cluster", volcengineCluster
                    ),
                    "user", Map.of(
                            "uid", volcengineAppId
                    ),
                    "audio", Map.of(
                            "url", playUrl,
                            "format", format
                    ),
                    "request", Map.of(
                            "reqid", taskId
                    ),
                    "additions", Map.of(
                            "use_itn", "True",
                            "use_punc", "True",
                            "with_speaker_info", "True"
                    )
            );
            HttpRequest request = HttpRequest.newBuilder(URI.create(VOLCENGINE_SUBMIT_URL))
                    .timeout(Duration.ofSeconds(60))
                    .header(HttpHeaders.AUTHORIZATION, "Bearer; " + volcengineToken)
                    .header(HttpHeaders.CONTENT_TYPE, "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new BusinessException(50212, "Volcengine ASR submit failed: " + volcengineErrorMessage(response));
            }
            JsonNode root = objectMapper.readTree(response.body());
            JsonNode resp = root.path("resp");
            int code = resp.path("code").asInt(-1);
            if (code != VOLCENGINE_SUCCESS_CODE) {
                throw new BusinessException(50212, "Volcengine ASR submit failed: " + volcengineErrorMessage(response));
            }
            String returnedId = resp.path("id").asText(null);
            return StringUtils.hasText(returnedId) ? returnedId : taskId;
        } catch (IOException exception) {
            throw new BusinessException(50212, "Volcengine ASR submit failed: " + exception.getMessage());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new BusinessException(50212, "Volcengine ASR submit was interrupted");
        }
    }

    private String queryVolcengineTranscript(String taskId) {
        for (int attempt = 0; attempt < 60; attempt++) {
            try {
                Thread.sleep(attempt == 0 ? 5_000 : 10_000);

                Map<String, Object> body = Map.of(
                        "appid", volcengineAppId,
                        "token", volcengineToken,
                        "cluster", volcengineCluster,
                        "id", taskId
                );
                HttpRequest request = HttpRequest.newBuilder(URI.create(VOLCENGINE_QUERY_URL))
                        .timeout(Duration.ofSeconds(60))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer; " + volcengineToken)
                        .header(HttpHeaders.CONTENT_TYPE, "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                        .build();

                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    throw new BusinessException(50213, "Volcengine ASR query failed: " + volcengineErrorMessage(response));
                }
                JsonNode root = objectMapper.readTree(response.body());
                JsonNode resp = root.path("resp");
                int code = resp.path("code").asInt(-1);
                if (code == VOLCENGINE_SUCCESS_CODE) {
                    String text = firstNonBlank(
                            textByPaths(resp, "/text"),
                            textByPaths(resp, "/utterances/0/text")
                    );
                    if (!StringUtils.hasText(text)) {
                        throw new BusinessException(50213, "Volcengine ASR query succeeded but returned empty text");
                    }
                    return text.trim();
                }
                if (!isVolcenginePending(code)) {
                    throw new BusinessException(50213, "Volcengine ASR query failed: " + volcengineErrorMessage(response));
                }
            } catch (IOException exception) {
                throw new BusinessException(50213, "Volcengine ASR query failed: " + exception.getMessage());
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new BusinessException(50213, "Volcengine ASR query was interrupted");
            }
        }
        throw new BusinessException(50213, "Volcengine ASR query timed out");
    }

    private boolean isVolcenginePending(int code) {
        return code == 2000 || code == 2001;
    }

    private String volcengineErrorMessage(HttpResponse<String> response) {
        return "http=" + response.statusCode() + ", body=" + abbreviate(response.body(), 500);
    }

    private String rewriteCopywriting(RewriteDTO request) {
        String originalText = request == null ? null : request.getOriginalText();
        if (!StringUtils.hasText(originalText)) {
            throw new BusinessException(50214, "Original transcript is empty, cannot rewrite copywriting");
        }

        try {
            String prompt = buildRewritePrompt(originalText.trim(), request.getStyle(), request.getIntroduce());
            Map<String, Object> body = Map.of(
                    "model", arkModel,
                    "messages", List.of(Map.of(
                            "role", "user",
                            "content", prompt
                    ))
            );
            HttpRequest httpRequest = HttpRequest.newBuilder(URI.create(arkBaseUrl + "/chat/completions"))
                    .timeout(Duration.ofSeconds(60))
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + arkApiKey)
                    .header(HttpHeaders.CONTENT_TYPE, "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                    .build();

            HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new BusinessException(50214, "Doubao rewrite failed: " + arkErrorMessage(response));
            }

            String rewrittenText = extractArkMessageContent(response.body());
            if (!StringUtils.hasText(rewrittenText)) {
                throw new BusinessException(50214, "Doubao rewrite returned empty text");
            }
            return rewrittenText.trim();
        } catch (IOException exception) {
            throw new BusinessException(50214, "Doubao rewrite failed: " + exception.getMessage());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new BusinessException(50214, "Doubao rewrite was interrupted");
        }
    }

    private String buildRewritePrompt(String originalText, String style, String introduce) {
        StringBuilder prompt = new StringBuilder(COPY_REWRITE_PROMPT_BASE);
        if (StringUtils.hasText(style)) {
            prompt.append("\n风格：").append(style.trim());
        }
        if (StringUtils.hasText(introduce)) {
            prompt.append("\n用户期望：").append(introduce.trim());
        }
        prompt.append("\n原文：").append(originalText);
        return prompt.toString();
    }

    private String extractArkMessageContent(String body) {
        try {
            JsonNode root = objectMapper.readTree(body);
            return firstNonBlank(
                    textByPaths(root, "/choices/0/message/content"),
                    textByPaths(root, "/choices/0/text")
            );
        } catch (IOException exception) {
            throw new BusinessException(50214, "Doubao rewrite returned invalid JSON: " + exception.getMessage());
        }
    }

    private String arkErrorMessage(HttpResponse<String> response) {
        String body = response.body();
        if (!StringUtils.hasText(body)) {
            return "http=" + response.statusCode() + ", empty response body";
        }
        try {
            JsonNode root = objectMapper.readTree(body);
            return "http=" + response.statusCode() + ", message=" + firstNonBlank(
                    textByPaths(root, "/error/message"),
                    textByPaths(root, "/message"),
                    abbreviate(body, 500)
            );
        } catch (IOException exception) {
            return "http=" + response.statusCode() + ", body=" + abbreviate(body, 500);
        }
    }

    private JsonNode callTikHub(String path, String queryName, String queryValue) {
        URI uri = UriComponentsBuilder.fromUriString(tikhubBaseUrl + path)
                .queryParam(queryName, queryValue)
                .build()
                .encode()
                .toUri();
        HttpRequest httpRequest = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(30))
                .header(HttpHeaders.ACCEPT, "application/json")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tikhubApiKey)
                .GET()
                .build();

        try {
            HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new BusinessException(50200, "TikHub request failed with HTTP " + response.statusCode());
            }

            JsonNode body = objectMapper.readTree(response.body());
            int code = body.path("code").asInt(200);
            if (code != 200) {
                String message = firstNonBlank(body.path("message_zh").asText(null), body.path("message").asText(null), "TikHub request failed");
                throw new BusinessException(50200, message);
            }
            return body;
        } catch (IOException exception) {
            throw new BusinessException(50200, "TikHub request failed: " + exception.getMessage());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new BusinessException(50200, "TikHub request was interrupted");
        }
    }

    private DouyinVideoParseResponse toVideoParseResponse(JsonNode tikhubResponse, String sourceEndpoint) {
        JsonNode data = tikhubResponse.path("data");
        JsonNode detail = data.path("aweme_detail").isMissingNode() ? data : data.path("aweme_detail");

        return new DouyinVideoParseResponse(
                firstNonBlank(
                        textByPaths(data, "/video_id", "/aweme_id", "/item_id"),
                        textByPaths(detail, "/video_id", "/aweme_id", "/item_id")
                ),
                findPlayUrl(data),
                firstNonBlank(textByPaths(data, "/title", "/desc", "/description"), textByPaths(detail, "/title", "/desc", "/description")),
                new DouyinAuthorInfo(
                        firstNonBlank(textByPaths(data, "/author/uid", "/author/user_id", "/author/id"), textByPaths(detail, "/author/uid", "/author/user_id", "/author/id")),
                        firstNonBlank(textByPaths(data, "/author/sec_uid", "/author/secUid"), textByPaths(detail, "/author/sec_uid", "/author/secUid")),
                        firstNonBlank(textByPaths(data, "/author/nickname", "/author/name", "/author/unique_id"), textByPaths(detail, "/author/nickname", "/author/name", "/author/unique_id")),
                        firstNonBlank(firstUrlFromObject(data.path("author"), "avatar_thumb"), firstUrlFromObject(detail.path("author"), "avatar_thumb"))
                ),
                findCoverUrl(data),
                normalizeDurationSeconds(firstNonBlank(textByPaths(data, "/duration", "/video/duration"), textByPaths(detail, "/duration", "/video/duration"))),
                sourceEndpoint,
                tikhubResponse.path("request_id").asText(null),
                data
        );
    }

    private String findPlayUrl(JsonNode node) {
        return firstNonBlank(
                textByPaths(
                        node,
                        "/original_video_url",
                        "/video_data/original_video_url",
                        "/video/play_addr/url_list/0",
                        "/video/play_addr_h264/url_list/0",
                        "/video/download_addr/url_list/0",
                        "/video/bit_rate/0/play_addr/url_list/0",
                        "/aweme_detail/video/play_addr/url_list/0",
                        "/aweme_detail/video/play_addr_h264/url_list/0",
                        "/aweme_detail/video/download_addr/url_list/0",
                        "/aweme_detail/video/bit_rate/0/play_addr/url_list/0"
                ),
                firstUrlFromObject(node, "play_addr"),
                firstUrlFromObject(node, "play_addr_h264"),
                firstUrlFromObject(node, "download_addr")
        );
    }

    private String findCoverUrl(JsonNode node) {
        return firstNonBlank(
                textByPaths(
                        node,
                        "/cover",
                        "/cover_url",
                        "/video/cover/url_list/0",
                        "/video/origin_cover/url_list/0",
                        "/video/dynamic_cover/url_list/0",
                        "/aweme_detail/video/cover/url_list/0",
                        "/aweme_detail/video/origin_cover/url_list/0",
                        "/aweme_detail/video/dynamic_cover/url_list/0"
                ),
                firstUrlFromObject(node, "cover"),
                firstUrlFromObject(node, "origin_cover"),
                firstUrlFromObject(node, "dynamic_cover")
        );
    }

    private String firstUrlFromObject(JsonNode node, String fieldName) {
        List<JsonNode> matches = new ArrayList<>();
        collectNamedNodes(node, fieldName, matches);
        for (JsonNode match : matches) {
            String url = firstUrlFromUrlNode(match);
            if (StringUtils.hasText(url)) {
                return url;
            }
        }
        return null;
    }

    private void collectNamedNodes(JsonNode node, String fieldName, List<JsonNode> matches) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return;
        }
        if (node.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                if (fieldName.equals(field.getKey())) {
                    matches.add(field.getValue());
                }
                collectNamedNodes(field.getValue(), fieldName, matches);
            }
        } else if (node.isArray()) {
            for (JsonNode child : node) {
                collectNamedNodes(child, fieldName, matches);
            }
        }
    }

    private String firstUrlFromUrlNode(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        if (node.isTextual()) {
            return trimToNull(node.asText());
        }
        if (node.isArray()) {
            for (JsonNode item : node) {
                String url = firstUrlFromUrlNode(item);
                if (StringUtils.hasText(url)) {
                    return url;
                }
            }
            return null;
        }
        if (node.isObject()) {
            return firstNonBlank(
                    firstUrlFromUrlNode(node.path("url_list")),
                    firstUrlFromUrlNode(node.path("url")),
                    firstUrlFromUrlNode(node.path("uri"))
            );
        }
        return null;
    }

    private String textByPaths(JsonNode node, String... paths) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        for (String path : paths) {
            JsonNode value = node.at(path);
            String text = textValue(value);
            if (StringUtils.hasText(text)) {
                return text;
            }
        }
        return null;
    }

    private String textValue(JsonNode value) {
        if (value == null || value.isMissingNode() || value.isNull()) {
            return null;
        }
        if (value.isTextual() || value.isNumber() || value.isBoolean()) {
            return trimToNull(value.asText());
        }
        if (value.isArray() && !value.isEmpty()) {
            return textValue(value.get(0));
        }
        return null;
    }

    private Long normalizeDurationSeconds(String duration) {
        if (!StringUtils.hasText(duration)) {
            return null;
        }
        try {
            long value = Long.parseLong(duration);
            return value > 1000 ? value / 1000 : value;
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private boolean hasData(JsonNode response) {
        JsonNode data = response == null ? null : response.path("data");
        return data != null && !data.isMissingNode() && !data.isNull();
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value;
            }
        }
        return null;
    }

    private String trimToNull(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        return value.trim();
    }

    private String abbreviate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }

    private static String trimTrailingSlash(String value) {
        if (!StringUtils.hasText(value)) {
            return "https://api.tikhub.io";
        }
        String trimmed = value.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }
}
