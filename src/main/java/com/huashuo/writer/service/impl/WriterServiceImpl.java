package com.huashuo.writer.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huashuo.common.exception.BusinessException;
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
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@Slf4j
public class WriterServiceImpl implements WriterService {

    private static final String HYBRID_VIDEO_DATA_PATH = "/api/v1/hybrid/video_data";
    private static final String DOUYIN_SHARE_VIDEO_PATH = "/api/v1/douyin/web/fetch_one_video_by_share_url";
    private static final String VOLCENGINE_SUBMIT_URL = "https://openspeech.bytedance.com/api/v3/auc/bigmodel/submit";
    private static final String VOLCENGINE_QUERY_URL = "https://openspeech.bytedance.com/api/v3/auc/bigmodel/query";
    private static final String VOLCENGINE_SUCCESS_CODE = "20000000";
    private static final String COPY_REWRITE_PROMPT_TEMPLATE = """
            你是一名短视频文案改写专家。请基于下面的原始口播文案，进行对标改写。
            要求：
            1. 保留原文案的核心卖点、信息结构和转化意图。
            2. 改写为适合短视频口播的自然中文，表达更流畅、有吸引力。
            3. 去除重复、口水话和无意义语气词。
            4. 不要虚构原文没有的事实、数字、品牌承诺。
            5. 只输出改写后的纯文本文案，不要解释。

            原始口播文案：
            %s
            """;

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final String tikhubBaseUrl;
    private final String tikhubApiKey;
    private final String ffmpegExecutable;
    private final String volcengineAppKey;
    private final String volcengineAccessKey;
    private final String volcengineResourceId;
    private final String volcengineModelName;
    private final String arkBaseUrl;
    private final String arkApiKey;
    private final String arkModel;

    public WriterServiceImpl(
            ObjectMapper objectMapper,
            @Value("${tikhub.base-url:https://api.tikhub.io}") String tikhubBaseUrl,
            @Value("${tikhub.api-key:${TIKHUB_API_KEY:}}") String tikhubApiKey,
            @Value("${ffmpeg.executable:${FFMPEG_PATH:ffmpeg}}") String ffmpegExecutable,
            @Value("${volcengine.asr.app-key:${VOLCENGINE_ASR_APP_KEY:}}") String volcengineAppKey,
            @Value("${volcengine.asr.access-key:${VOLCENGINE_ASR_ACCESS_KEY:}}") String volcengineAccessKey,
            @Value("${volcengine.asr.resource-id:${VOLCENGINE_ASR_RESOURCE_ID:volc.seedasr.auc}}") String volcengineResourceId,
            @Value("${volcengine.asr.model-name:${VOLCENGINE_ASR_MODEL_NAME:Doubao-pro-128k}}") String volcengineModelName,
            @Value("${volcengine.ark.base-url:${VOLCENGINE_ARK_BASE_URL:https://ark.cn-beijing.volces.com/api/v3}}") String arkBaseUrl,
            @Value("${volcengine.ark.api-key:${VOLCENGINE_ARK_API_KEY:}}") String arkApiKey,
            @Value("${volcengine.ark.model:${VOLCENGINE_ARK_MODEL:doubao-seed-2-0-mini-260215}}") String arkModel
    ) {
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(60))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        this.tikhubBaseUrl = trimTrailingSlash(tikhubBaseUrl);
        this.tikhubApiKey = tikhubApiKey;
        this.ffmpegExecutable = StringUtils.hasText(ffmpegExecutable) ? ffmpegExecutable.trim() : "ffmpeg";
        this.volcengineAppKey = volcengineAppKey;
        this.volcengineAccessKey = volcengineAccessKey;
        this.volcengineResourceId = StringUtils.hasText(volcengineResourceId) ? volcengineResourceId.trim() : "volc.seedasr.auc";
        this.volcengineModelName = StringUtils.hasText(volcengineModelName) ? volcengineModelName.trim() : "Doubao-pro-128k";
        this.arkBaseUrl = trimTrailingSlash(arkBaseUrl);
        this.arkApiKey = arkApiKey;
        this.arkModel = StringUtils.hasText(arkModel) ? arkModel.trim() : "doubao-seed-2-0-mini-260215";
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
        if (!StringUtils.hasText(volcengineAppKey) || !StringUtils.hasText(volcengineAccessKey)) {
            throw new BusinessException(50001, "Volcengine ASR app-key or access-key is not configured");
        }
        if (!StringUtils.hasText(arkApiKey)) {
            throw new BusinessException(50001, "Volcengine Ark api key is not configured");
        }

        Path mp4Path = null;
        Path mp3Path = null;
        try {
            log.info("开始进行音频文件转换");
            mp4Path = Files.createTempFile("douyin-video-", ".mp4");
            mp3Path = Files.createTempFile("douyin-audio-", ".mp3");
            downloadVideo(playUrl, mp4Path); // 下载视频
            convertToMp3(mp4Path, mp3Path); // 将视频转为音频
            log.info("转换音频文件成功，提交改写任务" + LocalDateTime.now());
            VolcengineAsrTask task = submitVolcengineAsrTask(mp3Path); // 提交改写的任务
            log.info("提交成功" + LocalDateTime.now());
            String originalText = queryVolcengineTranscript(task); // 轮询查看改写的结果
            log.info("轮询查看改写结果结束" + LocalDateTime.now());
            String translatedText = rewriteCopywriting(originalText);
            log.info("改写文案完成：" + LocalDateTime.now());

            return new WriterVO(originalText, translatedText);
        } catch (BusinessException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new BusinessException(50000, "Transcript temp file operation failed: " + exception.getMessage());
        } finally {
            deleteTempFile(mp4Path);
            deleteTempFile(mp3Path);
        }
    }

    private void downloadVideo(String playUrl, Path targetPath) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(playUrl))
                .timeout(Duration.ofMinutes(3))
                .header(HttpHeaders.ACCEPT, "*/*")
                .GET()
                .build();
        try {
            HttpResponse<Path> response = httpClient.send(request, HttpResponse.BodyHandlers.ofFile(targetPath));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new BusinessException(50210, "Download playUrl failed with HTTP " + response.statusCode());
            }
            if (Files.size(targetPath) == 0) {
                throw new BusinessException(50210, "Downloaded video is empty");
            }
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(40000, "Invalid playUrl: " + exception.getMessage());
        } catch (IOException exception) {
            throw new BusinessException(50210, "Download playUrl failed: " + exception.getMessage());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new BusinessException(50210, "Download playUrl was interrupted");
        }
    }

    private void convertToMp3(Path mp4Path, Path mp3Path) {
        ProcessBuilder processBuilder = new ProcessBuilder(
                ffmpegExecutable,
                "-hide_banner",
                "-loglevel", "error",
                "-y",
                "-i", mp4Path.toString(),
                "-vn",
                "-ac", "1",
                "-ar", "16000",
                "-codec:a", "libmp3lame",
                mp3Path.toString()
        );
        processBuilder.redirectErrorStream(true);

        try {
            Process process = processBuilder.start();
            boolean finished = process.waitFor(2, java.util.concurrent.TimeUnit.MINUTES);
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!finished) {
                process.destroyForcibly();
                throw new BusinessException(50211, "ffmpeg conversion timed out");
            }
            if (process.exitValue() != 0) {
                throw new BusinessException(50211, "ffmpeg conversion failed: " + abbreviate(output, 500));
            }
            if (Files.size(mp3Path) == 0) {
                throw new BusinessException(50211, "ffmpeg generated empty mp3");
            }
        } catch (IOException exception) {
            if (exception.getMessage() != null && exception.getMessage().contains("Cannot run program")) {
                throw new BusinessException(50211, "ffmpeg executable was not found: " + ffmpegExecutable + ". Please install ffmpeg, add it to PATH, or configure ffmpeg.executable");
            }
            throw new BusinessException(50211, "ffmpeg conversion failed: " + exception.getMessage());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new BusinessException(50211, "ffmpeg conversion was interrupted");
        }
    }

    private VolcengineAsrTask submitVolcengineAsrTask(Path mp3Path) {
        String taskId = UUID.randomUUID().toString();
        try {
            String audioData = Base64.getEncoder().encodeToString(Files.readAllBytes(mp3Path));
            Map<String, Object> body = Map.of(
                    "user", Map.of(
                            "uid", volcengineAppKey
                    ),
                    "audio", Map.of(
                            "data", audioData
                    ),
                    "request", Map.of(
                            "enable_speaker_info", true,
                            "model_name", volcengineModelName
                    )
            );
            HttpRequest request = HttpRequest.newBuilder(URI.create(VOLCENGINE_SUBMIT_URL))
                    .timeout(Duration.ofSeconds(60))
                    .header("X-Api-App-Key", volcengineAppKey)
                    .header("X-Api-Access-Key", volcengineAccessKey)
                    .header("X-Api-Resource-Id", volcengineResourceId)
                    .header("X-Api-Request-Id", taskId)
                    .header("X-Api-Sequence", "-1")
                    .header(HttpHeaders.CONTENT_TYPE, "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            String statusCode = response.headers().firstValue("X-Api-Status-Code").orElse(null);
            if (response.statusCode() < 200 || response.statusCode() >= 300 || !VOLCENGINE_SUCCESS_CODE.equals(statusCode)) {
                throw new BusinessException(50212, "Volcengine ASR submit failed: " + volcengineErrorMessage(response));
            }
            String xTtLogid = response.headers().firstValue("X-Tt-Logid").orElse("");
            return new VolcengineAsrTask(taskId, xTtLogid);
        } catch (IOException exception) {
            throw new BusinessException(50212, "Volcengine ASR submit failed: " + exception.getMessage());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new BusinessException(50212, "Volcengine ASR submit was interrupted");
        }
    }

    private String queryVolcengineTranscript(VolcengineAsrTask task) {
        for (int attempt = 0; attempt < 60; attempt++) {
            try {
                if (attempt == 0) {
                    Thread.sleep(5_000);
                } else {
                    Thread.sleep(10_000);
                }

                HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(URI.create(VOLCENGINE_QUERY_URL))
                        .timeout(Duration.ofSeconds(60))
                        .header("X-Api-App-Key", volcengineAppKey)
                        .header("X-Api-Access-Key", volcengineAccessKey)
                        .header("X-Api-Resource-Id", volcengineResourceId)
                        .header("X-Api-Request-Id", task.taskId())
                        .header(HttpHeaders.CONTENT_TYPE, "application/json");
                if (StringUtils.hasText(task.xTtLogid())) {
                    requestBuilder.header("X-Tt-Logid", task.xTtLogid());
                }

                HttpResponse<String> response = httpClient.send(
                        requestBuilder.POST(HttpRequest.BodyPublishers.ofString("{}")).build(),
                        HttpResponse.BodyHandlers.ofString()
                );
                String statusCode = response.headers().firstValue("X-Api-Status-Code").orElse(null);
                if (VOLCENGINE_SUCCESS_CODE.equals(statusCode)) {
                    String text = extractVolcengineText(response.body());
                    if (!StringUtils.hasText(text)) {
                        throw new BusinessException(50213, "Volcengine ASR query succeeded but returned empty text");
                    }
                    return text.trim();
                }
                if (response.statusCode() < 200 || response.statusCode() >= 300 || isVolcengineFinalFailure(statusCode)) {
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

    private String extractVolcengineText(String body) {
        try {
            JsonNode root = objectMapper.readTree(body);
            return firstNonBlank(
                    textByPaths(root, "/result/text"),
                    textByPaths(root, "/result/utterances/0/text"),
                    textByPaths(root, "/text")
            );
        } catch (IOException exception) {
            throw new BusinessException(50213, "Volcengine ASR query returned invalid JSON: " + exception.getMessage());
        }
    }

    private boolean isVolcengineFinalFailure(String statusCode) {
        return StringUtils.hasText(statusCode) && !statusCode.startsWith("2000");
    }

    private String volcengineErrorMessage(HttpResponse<String> response) {
        String statusCode = response.headers().firstValue("X-Api-Status-Code").orElse("missing");
        String message = response.headers().firstValue("X-Api-Message").orElse("");
        String body = abbreviate(response.body(), 500);
        return "http=" + response.statusCode() + ", status=" + statusCode + ", message=" + message + ", body=" + body;
    }

    private String rewriteCopywriting(String originalText) {
        if (!StringUtils.hasText(originalText)) {
            throw new BusinessException(50214, "Original transcript is empty, cannot rewrite copywriting");
        }

        try {
            String prompt = COPY_REWRITE_PROMPT_TEMPLATE.formatted(originalText.trim());
            Map<String, Object> body = Map.of(
                    "model", arkModel,
                    "messages", List.of(Map.of(
                            "role", "user",
                            "content", prompt
                    ))
            );
            HttpRequest request = HttpRequest.newBuilder(URI.create(arkBaseUrl + "/chat/completions"))
                    .timeout(Duration.ofSeconds(60))
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + arkApiKey)
                    .header(HttpHeaders.CONTENT_TYPE, "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
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

    private void deleteTempFile(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
        }
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

    private record VolcengineAsrTask(String taskId, String xTtLogid) {
    }
}
